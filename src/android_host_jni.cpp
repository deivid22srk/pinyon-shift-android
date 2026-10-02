/**
 ******************************************************************************
 * ShiftGlue / Pinyon Shift - Android JNI bootstrap
 ******************************************************************************
 * PinyonActivity (extends SDLActivity) calls nativeSetEnvironment from
 * onCreate, after the SDL activity has loaded libmain.so and before SDL starts
 * the thread that runs SDL_main. The environment variables set here are read
 * during startup by rex::cvar::ApplyEnvironment and
 * PinyonShiftApp::OnConfigurePaths:
 *
 * - PINYON_SHIFT_STATE_ROOT: saves, caches, config and logs. Internal
 *   storage, writable without permissions.
 * - PINYON_SHIFT_GAME_ROOT: the extracted game content. The player copies the
 *   files extracted from their disc to
 *   Android/data/dev.pinyon.shift/files/game/base over USB or a file manager;
 *   app-specific external storage is readable by the app without permissions.
 * - HOME: the SDK entry point derives user folders from it when the runtime
 *   needs a home (set only if not already present).
 * - REX_HID_MAPPINGS_FILE: the project gamepad mapping database, copied out of
 *   the APK assets by the activity (nativeLibraryDir itself is read-only).
 */

#include <jni.h>

#include <cstdlib>
#include <string>

namespace {

std::string JniToString(JNIEnv* env, jstring value) {
  if (!value) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars ? chars : "");
  if (chars) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return result;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeSetEnvironment(
    JNIEnv* env, jclass /*clazz*/, jstring internal_files_dir,
    jstring external_files_dir) {
  const std::string internal = JniToString(env, internal_files_dir);
  const std::string external = JniToString(env, external_files_dir);

  if (!internal.empty()) {
    setenv("HOME", internal.c_str(), 0);
    setenv("PINYON_SHIFT_STATE_ROOT", (internal + "/state").c_str(), 1);
    // The activity copies the project controller mappings from the APK
    // assets next to this path before calling into this function.
    setenv("REX_HID_MAPPINGS_FILE", (internal + "/gamecontrollerdb.txt").c_str(),
           0);
  }
  if (!external.empty()) {
    setenv("PINYON_SHIFT_GAME_ROOT", (external + "/game/base").c_str(), 1);
  }
}
