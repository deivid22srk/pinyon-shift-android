/**
 ******************************************************************************
 * ShiftGlue / Pinyon Shift - virtual gamepad bridge (Android)
 ******************************************************************************
 * The on-screen virtual gamepad (VirtualGamepadOverlay.java) sends its touch
 * state through these JNI functions, which forward it to an SDL3 virtual
 * joystick. The virtual device is created with
 * SDL_JOYSTICK_TYPE_GAMEPAD, so SDL generates the standard gamepad mapping
 * for it (button N -> gamepad button N, axis 0..5 -> leftx/lefty/rightx/
 * righty/triggerleft/triggerright) and the game sees it exactly like a
 * physical Xbox 360 controller (vendor 0x045e / product 0x028e).
 *
 * Conventions the Java side must follow (they match the generated mapping):
 * - Buttons use the SDL_GamepadButton indices 0..15:
 *   0 A/SOUTH, 1 B/EAST, 2 X/WEST, 3 Y/NORTH, 4 BACK, 5 GUIDE, 6 START,
 *   7 LEFT_STICK, 8 RIGHT_STICK, 9 LB, 10 RB,
 *   11 DPAD_UP, 12 DPAD_DOWN, 13 DPAD_LEFT, 14 DPAD_RIGHT, 15 MISC1.
 * - Sticks send Sint16 -32768..32767 with 0 as the center.
 * - Triggers (axes 4 and 5) must idle at -32768: the gamepad layer maps the
 *   full joystick range onto the 0..32767 gamepad trigger range, so 0 would
 *   read as half-pressed (this is the same convention the Linux xpad driver
 *   uses). The attach function initializes them correctly.
 *
 * All entry points tolerate being called at any time, including before the
 * game runtime initialized SDL: the attach attempt simply fails and is
 * retried on the next call (the Java side also retries on a timer), so a
 * few early touches while the intro is still loading are dropped instead of
 * crashing. SDL_AttachVirtualJoystick / SDL_SetJoystickVirtual* are
 * documented thread-safe, and the calls arrive on Android's UI thread.
 */

#include <jni.h>

#include <SDL3/SDL_init.h>
#include <SDL3/SDL_joystick.h>

#include <atomic>

namespace {

// The instance id of the attached virtual joystick, 0 while detached.
std::atomic<SDL_JoystickID> g_virtual_pad_id{0};

// Set once a detach was requested by the user (overlay disabled): further
// lazy attaches stay off until an explicit attach call arrives.
std::atomic<bool> g_detach_requested{false};

// Virtual pad identity: Xbox 360 controller so games treat it as the
// reference gamepad type.
constexpr Uint16 kVendorId = 0x045e;
constexpr Uint16 kProductId = 0x028e;
constexpr int kAxisCount = 6;   // leftx, lefty, rightx, righty, trigL, trigR
constexpr int kButtonCount = 16; // SDL_GamepadButton 0..15

bool TryAttachVirtualGamepad() {
    SDL_JoystickID expected = 0;
    // Already attached (or another thread is attaching): nothing to do.
    if (!g_virtual_pad_id.compare_exchange_strong(expected, 0)) {
        return g_virtual_pad_id.load() != 0;
    }

    // The game initializes SDL itself; before that a virtual device cannot
    // exist. SDL_InitSubSystem on the gamepad subsystem also initializes the
    // joystick subsystem and is ref-counted, so this is harmless when the
    // runtime already (or later) initializes the same subsystems.
    if (!SDL_WasInit(SDL_INIT_GAMEPAD)) {
        if (!SDL_InitSubSystem(SDL_INIT_GAMEPAD)) {
            return false;
        }
    }

    SDL_VirtualJoystickDesc desc;
    SDL_INIT_INTERFACE(&desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.vendor_id = kVendorId;
    desc.product_id = kProductId;
    desc.naxes = kAxisCount;
    desc.nbuttons = kButtonCount;
    desc.button_mask = 0xFFFFu;               // SDL_GamepadButton 0..15
    desc.axis_mask = 0x3Fu;                   // leftx..triggerright
    desc.name = "Pinyon Virtual Gamepad";

    const SDL_JoystickID instance_id = SDL_AttachVirtualJoystick(&desc);
    if (instance_id == 0) {
        return false;
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
    return true;
}

SDL_Joystick* AttachedJoystick() {
    const SDL_JoystickID instance_id = g_virtual_pad_id.load();
    if (instance_id == 0) {
        return nullptr;
    }
    return SDL_GetJoystickFromID(instance_id);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadAttach(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    g_detach_requested.store(false);
    return TryAttachVirtualGamepad() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadDetach(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    g_detach_requested.store(true);
    const SDL_JoystickID instance_id = g_virtual_pad_id.exchange(0);
    if (instance_id != 0) {
        SDL_DetachVirtualJoystick(instance_id);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadAxis(
    JNIEnv* /*env*/, jclass /*clazz*/, jint axis, jshort value) {
    SDL_Joystick* joystick = AttachedJoystick();
    if (!joystick && !g_detach_requested.load() && TryAttachVirtualGamepad()) {
        joystick = AttachedJoystick();
    }
    if (!joystick || axis < 0 || axis >= kAxisCount) {
        return;  // SDL not up yet (or stale event after detach): drop it.
    }
    SDL_SetJoystickVirtualAxis(joystick, axis, static_cast<Sint16>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pinyon_shift_PinyonActivity_nativeVirtualGamepadButton(
    JNIEnv* /*env*/, jclass /*clazz*/, jint button, jboolean down) {
    SDL_Joystick* joystick = AttachedJoystick();
    if (!joystick && !g_detach_requested.load() && TryAttachVirtualGamepad()) {
        joystick = AttachedJoystick();
    }
    if (!joystick || button < 0 || button >= kButtonCount) {
        return;  // SDL not up yet (or stale event after detach): drop it.
    }
    SDL_SetJoystickVirtualButton(joystick, button, down == JNI_TRUE);
}
