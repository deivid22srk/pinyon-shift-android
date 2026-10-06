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
// line so a stuck loop cannot fill storage or drown distinct errors, and the
// file rotates at 200 MB keeping one .old generation. Append mode throughout:
// the crash reporter writes crash.log through its own raw descriptor from the
// signal handler, and independent descriptors must not fight over offsets.
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

  void WriteLine(const std::string& line) {
    if (!file_) {
      return;
    }
    if (line == pending_line_) {
      ++pending_repeats_;
      // The repeated line itself is not written until a different message (or
      // a flush) closes the run; its first occurrence is already on disk, so
      // nothing distinct is ever lost.
      return;
    }
    FlushRepeats();
    pending_line_ = line;
    WriteRaw(line);
  }

  void FlushRepeats() {
    if (pending_repeats_ > 0 && file_) {
      const std::string note = "... repeated " + std::to_string(pending_repeats_) + "x more";
      std::fputs(note.c_str(), file_);
      std::fputc('\n', file_);
      std::fflush(file_);
      written_ += note.size() + 1;
      pending_repeats_ = 0;
    }
  }

  void Flush() {
    FlushRepeats();
    if (file_) {
      std::fflush(file_);
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
    std::filesystem::rename(path_, old, error);
    file_ = std::fopen(path_.string().c_str(), "a");
    written_ = 0;
    if (file_) {
      const std::string note = "--- rotated; previous generation in " + old.filename().string() +
                               " ---";
      std::fputs(note.c_str(), file_);
      std::fputc('\n', file_);
      std::fflush(file_);
    }
  }

  static constexpr uint64_t kMaxFileBytes = 200ull * 1024 * 1024;

  std::filesystem::path path_;
  std::string name_;
  std::FILE* file_ = nullptr;
  uint64_t written_ = 0;
  std::string pending_line_;
  uint64_t pending_repeats_ = 0;
};

std::string Timestamp() {
  const auto now = std::chrono::system_clock::now();
  const std::time_t time = std::chrono::system_clock::to_time_t(now);
  const auto milliseconds =
      std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() %
      1000;
  std::tm utc{};
#if defined(_WIN32)
  gmtime_s(&utc, &time);
#else
  gmtime_r(&time, &utc);
#endif
  std::ostringstream stream;
  stream << std::put_time(&utc, "%Y-%m-%d %H:%M:%S") << '.' << std::setw(3) << std::setfill('0')
         << milliseconds;
  return stream.str();
}

bool Contains(std::string_view text, const char* needle) {
  return text.find(needle) != std::string_view::npos;
}

// The multiplexed sink attached to every logger (rex::AddSink adds it to all
// current and future categories). Each message is formatted once and routed
// by category and stable message prefixes (the "fh1 fmv" convention, the
// driver's "Vulkan ..." debug-utils lines, the breadcrumb dump); all.log
// always receives everything.
class RealtimeLogSink : public spdlog::sinks::base_sink<std::mutex> {
 public:
  RealtimeLogSink(const std::filesystem::path& session_dir, const std::string& header) {
    struct Spec {
      const char* name;
      LogStream** target;
    };
    Spec specs[] = {
        {"all", &all_},   {"gpu", &gpu_},   {"vulkan", &vulkan_}, {"fmv", &fmv_},
        {"files", &files_}, {"audio", &audio_}, {"crash", &crash_},
    };
    for (const Spec& spec : specs) {
      auto stream = std::make_unique<LogStream>();
      stream->set_name(spec.name);
      if (stream->Open(session_dir / (std::string(spec.name) + ".log"))) {
        stream->WriteLine(header);
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
        "[" + Timestamp() + "] [" + level + "] [" + category + "] " + text;

    const bool is_gpu = category == "gpu";
    const bool fmv = is_gpu && Contains(text, "fh1 fmv");
    const bool vulkan = is_gpu && (Contains(text, "Vulkan") || Contains(text, "VkResult") ||
                                   Contains(text, "vkQueue") || Contains(text, "vkCmd") ||
                                   Contains(text, "device lost") || Contains(text, "breadcrumb"));
    const bool files = category == "fs" || Contains(text, "NtCreateFile") ||
                       Contains(text, "vfs open failed") ||
                       (category == "krnl" && Contains(text, "NtCreateFile"));
    const bool audio =
        category == "apu" || Contains(text, "XmaContext") || Contains(text, "XMA");
    const bool crash = Contains(text, "device lost") || Contains(text, "breadcrumb") ||
                       Contains(text, "fatal signal") || Contains(text, "Crash report");

    if (all_) {
      all_->WriteLine(line);
    }
    if (gpu_ && is_gpu) {
      gpu_->WriteLine(line);
    }
    if (vulkan_ && vulkan) {
      vulkan_->WriteLine(line);
    }
    if (fmv_ && fmv) {
      fmv_->WriteLine(line);
    }
    if (files_ && files) {
      files_->WriteLine(line);
    }
    if (audio_ && audio) {
      audio_->WriteLine(line);
    }
    if (crash_ && crash) {
      crash_->WriteLine(line);
    }
  }

  void flush_() override {
    for (const auto& stream : streams_) {
      stream->Flush();
    }
  }

 private:
  std::vector<std::unique_ptr<LogStream>> streams_;
  LogStream* all_ = nullptr;
  LogStream* gpu_ = nullptr;
  LogStream* vulkan_ = nullptr;
  LogStream* fmv_ = nullptr;
  LogStream* files_ = nullptr;
  LogStream* audio_ = nullptr;
  LogStream* crash_ = nullptr;
};

std::mutex g_session_mutex;
std::shared_ptr<RealtimeLogSink> g_session_sink;
std::string g_crash_log_path;

std::string LoadBuildSummary() {
  // The build manifest the diagnostics module also reads: executable
  // directory / pinyon_shift_build.json. Only the identity fields are needed.
  const std::filesystem::path executable = platform::ExecutablePath();
  if (executable.empty()) {
    return "unknown build";
  }
  std::ifstream input(executable.parent_path() / "pinyon_shift_build.json", std::ios::binary);
  if (!input) {
    return "unknown build";
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

void WriteConfigDump(const std::filesystem::path& session_dir) {
  std::ofstream out(session_dir / "config_dump.txt", std::ios::binary);
  if (!out) {
    return;
  }
  out << "Pinyon Shift cvar dump at " << Timestamp() << "\n";
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
                             " | level " + level + " | started " + Timestamp();
  auto sink = std::make_shared<RealtimeLogSink>(session_dir, header);
  if (sink->stream_count() == 0) {
    return false;
  }
  rex::AddSink(sink);
  g_session_sink = sink;
  g_crash_log_path = (session_dir / "crash.log").string();

  WriteConfigDump(session_dir);
  ApplyLogLevel(level);
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
  g_session_sink->flush();
}

const char* RealtimeCrashLogPath() {
  return g_crash_log_path.empty() ? nullptr : g_crash_log_path.c_str();
}

}  // namespace pinyon_shift::diagnostics
