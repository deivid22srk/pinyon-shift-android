package dev.pinyon.shift;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persistent settings of the virtual gamepad, all in the shared
 * "pinyon_game" preferences file (the same one the picker screen uses) with
 * a "vgp_" prefix:
 *
 * - enabled:    master switch (picker screen). When off, the game activity
 *               creates neither the overlay nor the floating toggle button.
 * - visible:    last shown/hidden state of the overlay, restored on the next
 *               game start (the floating button also flips this).
 * - opacity:    overlay opacity 0.20..1.00.
 * - scale:      control size 0.60..1.60 (multiplies the base radii).
 * - deadzone:   analog stick dead zone 0.00..0.35 of the stick radius.
 * - haptics:    light haptic feedback on button presses.
 * - layout:     JSON of dragged control positions ("control id" -> [xFrac,
 *               yFrac] of the overlay size). Controls without an entry keep
 *               their built-in default spot, so a layout saved on one aspect
 *               ratio still lands sanely on another.
 */
final class VirtualGamepadPrefs {
    private static final String PREFIX = "vgp_";

    static final float OPACITY_DEFAULT = 0.68f;
    static final float SCALE_DEFAULT = 1.0f;
    static final float DEADZONE_DEFAULT = 0.12f;

    private VirtualGamepadPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(GamePickerActivity.PREFS_NAME,
                Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREFIX + "enabled", true);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREFIX + "enabled", enabled).apply();
    }

    static boolean isVisibleByDefault(Context context) {
        return prefs(context).getBoolean(PREFIX + "visible", true);
    }

    static void setVisibleByDefault(Context context, boolean visible) {
        prefs(context).edit().putBoolean(PREFIX + "visible", visible).apply();
    }

    static float getOpacity(Context context) {
        return clamp(prefs(context).getFloat(PREFIX + "opacity", OPACITY_DEFAULT),
                0.20f, 1.0f);
    }

    static void setOpacity(Context context, float value) {
        prefs(context).edit().putFloat(PREFIX + "opacity", clamp(value, 0.20f, 1.0f)).apply();
    }

    static float getScale(Context context) {
        return clamp(prefs(context).getFloat(PREFIX + "scale", SCALE_DEFAULT),
                0.60f, 1.60f);
    }

    static void setScale(Context context, float value) {
        prefs(context).edit().putFloat(PREFIX + "scale", clamp(value, 0.60f, 1.60f)).apply();
    }

    static float getDeadzone(Context context) {
        return clamp(prefs(context).getFloat(PREFIX + "deadzone", DEADZONE_DEFAULT),
                0.0f, 0.35f);
    }

    static void setDeadzone(Context context, float value) {
        prefs(context).edit().putFloat(PREFIX + "deadzone", clamp(value, 0.0f, 0.35f)).apply();
    }

    static boolean getHaptics(Context context) {
        return prefs(context).getBoolean(PREFIX + "haptics", true);
    }

    static void setHaptics(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREFIX + "haptics", enabled).apply();
    }

    /** The saved control positions, or an empty object when none were moved. */
    static JSONObject getLayout(Context context) {
        String raw = prefs(context).getString(PREFIX + "layout", null);
        if (raw == null || raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void saveLayout(Context context, JSONObject layout) {
        prefs(context).edit().putString(PREFIX + "layout", layout.toString()).apply();
    }

    static void resetLayout(Context context) {
        prefs(context).edit().remove(PREFIX + "layout").apply();
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
