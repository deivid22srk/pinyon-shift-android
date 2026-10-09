#include "pinyon_shift_realtime_log.h"

#include "crash_reporter.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <regex>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#include <spdlog/sinks/base_sink.h>

#include <rex/cvar.h>
#include <rex/logging.h>

#include "platform/host_platform.h"

namespace pinyon_shift::diagnostics {
namespace {

// One append-mode log stream. Every written line is flushed immediately
// (real-time requirement: the log must be complete up to the last event when
// the process dies), consecutive identical payloads collapse into a counter
// line so a stuck loop cannot fill storage or drown distinct errors (the
// dedup key is the payload, NOT the formatted line - the timestamp would
// defeat it), and the file rotates at 200 MB keeping one .old generation.
// Append mode throughout: the crash reporter writes crash.log through its own
// raw descriptor from the signal handler, and independent descriptors must
// not fight over offsets.
class LogStream {
 public:
  ~LogStream() { Close(); }

  bool Open(const std::filesystem::path& path) {
    path_ = path;
    file_ = std::fopen(path.string().c_str(), "a");
    return file_ != nullptr;
  }

  const std::string& name() const { return name_; }
  void set_name(const char* name) { name_ = name; }

  void WriteLine(const std::string& key, const std::string& line) {
    if (!file_) {
      return;
    }
    if (key == pending_key_) {
      ++pending_repeats_;
      // The repeated line itself is not written until a different message (or
      // a flush) closes the run; its first occurrence is already on disk, so
      // nothing distinct is ever lost.
      return;
    }
    FlushRepeats();
    pending_key_ = key;
    WriteRaw(line);
  }

  void FlushRepeats() {
    if (pending_repeats_ > 0 && file_) {
      const std::string note = "... repeated " + std::to_string(pending_repeats_) + "x more";
      std::fputs(note.c_str(), file_);
      std::fputc('\n', file_);
      std::fflush(file_);
      written_ += note.size() + 1;
      session_total_ += note.size() + 1;
      pending_repeats_ = 0;
    }
  }

  void Flush() {
    FlushRepeats();
    if (file_) {
      std::fflush(file_);
    }
  }

  uint64_t written() const { return written_; }

  // Everything this stream has written since the session began, across
  // rotations - the whole-session budget must survive a rotation resetting
  // the per-file counter.
  uint64_t session_total() const { return session_total_; }

  // Stops the stream permanently with a final marker line (the session budget
  // gate). The crash stream is never closed this way.
  void CloseWithNote(const std::string& note) {
    if (file_) {
      Flush();
      std::fputs(note.c_str(), file_);
      std::fputc('\n', file_);
      std::fflush(file_);
      session_total_ += note.size() + 1;
      std::fclose(file_);
      file_ = nullptr;
    }
  }

 private:
  void Close() {
    if (file_) {
      Flush();
      std::fclose(file_);
      file_ = nullptr;
    }
  }

  void WriteRaw(const std::string& line) {
    std::fputs(line.c_str(), file_);
    std::fputc('\n', file_);
    std::fflush(file_);
    written_ += line.size() + 1;
    session_total_ += line.size() + 1;
    if (written_ > kMaxFileBytes) {
      Rotate();
    }
  }

  void Rotate() {
    std::fclose(file_);
    file_ = nullptr;
    std::error_code error;
    const std::filesystem::path old = path_.string() + ".old";
    std::filesystem::remove(old, error);
    error.clear();
    std::filesystem::rename(path_, old, error);
    const bool renamed = !error;
    file_ = std::fopen(path_.string().c_str(), "a");
    if (!file_) {
      return;  // Stream dead; nothing more this session can do.
    }
    if (!renamed) {
      // The file was not rotated away: writing would exceed the size limit
      // with no way forward, so stop this stream instead of spinning.
      std::fclose(file_);
      file_ = nullptr;
      return;
    }
    written_ = 0;
    const std::string note = "--- rotated; previous generation in " + old.filename().string() +
                             " ---";
    std::fputs(note.c_str(), file_);
    std::fputc('\n', file_);
    std::fflush(file_);
    session_total_ += note.size() + 1;
  }

  static constexpr uint64_t kMaxFileBytes = 200ull * 1024 * 1024;

  std::filesystem::path path_;
  std::string name_;
  std::FILE* file_ = nullptr;
  uint64_t written_ = 0;
  uint64_t session_total_ = 0;
  std::string pending_key_;
  uint64_t pending_repeats_ = 0;
};

// Formats the message's own clock value (no system_clock::now per line, no
// ostringstream) as UTC.
std::string FormatTime(spdlog::log_clock::time_point time) {
  const auto duration = time.time_since_epoch();
  const std::time_t seconds = std::chrono::duration_cast<std::chrono::seconds>(duration).count();
  const int milliseconds =
      int(std::chrono::duration_cast<std::chrono::milliseconds>(duration).count() % 1000);
  std::tm utc{};
#if defined(_WIN32)
  gmtime_s(&utc, &seconds);
#else
  gmtime_r(&seconds, &utc);
#endif
  char buffer[40];
  std::snprintf(buffer, sizeof(buffer), "%04d-%02d-%02d %02d:%02d:%02d.%03d",
                utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday, utc.tm_hour, utc.tm_min,
                utc.tm_sec, milliseconds);
  return buffer;
}

bool Contains(std::string_view text, const char* needle) {
  return text.find(needle) != std::string_view::npos;
}

// The multiplexed sink attached to every logger (rex::AddSink adds it to all
// current and future categories). Each message is formatted once and routed
// by category and stable message prefixes (the "fh1 fmv" convention, the
// driver's "Vulkan ..." debug-utils lines, the breadcrumb dump); all.log
// always receives everything. A whole-session budget keeps the worst case
// bounded: past it only crash.log keeps accepting lines.
class RealtimeLogSink : public spdlog::sinks::base_sink<std::mutex> {
 public:
  RealtimeLogSink(const std::filesystem::path& session_dir, const std::string& header) {
    struct Spec {
      const char* name;
      LogStream** target;
    };
    Spec specs[] = {
        {"all", &all_},     {"gpu", &gpu_},       {"vulkan", &vulkan_},  {"fmv", &fmv_},
        {"files", &files_}, {"audio", &audio_},   {"crash", &crash_},
    };
    for (const Spec& spec : specs) {
      auto stream = std::make_unique<LogStream>();
      stream->set_name(spec.name);
      if (stream->Open(session_dir / (std::string(spec.name) + ".log"))) {
        stream->WriteLine(header, header);
        *spec.target = stream.get();
        streams_.push_back(std::move(stream));
      }
    }
  }

  size_t stream_count() const { return streams_.size(); }

 protected:
  void sink_it_(const spdlog::details::log_msg& msg) override {
    const std::string category(msg.logger_name.begin(), msg.logger_name.end());
    const std::string text(msg.payload.begin(), msg.payload.end());
    const auto level_view = spdlog::level::to_string_view(msg.level);
    const std::string level(level_view.data(), level_view.size());
    const std::string line =
        "[" + FormatTime(msg.time) + "] [" + level + "] [" + category + "] " + text;
    // The dedup key excludes the timestamp: two identical messages a
    // millisecond apart must still collapse.
    const std::string key = level + "/" + category + "/" + text;

    const bool is_gpu = category == "gpu";
    const bool fmv = is_gpu && Contains(text, "fh1 fmv");
    const bool vulkan =
        is_gpu || Contains(text, "VulkanPresenter") || Contains(text, "Vulkan") ||
        Contains(text, "VkResult") || Contains(text, "vkQueue") ||
        Contains(text, "vkCmd") || Contains(text, "device lost") ||
        Contains(text, "breadcrumb");
    // The presenter's black-frame detector logs from the core category but
    // belongs with the FMV/GPU evidence.
    const bool clear_only_frame = Contains(text, "clear-only frame");
    const bool files = category == "fs" || Contains(text, "NtCreateFile") ||
                       Contains(text, "vfs open failed") ||
                       Contains(text, "colour grading map") ||
                       (category == "krnl" && Contains(text, "NtCreateFile"));
    const bool audio =
        category == "apu" || Contains(text, "XmaContext") || Contains(text, "XMA");
    const bool crash = Contains(text, "device lost") || Contains(text, "breadcrumb") ||
                       Contains(text, "fatal signal") || Contains(text, "Crash report");

    if (all_) {
      all_->WriteLine(key, line);
    }
    if (gpu_ && is_gpu) {
      gpu_->WriteLine(key, line);
    }
    if (vulkan_ && (vulkan || clear_only_frame)) {
      vulkan_->WriteLine(key, line);
    }
    if (fmv_ && (fmv || clear_only_frame)) {
      fmv_->WriteLine(key, line);
    }
    if (files_ && files) {
      files_->WriteLine(key, line);
    }
    if (audio_ && audio) {
      audio_->WriteLine(key, line);
    }
    if (crash_ && crash) {
      crash_->WriteLine(key, line);
    }
    EnforceBudget();
  }

  void flush_() override {
    for (const auto& stream : streams_) {
      stream->Flush();
    }
  }

 private:
  // Past the session budget only crash.log keeps writing, so a runaway loop
  // the dedup cannot collapse (many distinct messages) still cannot fill the
  // device, while the loss/crash evidence survives. The budget counts every
  // byte written since the session began, across rotations.
  void EnforceBudget() {
    if (budget_reached_) {
      return;
    }
    uint64_t total = 0;
    for (const auto& stream : streams_) {
      total += stream->session_total();
    }
    if (total <= kSessionBudgetBytes) {
      return;
    }
    budget_reached_ = true;
    for (const auto& stream : streams_) {
      if (stream.get() != crash_) {
        stream->CloseWithNote("--- session budget of " + std::to_string(kSessionBudgetBytes /
                                                                       (1024 * 1024)) +
                             " MB reached; this stream is closed, crash.log keeps writing ---");
      }
    }
    if (crash_) {
      crash_->WriteLine("budget", "--- session budget reached; only this stream keeps writing ---");
    }
  }

  static constexpr uint64_t kSessionBudgetBytes = 1024ull * 1024 * 1024;

  std::vector<std::unique_ptr<LogStream>> streams_;
  LogStream* all_ = nullptr;
  LogStream* gpu_ = nullptr;
  LogStream* vulkan_ = nullptr;
  LogStream* fmv_ = nullptr;
  LogStream* files_ = nullptr;
  LogStream* audio_ = nullptr;
  LogStream* crash_ = nullptr;
  bool budget_reached_ = false;
};

std::mutex g_session_mutex;
std::shared_ptr<RealtimeLogSink> g_session_sink;
std::filesystem::path g_session_dir;
std::string g_crash_log_path;

std::string LoadBuildSummary() {
  // The build manifest: the executable directory (Windows preview) or the
  // HOME the Android activity points at the internal files dir (the gradle
  // task stages the manifest there; nativeLibraryDir is covered by the
  // executable path). Only the identity fields are needed.
  std::vector<std::filesystem::path> candidates;
  const std::filesystem::path executable = platform::ExecutablePath();
  if (!executable.empty()) {
    candidates.push_back(executable.parent_path() / "pinyon_shift_build.json");
  }
  if (const char* home = std::getenv("HOME")) {
    candidates.push_back(std::filesystem::path(home) / "pinyon_shift_build.json");
  }
  for (const std::filesystem::path& candidate : candidates) {
    std::ifstream input(candidate, std::ios::binary);
    if (!input) {
      continue;
    }
    std::ostringstream contents;
    contents << input.rdbuf();
    const std::string json = contents.str();
    const std::regex commit("\"pinyon_shift_commit\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    const std::regex sdk("\"rexglue_commit\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    std::smatch match;
    std::string result = "commit ";
    result += std::regex_search(json, match, commit) ? match[1].str() : std::string("?");
    result += ", sdk ";
    result += std::regex_search(json, match, sdk) ? match[1].str() : std::string("?");
    return result;
  }
  return "unknown build";
}

void WriteConfigDump(const std::filesystem::path& session_dir, const char* title) {
  std::ofstream out(session_dir / "config_dump.txt",
                    std::ios::binary | std::ios::app);
  if (!out) {
    return;
  }
  out << "\n=== " << title << " at " << FormatTime(std::chrono::system_clock::now()) << " ===\n";
  out << "name = value | default | source | category\n\n";
  for (const rex::cvar::FlagEntry& entry : rex::cvar::GetRegistry()) {
    out << entry.name << " = " << entry.getter() << " | default " << entry.default_value
        << " | source " << uint32_t(entry.source) << " | " << entry.category << "\n";
  }
}

void ApplyLogLevel(const std::string& level) {
  // The instrumentation lines are emitted at INFO/WARN, so the level mainly
  // picks the extra detail channels; "full" also lowers the global log level
  // (the app sets the log_level cvar before InitLogging for that).
  if (level == "gpu" || level == "full") {
    rex::cvar::SetFlagByName("vulkan_frame_stats", "true");
    rex::cvar::SetFlagByName("vulkan_texture_log", "true");
  }
}

}  // namespace

bool InstallRealtimeLogSession() {
  std::lock_guard lock(g_session_mutex);
  if (g_session_sink) {
    return true;
  }
  const char* session = std::getenv("PINYON_SHIFT_LOG_SESSION");
  if (!session || !*session) {
    return false;
  }
  const std::string level = [] {
    const char* value = std::getenv("PINYON_SHIFT_LOG_LEVEL");
    return value ? std::string(value) : std::string("normal");
  }();

  const std::filesystem::path session_dir(session);
  std::error_code error;
  std::filesystem::create_directories(session_dir, error);

  const std::string header = "Pinyon Shift realtime log session | " + LoadBuildSummary() +
                             " | level " + level + " | started " +
                             FormatTime(std::chrono::system_clock::now());
  auto sink = std::make_shared<RealtimeLogSink>(session_dir, header);
  if (sink->stream_count() == 0) {
    return false;
  }
  rex::AddSink(sink);
  g_session_sink = sink;
  g_session_dir = session_dir;
  g_crash_log_path = (session_dir / "crash.log").string();

  WriteConfigDump(session_dir, "cvars at startup");
  ApplyLogLevel(level);
  // Opt-in resolve dumps for the open bug hypotheses (BUG-02 presentation
  // resolve sub-rect, BUG-08 shadow depth resolve, BUG-10.3 reflection cube -
  // session 20261009 investigation): set PINYON_SHIFT_LOG_RESOLVE_DUMP=<frame>
  // (swap count; 0 = every frame, very slow - it syncs with the GPU after each
  // resolve) to write every resolve's output bytes into the session's
  // resolves/ folder, which lands in the exported ZIP.
  if (const char* resolve_frame = std::getenv("PINYON_SHIFT_LOG_RESOLVE_DUMP")) {
    rex::cvar::SetFlagByName("fh1_resolve_dump_dir", (session_dir / "resolves").string());
    rex::cvar::SetFlagByName("fh1_resolve_dump_frame", resolve_frame);
    REXLOG_INFO("Realtime log: resolve dumps enabled in {} (frame filter {})",
                (session_dir / "resolves").string(), resolve_frame);
  }
  // The crash handlers are installed before the session exists; hand them
  // the crash.log path now (allocation-free copy; the handler only reads).
  crash::RefreshRealtimeCrashPath();

  REXLOG_INFO("Realtime log session installed in {} (level {})", session_dir.string(), level);
  return true;
}

void FlushRealtimeLogSession() {
  std::lock_guard lock(g_session_mutex);
  if (!g_session_sink) {
    return;
  }
  // A second config snapshot at shutdown: cvars registered later (mods) and
  // hot-reloaded values are part of the reproducible state.
  WriteConfigDump(g_session_dir, "cvars at shutdown");
  g_session_sink->flush();
}

const char* RealtimeCrashLogPath() {
  return g_crash_log_path.empty() ? nullptr : g_crash_log_path.c_str();
}

}  // namespace pinyon_shift::diagnostics
