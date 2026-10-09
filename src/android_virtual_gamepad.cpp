/**
 ******************************************************************************
 * ShiftGlue / Pinyon Shift - virtual gamepad bridge (Android)
 ******************************************************************************
 * The on-screen virtual gamepad (VirtualGamepadOverlay.java) sends its touch
 * state through these JNI functions, which forward it to an SDL3 virtual
 * joystick. The virtual device is created with SDL_JOYSTICK_TYPE_GAMEPAD, so
 * SDL generates the standard gamepad mapping for it (button N -> gamepad
 * button N, axis 0..5 -> leftx/lefty/rightx/righty/triggerleft/triggerright)
 * and the game sees it exactly like a physical Xbox 360 controller (vendor
 * 0x045e / product 0x028e).
 *
 * Conventions the Java side must follow (they match the generated mapping):
 * - Buttons use the SDL_GamepadButton indices 0..15:
 *   0 A/SOUTH, 1 B/EAST, 2 X/WEST, 3 Y/NORTH, 4 BACK, 5 GUIDE, 6 START,
 *   7 LEFT_STICK, 8 RIGHT_STICK, 9 LB, 10 RB,
 *   11 DPAD_UP, 12 DPAD_DOWN, 13 DPAD_LEFT, 14 DPAD_RIGHT, 15 MISC1.
 * - Sticks send Sint16 -32768..32767 with 0 as the center.
 * - Triggers (axes 4 and 5) must idle at -32768: the gamepad layer maps the
 *   full joystick range onto the 0..32767 gamepad trigger range, so 0 would
 *   read as half-pressed (same convention as the Linux xpad driver). Attach
 *   initializes them correctly.
 *
 * ---------------------------------------------------------------------------
 * WHY THE ATTACH IS WATCHDOG-DRIVEN (the original bug this file now guards
 * against): the game's input driver (SDLInputDriver) consumes SDL events
 * through an SDL_AddEventWatch callback registered inside OnWindowAvailable,
 * and it never polls the event queue. SDL_InitGamepads() runs on the FIRST
 * SDL_InitSubSystem(SDL_INIT_GAMEPAD) and posts SDL_EVENT_GAMEPAD_ADDED for
 * every joystick already attached at that moment - events posted BEFORE the
 * driver's watch registration are never delivered (nobody polls), and the
 * refcounted second init does not re-enumerate. An early attach therefore
 * produced a gamepad the game never opened, and SDL only translates joystick
 * axis/button activity into gamepad events for OPENED gamepads: the pad was
 * on screen but the game received nothing.
 *
 * The rule now: this bridge NEVER initializes the gamepad subsystem itself.
 * SDL_WasInit(SDL_INIT_GAMEPAD) only flips true when the input driver does
 * it, immediately after registering its event watch - so attaching at that
 * point (and only at that point) guarantees the SDL_EVENT_GAMEPAD_ADDED is
 * delivered and the game opens the pad. A low-frequency watchdog thread
 * performs the attach as soon as that window opens, verifies the game
 * actually opened the pad (SDL_GetGamepadFromID) and re-asserts (detach +
 * attach, bounded) if the added event was still missed. All of this is a few
 * calls per 500 ms: no measurable frame cost.
 *
 * Every step is logged under the "PinyonVGP" tag (visible in adb logcat and
 * captured by the realtime session logcat.txt) so a broken input chain can
 * be located from the logs alone.
 */

#include <jni.h>

#include <SDL3/SDL_init.h>
#include <SDL3/SDL_joystick.h>
#include <SDL3/SDL_gamepad.h>

#include <android/log.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace {

constexpr const char* kLogTag = "PinyonVGP";

#define VGP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define VGP_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)
#define VGP_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, kLogTag, __VA_ARGS__)

// Virtual pad identity: Xbox 360 controller so games treat it as the
// reference gamepad type.
constexpr Uint16 kVendorId = 0x045e;
constexpr Uint16 kProductId = 0x028e;
constexpr int kAxisCount = 6;     // leftx, lefty, rightx, righty, trigL, trigR
constexpr int kButtonCount = 16;  // SDL_GamepadButton 0..15
constexpr auto kWatchdogInterval = std::chrono::milliseconds(500);
// The game opens every SDL_EVENT_GAMEPAD_ADDED it receives; if the pad stays
// unopened this long after attaching, the added event was missed and a fresh
// detach+attach re-emits it.
constexpr int kUnopenedTicksBeforeReassert = 8;  // ~4 s
constexpr int kMaxReasserts = 5;

// Attach state. g_attach_state: 0 detached, 1 attached.
std::atomic<SDL_JoystickID> g_virtual_pad_id{0};
// Java-side enable (master switch / overlay visible). g_suppressed is set
// while a physical gamepad is connected or the feature is disabled: the
// watchdog stops and the pad is detached.
std::atomic<bool> g_enabled{false};
std::atomic<bool> g_suppressed{false};

std::mutex g_watchdog_mutex;
std::condition_variable g_watchdog_wake;
std::thread g_watchdog_thread;
bool g_watchdog_running = false;

void LogAttachFailure(const char* where) {
    VGP_LOGW("%s failed: %s", where, SDL_GetError());
}

// Attaches the virtual joystick. Caller must have verified
// SDL_WasInit(SDL_INIT_GAMEPAD); attaching earlier would steal the first
// gamepad init from the game's input driver (see the file header).
SDL_JoystickID TryAttachVirtualGamepad() {
    SDL_JoystickID expected = 0;
    if (!g_virtual_pad_id.compare_exchange_strong(expected, 0)) {
        return g_virtual_pad_id.load();  // already attached
    }
    if (!SDL_WasInit(SDL_INIT_GAMEPAD)) {
        return 0;  // wait for the game's input driver; do NOT init it here.
    }

    SDL_VirtualJoystickDesc desc;
    SDL_INIT_INTERFACE(&desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.vendor_id = kVendorId;
    desc.product_id = kProductId;
    desc.naxes = kAxisCount;
    desc.nbuttons = kButtonCount;
    desc.button_mask = 0xFFFFu;  // SDL_GamepadButton 0..15
    desc.axis_mask = 0x3Fu;      // leftx..triggerright
    desc.name = "Pinyon Virtual Gamepad";

    const SDL_JoystickID instance_id = SDL_AttachVirtualJoystick(&desc);
    if (instance_id == 0) {
        LogAttachFailure("SDL_AttachVirtualJoystick");
        return 0;
    }
    g_virtual_pad_id.store(instance_id);

    // Center the sticks and idle the triggers. Fresh joystick axes are 0,
    // which the gamepad layer would read as half-pressed triggers.
    SDL_Joystick* joystick = SDL_GetJoystickFromID(instance_id);
    if (joystick) {
        for (int axis = 0; axis < 4; ++axis) {
            SDL_SetJoystickVirtualAxis(joystick, axis, 0);
        }
        SDL_SetJoystickVirtualAxis(joystick, 4, SDL_JOYSTICK_AXIS_MIN);
        SDL_SetJoystickVirtualAxis(joystick, 5, SDL_JOYSTICK_AXIS_MIN);
    }
    VGP_LOGI("virtual gamepad attached (instance=%u)", instance_id);
    return instance_id;
}

void DetachVirtualGamepad(const char* reason) {
    const SDL_JoystickID instance_id = g_virtual_pad_id.exchange(0);
    if (instance_id != 0) {
        SDL_DetachVirtualJoystick(instance_id);
        VGP_LOGI("virtual gamepad detached (%s)", reason);
    }
}

SDL_Joystick* AttachedJoystick() {
    const SDL_JoystickID instance_id = g_virtual_pad_id.load();
    if (instance_id == 0) {
        return nullptr;
    }
    return SDL_GetJoystickFromID(instance_id);
}

// The watchdog: attaches the pad as soon as the game's input driver has
// initialized the gamepad subsystem (see file header), then watches that the
// game actually opened it. Re-asserts a bounded number of times if the added
// event was missed. ~3 API calls per tick; runs off the UI thread.
void WatchdogLoop() {
    int unopened_ticks = 0;
    int reasserts = 0;
    bool logged_waiting = false;

    for (;;) {
        std::unique_lock<std::mutex> lock(g_watchdog_mutex);
        g_watchdog_wake.wait_for(lock, kWatchdogInterval);
        if (!g_watchdog_running) {
            return;
        }
        lock.unlock();

        if (!g_enabled.load() || g_suppressed.load()) {
            continue;
        }

        if (!SDL_WasInit(SDL_INIT_GAMEPAD)) {
            if (!logged_waiting) {
                VGP_LOGD("waiting for the game's gamepad subsystem before attaching");
                logged_waiting = true;
            }
            continue;
        }
        if (logged_waiting) {
            VGP_LOGD("gamepad subsystem ready; attaching");
            logged_waiting = false;
        }

        if (g_virtual_pad_id.load() == 0) {
            if (TryAttachVirtualGamepad() != 0) {
                unopened_ticks = 0;
                reasserts = 0;
            }
            continue;
        }

        // Health check: the driver opens every added gamepad it receives.
        const SDL_JoystickID instance_id = g_virtual_pad_id.load();
        if (SDL_GetGamepadFromID(instance_id) == nullptr) {
            if (++unopened_ticks >= kUnopenedTicksBeforeReassert) {
                unopened_ticks = 0;
                if (reasserts < kMaxReasserts) {
                    ++reasserts;
                    VGP_LOGW("game never opened the virtual pad; re-asserting attach "
                             "(attempt %d/%d)",
                             reasserts, kMaxReasserts);
                    DetachVirtualGamepad("re-assert");
                    TryAttachVirtualGamepad();
                }
            }
        } else {
            unopened_ticks = 0;
            reasserts = 0;
        }
    }
}

void EnsureWatchdogStarted() {
    std::lock_guard<std::mutex> lock(g_watchdog_mutex);
    if (g_watchdog_running) {
        g_watchdog_wake.notify_all();
        return;
    }
    g_watchdog_running = true;
    g_watchdog_thread = std::thread(WatchdogLoop);
    g_watchdog_thread.detach();
    VGP_LOGI("virtual gamepad watchdog started");
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadAttach(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    // Signal: the overlay (or a reconnect) wants the pad. The watchdog does
    // the actual attach once the game's gamepad subsystem is up; attaching
    // here directly would only succeed after that point anyway.
    g_enabled.store(true);
    g_suppressed.store(false);
    EnsureWatchdogStarted();

    const SDL_JoystickID instance_id = g_virtual_pad_id.load();
    return instance_id != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadDetach(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    // Suppressed, not disabled: a physical gamepad connected (or the feature
    // was switched off). The watchdog stops touching SDL until the next
    // attach signal.
    g_suppressed.store(true);
    DetachVirtualGamepad("suppressed");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadAxis(
    JNIEnv* /*env*/, jclass /*clazz*/, jint axis, jshort value) {
    SDL_Joystick* joystick = AttachedJoystick();
    if (!joystick) {
        // Lazy safety net: the watchdog normally beats us to it; this covers
        // input that arrives before the first watchdog tick.
        if (g_enabled.load() && !g_suppressed.load()
                && SDL_WasInit(SDL_INIT_GAMEPAD)) {
            TryAttachVirtualGamepad();
            joystick = AttachedJoystick();
        }
        if (!joystick) {
            return;
        }
    }
    if (axis < 0 || axis >= kAxisCount) {
        return;
    }
    SDL_SetJoystickVirtualAxis(joystick, axis, static_cast<Sint16>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadButton(
    JNIEnv* /*env*/, jclass /*clazz*/, jint button, jboolean down) {
    SDL_Joystick* joystick = AttachedJoystick();
    if (!joystick) {
        if (g_enabled.load() && !g_suppressed.load()
                && SDL_WasInit(SDL_INIT_GAMEPAD)) {
            TryAttachVirtualGamepad();
            joystick = AttachedJoystick();
        }
        if (!joystick) {
            return;
        }
    }
    if (button < 0 || button >= kButtonCount) {
        return;
    }
    SDL_SetJoystickVirtualButton(joystick, button, down == JNI_TRUE);
}
