#include "crash_reporter.h"
#include "pinyon_shift_realtime_log.h"

// The POSIX crash reporter: a handler for the fatal signals, on its own stack,
// writes <crash_root>/<session>-<signal>.txt with the signal, the faulting
// address, the program counter and a backtrace, then lets the default action
// end the process (and leave a core dump where the system keeps them). Only
// async-signal-safe calls run in the handler, except backtrace(), which glibc
// and macOS support there once it has been called before the fault.

#include <fcntl.h>
#include <signal.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <system_error>

#include <rex/logging.h>

#if defined(__GLIBC__) || defined(__APPLE__)
#include <execinfo.h>
#define PINYON_SHIFT_HAVE_BACKTRACE 1
#endif

namespace pinyon_shift::diagnostics::crash {
namespace {

// On Android the guest emulation NEEDS the hardware fault signals: the SDK's
// MMIO handler services write-watch faults for GPU memory coherence, and the
// SEH handlers turn guest __try into C++ exceptions. A sigaction handler is
// dispatched FIRST (bionic's signal chain runs the most recent registration
// before anything else), so claiming these signals here would kill the
// process on the first fault the emulator was about to handle — on Windows
// this reporter only ever sees faults nobody handled, because
// SetUnhandledExceptionFilter runs last. Keep that semantic by only claiming
// SIGABRT on Android; unhandled hardware faults still reach the system
// tombstone, which logcat records with the full backtrace.
#if defined(__ANDROID__)
constexpr int kSignals[] = {SIGABRT};
#else
constexpr int kSignals[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};
#endif
// "<crash_root>/<session>-" prepared at install; the handler appends the
// signal name, so it never allocates. The realtime session's crash.log
// (append mode, opened lazily in the handler) receives the same bytes.
std::array<char, 4096> g_report_prefix{};
size_t g_report_prefix_length = 0;
std::array<char, 4096> g_realtime_crash_path{};
// Fixed exit-reason file the launcher reads after the process dies
// (BUG-12 F3: the game used to return to the launcher with no context).
// Prepared at install, written from the handler without allocating.
std::array<char, 4096> g_exit_reason_path{};
std::array<char, 128> g_session_id{};
std::atomic_flag g_reported = ATOMIC_FLAG_INIT;
std::array<std::byte, 64 * 1024> g_signal_stack{};

const char* SignalName(int signal) {
  switch (signal) {
    case SIGSEGV:
      return "sigsegv";
    case SIGBUS:
      return "sigbus";
    case SIGILL:
      return "sigill";
    case SIGFPE:
      return "sigfpe";
    case SIGABRT:
      return "sigabrt";
    default:
      return "signal";
  }
}

void Write(int file, const char* text) { (void)!write(file, text, std::strlen(text)); }

// Appends "key=value\n" with a lowercase alphanumeric value (signal names,
// hex): byte-by-byte, allocation-free (async-signal-safe).
void WriteKeyValue(int file, const char* key, const char* value) {
  Write(file, key);
  Write(file, "=");
  for (const char* c = value; *c; ++c) {
    char buffer[2] = {*c, '\0'};
    Write(file, buffer);
  }
  Write(file, "\n");
}

void WriteHex(int file, const char* label, uint64_t value) {
  char buffer[40];
  size_t length = 0;
  for (const char* c = label; *c && length < 16; ++c) buffer[length++] = *c;
  buffer[length++] = '0';
  buffer[length++] = 'x';
  for (int shift = 60; shift >= 0; shift -= 4) {
    buffer[length++] = "0123456789ABCDEF"[(value >> shift) & 0xF];
  }
  buffer[length++] = '\n';
  (void)!write(file, buffer, length);
}

uint64_t ProgramCounter(const void* context) {
  const auto* user = static_cast<const ucontext_t*>(context);
  if (!user) return 0;
#if defined(__APPLE__) && defined(__x86_64__)
  return user->uc_mcontext->__ss.__rip;
#elif defined(__APPLE__) && defined(__aarch64__)
  return user->uc_mcontext->__ss.__pc;
#elif defined(__linux__) && defined(__x86_64__)
  return uint64_t(user->uc_mcontext.gregs[REG_RIP]);
#elif defined(__linux__) && defined(__aarch64__)
  return user->uc_mcontext.pc;
#else
  return 0;
#endif
}

void Handler(int signal, siginfo_t* info, void* context) {
  if (!g_reported.test_and_set()) {
    char path[4200];
    std::memcpy(path, g_report_prefix.data(), g_report_prefix_length);
    size_t length = g_report_prefix_length;
    for (const char* c = SignalName(signal); *c; ++c) path[length++] = *c;
    for (const char* c = ".txt"; *c; ++c) path[length++] = *c;
    path[length] = '\0';
    const int file = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    // The realtime session's crash.log: append (the log sink holds its own
    // descriptor on the same file; both append so the streams interleave
    // instead of overwriting). O_CLOEXEC keeps the fd out of child processes.
    const int realtime_file =
        g_realtime_crash_path[0] != '\0'
            ? open(g_realtime_crash_path.data(), O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC,
                   0644)
            : -1;
    const int files[2] = {file, realtime_file};
    for (const int out : files) {
      if (out < 0) {
        continue;
      }
      Write(out, "Pinyon Shift fatal signal\nsignal=");
      Write(out, SignalName(signal));
      Write(out, "\n");
      WriteHex(out, "fault_address=", uint64_t(uintptr_t(info ? info->si_addr : nullptr)));
      WriteHex(out, "pc=", ProgramCounter(context));
#if defined(PINYON_SHIFT_HAVE_BACKTRACE)
      void* frames[64];
      const int count = backtrace(frames, 64);
      backtrace_symbols_fd(frames, count, out);
#endif
      if (out != file) {
        close(out);
      }
    }
    if (file >= 0) {
      close(file);
    }
    // The exit-reason state file (BUG-12 F3): the launcher reads it on the
    // next start, so the player gets "the game stopped" context instead of a
    // silent return. Same content as the report header, one fixed file.
    if (g_exit_reason_path[0] != '\0') {
      const int reason_file =
          open(g_exit_reason_path.data(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
      if (reason_file >= 0) {
        Write(reason_file, "Pinyon Shift exit reason\n");
        WriteKeyValue(reason_file, "kind", "fatal_signal");
        WriteKeyValue(reason_file, "signal", SignalName(signal));
        char address[32];
        address[0] = '0';
        address[1] = 'x';
        for (int shift = 60, index = 2; shift >= 0; shift -= 4, ++index) {
          address[index] = "0123456789ABCDEF"[(uint64_t(uintptr_t(
                                  info ? info->si_addr : nullptr)) >>
                              shift) &
                             0xF];
        }
        address[18] = '\0';
        WriteKeyValue(reason_file, "fault_address", address);
        WriteKeyValue(reason_file, "session", g_session_id.data());
        close(reason_file);
      }
    }
  }
  // The default action ends the process as the signal would have.
  struct sigaction default_action {};
  default_action.sa_handler = SIG_DFL;
  sigemptyset(&default_action.sa_mask);
  sigaction(signal, &default_action, nullptr);
  raise(signal);
}

}  // namespace

// Reports from previous sessions hold the fault address and PC of crashes
// whose death was otherwise silent (the reporter writes the file and the
// process ends before anything logs). Surface them on the next boot: on a
// phone the session file is the only record, tombstones being unreadable
// without root. Replayed files are renamed so each crash is logged once.
void ReplayExistingReports(const std::filesystem::path& crash_root) {
  std::error_code error;
  std::filesystem::directory_iterator iterator(crash_root,
                                               std::filesystem::directory_options::skip_permission_denied,
                                               error);
  if (error) {
    return;
  }
  for (const auto& entry : iterator) {
    std::error_code entry_error;
    if (!entry.is_regular_file(entry_error) ||
        entry.path().extension() != ".txt") {
      continue;
    }
    std::ifstream input(entry.path(), std::ios::binary);
    if (!input) {
      continue;
    }
    std::ostringstream contents;
    contents << input.rdbuf();
    std::string text = contents.str();
    if (text.size() > 16 * 1024) {
      text.resize(16 * 1024);
    }
    REXLOG_WARN("Crash report from a previous session ({}):\n{}",
                   entry.path().filename().string(), text);
    std::filesystem::rename(entry.path(),
                            entry.path().string() + ".logged", entry_error);
  }
}

void Install(const std::filesystem::path& crash_root, const std::string& session_id) {
  const std::string prefix = (crash_root / session_id).string() + "-";
  g_report_prefix_length = std::min(prefix.size(), g_report_prefix.size() - 1);
  std::memcpy(g_report_prefix.data(), prefix.data(), g_report_prefix_length);
  // The launcher's fixed exit-reason file (read + deleted on the next start).
  {
    const std::string reason_path = (crash_root / "exit_reason.txt").string();
    const size_t reason_length = std::min(reason_path.size(), g_exit_reason_path.size() - 1);
    std::memcpy(g_exit_reason_path.data(), reason_path.data(), reason_length);
    g_exit_reason_path[reason_length] = '\0';
    const size_t session_length = std::min(session_id.size(), g_session_id.size() - 1);
    std::memcpy(g_session_id.data(), session_id.data(), session_length);
    g_session_id[session_length] = '\0';
  }
  // The realtime session (when the picker toggle is on) also wants the
  // report in its crash.log next to the breadcrumb dump; the path itself is
  // picked up by RefreshRealtimeCrashPath once the session exists.
  RefreshRealtimeCrashPath();
#if defined(PINYON_SHIFT_HAVE_BACKTRACE)
  // The first backtrace() loads the unwinder; do it now, not in the handler.
  void* frame;
  backtrace(&frame, 1);
#endif
  stack_t stack{};
  stack.ss_sp = g_signal_stack.data();
  stack.ss_size = g_signal_stack.size();
  sigaltstack(&stack, nullptr);
  ReplayExistingReports(crash_root);
  Refresh();
}

void RefreshRealtimeCrashPath() {
  // The realtime session installs after the handlers (it needs the logging
  // system up); copy its crash.log path once it exists. Allocation-free
  // copy: the signal handler only reads the buffer.
  g_realtime_crash_path.fill('\0');
  if (const char* realtime = pinyon_shift::diagnostics::RealtimeCrashLogPath()) {
    const size_t realtime_length =
        std::min(std::strlen(realtime), g_realtime_crash_path.size() - 1);
    std::memcpy(g_realtime_crash_path.data(), realtime, realtime_length);
  }
}

void Refresh() {
  struct sigaction action {};
  action.sa_sigaction = Handler;
  action.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&action.sa_mask);
  for (const int signal : kSignals) {
    sigaction(signal, &action, nullptr);
  }
}

void RaiseAccessViolation() { raise(SIGSEGV); }

void ExecuteNull() {
  volatile uintptr_t null_target = 0;
  reinterpret_cast<void (*)()>(null_target)();
}

}  // namespace pinyon_shift::diagnostics::crash
