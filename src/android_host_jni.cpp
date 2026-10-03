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
 * - PINYON_SHIFT_GAME_ROOT: the extracted game content. The player selects
 *   the folder on the picker screen (XenDroid-style SAF pick) and it is read
 *   in place — no copy. The location arrives as the game_root argument; when
 *   the player never used the picker, the legacy
 *   Android/data/dev.pinyon.shift/files/game/base location is used.
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
    jstring external_files_dir, jstring game_root) {
  const std::string internal = JniToString(env, internal_files_dir);
  const std::string external = JniToString(env, external_files_dir);
  const std::string selected_game_root = JniToString(env, game_root);

  if (!internal.empty()) {
    setenv("HOME", internal.c_str(), 0);
    setenv("PINYON_SHIFT_STATE_ROOT", (internal + "/state").c_str(), 1);
    // The activity copies the project controller mappings from the APK
    // assets next to this path before calling into this function.
    setenv("REX_HID_MAPPINGS_FILE", (internal + "/gamecontrollerdb.txt").c_str(),
           0);
  }
  if (!selected_game_root.empty()) {
    // The picker screen's selection: the game content stays where the player
    // put it and is read in place.
    setenv("PINYON_SHIFT_GAME_ROOT", selected_game_root.c_str(), 1);
  } else if (!external.empty()) {
    setenv("PINYON_SHIFT_GAME_ROOT", (external + "/game/base").c_str(), 1);
  }
}

// The custom Vulkan driver (Mesa Turnip and friends) picked on the picker
// screen, loaded by the runtime through libadrenotools:
// - REX_ANDROID_DRIVERS_DIR: root folder holding one folder per driver
//   package (meta.json + the .so files).
// - REX_ANDROID_GPU_DRIVER: the package folder name to load; empty/unset
//   keeps the system driver.
// - REX_ANDROID_GPU_TURBO: adrenotools_set_turbo while the game is shown.
// Must run before SDL_main: the Vulkan instance is created early on.
extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeSetGpuDriver(
    JNIEnv* env, jclass /*clazz*/, jstring drivers_dir, jstring driver_name,
    jboolean turbo) {
  const std::string dir = JniToString(env, drivers_dir);
  const std::string name = JniToString(env, driver_name);

  if (!dir.empty()) {
    setenv("REX_ANDROID_DRIVERS_DIR", dir.c_str(), 1);
  }
  if (!name.empty()) {
    setenv("REX_ANDROID_GPU_DRIVER", name.c_str(), 1);
  } else {
    unsetenv("REX_ANDROID_GPU_DRIVER");
  }
  setenv("REX_ANDROID_GPU_TURBO", turbo == JNI_TRUE ? "true" : "false", 1);
}
