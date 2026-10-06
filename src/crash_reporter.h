#pragma once

// The process crash reporter behind the diagnostics interface (NP-12.2):
// Windows writes a minidump and a symbolized text report, POSIX hosts a text
// report with the signal, faulting address, registers and backtrace. Reports
// go to <crash_root>/<session>-*.

#include <filesystem>
#include <string>

namespace pinyon_shift::diagnostics::crash {

// Installs the handlers; later crashes in any thread are reported.
void Install(const std::filesystem::path& crash_root, const std::string& session_id);

// Re-reads the realtime session's crash.log path (empty until the realtime
// log session is installed, which happens after the handlers). POSIX only;
// the Windows reporter is a no-op here.
void RefreshRealtimeCrashPath();

// Installs the unhandled-crash handler again, for runtime components that
// replace it after startup.
void Refresh();

// Crash self-tests (PINYON_SHIFT_CRASH_SELF_TEST and
// PINYON_SHIFT_EXECUTE_CRASH_SELF_TEST): an access violation, and a call
// through a null function pointer. Neither returns when the reporter works.
void RaiseAccessViolation();
void ExecuteNull();

}  // namespace pinyon_shift::diagnostics::crash
