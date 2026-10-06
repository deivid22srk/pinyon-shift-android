#pragma once

namespace pinyon_shift::diagnostics {

// Installs the realtime multiplexed log sink when the Android activity (or a
// desktop user, through the environment) requested one:
//
//   PINYON_SHIFT_LOG_SESSION  directory created by the activity, already
//                             holding device_info.txt and logcat.txt
//   PINYON_SHIFT_LOG_LEVEL    normal | gpu | full (picker screen)
//
// Files created in the session directory: all.log, gpu.log, vulkan.log,
// fmv.log, files.log, audio.log, crash.log and config_dump.txt. Every line is
// flushed as it is written, so a crash, a killed process or a hung GPU leaves
// the log complete up to the last event. Repeated identical messages are
// collapsed into a counter line, files rotate at 200 MB (keeping one .old
// generation) and the activity keeps only the newest five sessions.
//
// Returns true when the session sink is installed. When it returns false the
// runtime logging is untouched (logcat / runtime.log as before). Call
// FlushRealtimeLogSession() from OnShutdown; the sink also flushes on every
// line, so even a skipped flush only loses in-flight repeats.
bool InstallRealtimeLogSession();

// Flushes and closes the realtime session files, if any. Safe to call when no
// session is installed.
void FlushRealtimeLogSession();

// The crash reporter writes its fatal-signal report here as well when a
// realtime session is active (the reporter's own state-root copy stays).
// Returns an empty path when no session is installed.
const char* RealtimeCrashLogPath();

}  // namespace pinyon_shift::diagnostics
