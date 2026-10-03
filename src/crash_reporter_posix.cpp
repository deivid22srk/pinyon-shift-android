#include "crash_reporter.h"

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
// signal name, so it never allocates.
std::array<char, 4096> g_report_prefix{};
size_t g_report_prefix_length = 0;
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
    if (file >= 0) {
      Write(file, "Pinyon Shift fatal signal\nsignal=");
      Write(file, SignalName(signal));
      Write(file, "\n");
      WriteHex(file, "fault_address=", uint64_t(uintptr_t(info ? info->si_addr : nullptr)));
      WriteHex(file, "pc=", ProgramCounter(context));
#if defined(PINYON_SHIFT_HAVE_BACKTRACE)
      void* frames[64];
      const int count = backtrace(frames, 64);
      backtrace_symbols_fd(frames, count, file);
#endif
      close(file);
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
