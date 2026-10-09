package dev.pinyon.shift;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

/**
 * Android host activity.
 *
 * SDL3's activity loads the native code as libmain.so and runs SDL_main (see
 * windowed_app_main_sdl.cpp in the ShiftGlue SDK). This subclass only wires
 * the device's storage locations into the process environment before the
 * game's main starts, stages the project gamepad mapping database from
 * the APK assets, and hosts the two in-game overlays:
 *
 * - the virtual gamepad (VirtualGamepadOverlay) with its floating
 *   show/hide button, hidden automatically while a physical gamepad is
 *   connected;
 * - the discreet REC badge shown while realtime log recording is on.
 *
 * Storage layout:
 * - Internal files dir (files/): state root (saves, caches, config, logs) and
 *   the extracted gamecontrollerdb.txt.
 * - Game content: wherever the player selected it on the picker screen
 *   (GamePickerActivity), read in place — nothing is copied. It arrives
 *   through the {@link GamePickerActivity#EXTRA_GAME_ROOT} intent extra, with
 *   the picker's saved selection as the fallback; when neither exists the
 *   legacy Android/data/dev.pinyon.shift/files/game/base location is used.
 */
public class PinyonActivity extends SDLActivity {
    private static final String TAG = "PinyonShift";

    /** The logcat PID capture of the current session, stopped on destroy. */
    private Process logcatCapture;

    /** The logcat crash-buffer capture: tombstone backtraces are written by
     *  the system's crash_dump process (a different PID), so the PID capture
     *  alone cannot be relied on to hold them. */
    private Process crashLogcatCapture;

    // Virtual gamepad overlay state.
    private VirtualGamepadOverlay gamepadOverlay;
    private View gamepadToggle;
    private TextView recBadge;
    private InputManager inputManager;
    private InputManager.InputDeviceListener inputDeviceListener;
    private boolean autoHiddenByPhysicalGamepad = false;
    /** Retries the native attach while the runtime is still booting SDL. */
    private int attachAttempts = 0;
    private boolean attachRetryScheduled = false;

    @Override
    protected String[] getLibraries() {
        // SDL3 is linked statically into libmain.so; only the host library
        // needs loading.
        return new String[]{
                "main"
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        copyAssetToFiles("gamecontrollerdb.txt");
        copyAssetToFiles("pinyon_shift_build.json");
        notifyIfGameDataMissing();
        setupRecBadge();
        setupVirtualGamepad();
        setupInputDeviceListener();
    }

    @Override
    public void loadLibraries() {
        // Loads libmain.so (SDL3 is static). After this the JNI bootstrap is
        // resolvable; the environment must be set before SDL_main runs.
        super.loadLibraries();
        java.io.File external = getExternalFilesDir(null);
        nativeSetEnvironment(
                getFilesDir() != null ? getFilesDir().getAbsolutePath() : null,
                external != null ? external.getAbsolutePath() : null,
                resolveGameRoot());
        // Custom Vulkan driver (libadrenotools, Turnip), selected on the
        // picker screen; empty name keeps the system driver.
        android.content.SharedPreferences prefs =
                getSharedPreferences(GamePickerActivity.PREFS_NAME, MODE_PRIVATE);
        nativeSetGpuDriver(
                GpuDrivers.driversRoot(this).getAbsolutePath(),
                prefs.getString(GamePickerActivity.PREF_GPU_DRIVER, ""),
                prefs.getBoolean(GamePickerActivity.PREF_GPU_TURBO, false));
        // Realtime logging, configured on the picker screen. The session
        // directory, device_info.txt and the logcat capture must exist before
        // SDL_main starts writing.
        setupRealtimeLogging(prefs);
    }

    /**
     * The game content location set on the picker screen: the intent extra
     * from a fresh start, then the picker's saved selection. Null leaves the
     * native default (game/base in this app's external files directory).
     */
    private String resolveGameRoot() {
        String fromIntent = getIntent() != null
                ? getIntent().getStringExtra(GamePickerActivity.EXTRA_GAME_ROOT)
                : null;
        if (fromIntent != null && !fromIntent.isEmpty()) {
            return fromIntent;
        }
        return getSharedPreferences(GamePickerActivity.PREFS_NAME, MODE_PRIVATE)
                .getString(GamePickerActivity.PREF_GAME_ROOT, null);
    }

    private void notifyIfGameDataMissing() {
        String gameRoot = resolveGameRoot();
        if (gameRoot != null) {
            // The player picked a location; the game reports problems through
            // its own startup logging if it cannot be read.
            return;
        }
        java.io.File external = getExternalFilesDir(null);
        if (external == null) {
            Log.w(TAG, "External storage unavailable; cannot locate game data");
            return;
        }
        java.io.File gameBase = new java.io.File(external, "game/base");
        if (!gameBase.isDirectory()) {
            Toast.makeText(this,
                    "No game selected. Pick your game folder on the next screen.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Creates the realtime log session and points the native runtime at it
     * before SDL_main starts: the session directory (with device_info.txt)
     * via LogSessions, the environment through the JNI call below, and the
     * logcat PID capture (native logs, the Turnip driver's messages, Android
     * system noise such as AdrenoUtils/GraphicBufferAllocator) into
     * logcat.txt. Rotated by logcat itself so it cannot fill storage.
     */
    private void setupRealtimeLogging(android.content.SharedPreferences prefs) {
        if (!prefs.getBoolean(LogSessions.PREF_REALTIME, false)) {
            return;
        }
        java.io.File session = LogSessions.createSessionDir(this);
        if (session == null) {
            Log.w(TAG, "Realtime logging is on but the session directory could not be created; "
                    + "logs stay in logcat only");
            return;
        }
        String level = prefs.getString(LogSessions.PREF_LEVEL, LogSessions.LEVEL_NORMAL);
        nativeSetLogSession(session.getAbsolutePath(), level);
        try {
            logcatCapture = Runtime.getRuntime().exec(new String[]{
                    "logcat",
                    "--pid=" + android.os.Process.myPid(),
                    "-v", "time",
                    "-f", new java.io.File(session, "logcat.txt").getAbsolutePath(),
                    "-r", "2048", "-n", "4"
            });
        } catch (Exception e) {
            Log.w(TAG, "Could not start the logcat capture", e);
            logcatCapture = null;
        }
        try {
            // The crash buffer only receives lines when something dies, so
            // this stays empty and tiny in a healthy session while guaranteeing
            // the tombstone backtrace is in the session even when the PID
            // filter misses it.
            crashLogcatCapture = Runtime.getRuntime().exec(new String[]{
                    "logcat",
                    "-b", "crash",
                    "-v", "time",
                    "-f", new java.io.File(session, "logcat_crash.txt").getAbsolutePath(),
                    "-r", "1024", "-n", "2"
            });
        } catch (Exception e) {
            Log.w(TAG, "Could not start the crash-buffer logcat capture", e);
            crashLogcatCapture = null;
        }
    }

    // --------------------------------------------------- virtual gamepad ui

    /**
     * Adds the virtual gamepad overlay and its floating toggle button on top
     * of the SDL surface (both inside android.R.id.content, so a touch that
     * misses every control falls through to the game). The toggle lives in
     * the top corner, half transparent; a long press opens the gamepad
     * settings. Nothing is created when the feature is disabled on the
     * picker screen.
     */
    private void setupVirtualGamepad() {
        if (!VirtualGamepadPrefs.isEnabled(this)) {
            Log.i(TAG, "Virtual gamepad disabled in settings; overlay not created");
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().post(() -> {
            ViewGroup content = findViewById(android.R.id.content);
            if (content == null) {
                return;
            }

            gamepadOverlay = new VirtualGamepadOverlay(this);
            gamepadOverlay.setListener(visible -> {
                Log.i(TAG, "Virtual gamepad overlay " + (visible ? "shown" : "hidden"));
                if (visible) {
                    retryAttach();
                }
            });
            content.addView(gamepadOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView toggleView = new ImageView(this);
            toggleView.setImageResource(R.drawable.ic_vgp_toggle);
            gamepadToggle = toggleView;
            gamepadToggle.setFocusable(true);
            gamepadToggle.setClickable(true);
            gamepadToggle.setAlpha(0.35f);
            gamepadToggle.setContentDescription(getString(R.string.vgp_toggle_desc));
            gamepadToggle.setOnClickListener(v -> {
                boolean show = !gamepadOverlay.isOverlayVisible();
                gamepadOverlay.setOverlayVisible(show, true);
                VirtualGamepadPrefs.setVisibleByDefault(this, show);
                autoHiddenByPhysicalGamepad = false;
            });
            gamepadToggle.setOnLongClickListener(v -> {
                VirtualGamepadDialogs.openSettings(this, gamepadOverlay);
                return true;
            });
            FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(
                    Math.round(46 * density), Math.round(46 * density));
            toggleParams.gravity = Gravity.TOP | Gravity.END;
            toggleParams.topMargin = Math.round(10 * density);
            toggleParams.rightMargin = Math.round(10 * density);
            gamepadToggle.setOnApplyWindowInsetsListener((view, insets) -> {
                toggleParams.topMargin = Math.round(10 * density)
                        + insets.getSystemWindowInsetTop();
                toggleParams.rightMargin = Math.round(10 * density)
                        + insets.getSystemWindowInsetRight();
                gamepadToggle.setLayoutParams(toggleParams);
                return insets;
            });
            content.addView(gamepadToggle, toggleParams);

            // Restore the last session's visible state through the same path
            // the toggle uses, so the internal flag and the attach retry stay
            // in sync (a shown overlay also schedules the attach retries for
            // the SDL boot window).
            gamepadOverlay.setOverlayVisible(
                    VirtualGamepadPrefs.isVisibleByDefault(this), false);
        });
    }

    /** Called from the settings dialog when the gamepad is enabled/disabled
     *  mid-game: rebuilds or removes the in-game UI for this session. */
    void onVirtualGamepadEnabledChanged() {
        if (VirtualGamepadPrefs.isEnabled(this) && gamepadOverlay == null) {
            Log.i(TAG, "Virtual gamepad enabled at runtime");
            setupVirtualGamepad();
            return;
        }
        if (!VirtualGamepadPrefs.isEnabled(this) && gamepadOverlay != null) {
            gamepadOverlay.setOverlayVisible(false, true);
            ViewGroup content = findViewById(android.R.id.content);
            if (content != null) {
                // Take both views off the hierarchy so a later re-enable in
                // this session rebuilds them from scratch.
                content.removeView(gamepadOverlay);
                if (gamepadToggle != null) {
                    content.removeView(gamepadToggle);
                }
            }
            gamepadOverlay = null;
            gamepadToggle = null;
            nativeVirtualGamepadDetach();
            Log.i(TAG, "Virtual gamepad disabled at runtime");
        }
    }

    /**
     * The native virtual device can only exist after the game initialized
     * SDL. The overlay's first input also retries lazily; this timer covers
     * the case where the user shows the overlay before touching anything.
     */
    private void retryAttach() {
        if (attachRetryScheduled || gamepadOverlay == null
                || !gamepadOverlay.isOverlayVisible()) {
            return;
        }
        attachRetryScheduled = true;
        gamepadOverlay.postDelayed(() -> {
            attachRetryScheduled = false;
            if (gamepadOverlay == null || !gamepadOverlay.isOverlayVisible()) {
                return;
            }
            if (!nativeVirtualGamepadAttach() && attachAttempts < 30) {
                attachAttempts++;
                retryAttach();
            }
        }, 500L);
    }

    /** A physical gamepad took over: tuck the overlay away (manual re-show
     *  through the floating button stays possible). */
    private void onPhysicalGamepadConnected(InputDevice device) {
        Log.i(TAG, "Physical gamepad connected: " + device.getName());
        if (gamepadOverlay != null && gamepadOverlay.isOverlayVisible()
                && !gamepadOverlay.isEditMode()) {
            autoHiddenByPhysicalGamepad = true;
            gamepadOverlay.setOverlayVisible(false, true);
            Log.i(TAG, "Virtual gamepad auto-hidden for the physical controller");
        }
    }

    /** The physical gamepad is gone: restore the overlay if we hid it. */
    private void onPhysicalGamepadDisconnected(InputDevice device) {
        Log.i(TAG, "Physical gamepad disconnected"
                + (device != null ? ": " + device.getName() : ""));
        if (autoHiddenByPhysicalGamepad && gamepadOverlay != null
                && VirtualGamepadPrefs.isEnabled(this)) {
            autoHiddenByPhysicalGamepad = false;
            gamepadOverlay.setOverlayVisible(true, true);
            retryAttach();
        }
    }

    private static boolean isRealGamepad(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void setupInputDeviceListener() {
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) {
            return;
        }
        inputDeviceListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (isRealGamepad(device)) {
                    onPhysicalGamepadConnected(device);
                }
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                // The device object is already gone; treat every removal as
                // a possible gamepad departure.
                onPhysicalGamepadDisconnected(null);
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
            }
        };
        inputManager.registerInputDeviceListener(inputDeviceListener, null);
    }

    // ------------------------------------------------------------ rec badge

    /** The discreet "REC" indicator, shown while realtime logging is on. */
    private void setupRecBadge() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(GamePickerActivity.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(LogSessions.PREF_REALTIME, false)) {
            return;
        }
        getWindow().getDecorView().post(() -> {
            ViewGroup content = findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            float density = getResources().getDisplayMetrics().density;
            recBadge = new TextView(this);
            recBadge.setText(R.string.logs_rec_badge);
            recBadge.setTextSize(10f);
            recBadge.setTextColor(0xFFFF4530);
            recBadge.setAlpha(0.65f);
            recBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            recBadge.setLetterSpacing(0.2f);
            recBadge.setPadding(Math.round(8 * density), Math.round(3 * density),
                    Math.round(8 * density), Math.round(3 * density));
            recBadge.setBackgroundResource(R.drawable.forza_rec_badge);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.topMargin = Math.round(10 * density);
            params.leftMargin = Math.round(10 * density);
            recBadge.setOnApplyWindowInsetsListener((view, insets) -> {
                params.topMargin = Math.round(10 * density)
                        + insets.getSystemWindowInsetTop();
                params.leftMargin = Math.round(10 * density)
                        + insets.getSystemWindowInsetLeft();
                recBadge.setLayoutParams(params);
                return insets;
            });
            content.addView(recBadge, params);
        });
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onPause() {
        // A pause can interrupt a gesture mid-hold (home button, overlay
        // notification): release the virtual controls so the game never
        // keeps a stick or a trigger stuck.
        if (gamepadOverlay != null) {
            gamepadOverlay.releaseEverything();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null && inputDeviceListener != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
            inputDeviceListener = null;
        }
        if (gamepadOverlay != null) {
            gamepadOverlay.releaseEverything();
        }
        nativeVirtualGamepadDetach();
        if (logcatCapture != null) {
            logcatCapture.destroy();
            logcatCapture = null;
        }
        if (crashLogcatCapture != null) {
            crashLogcatCapture.destroy();
            crashLogcatCapture = null;
        }
        super.onDestroy();
    }

    private boolean copyAssetToFiles(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.File target = new java.io.File(getFilesDir(), name);
            try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not stage asset " + name + ": " + e);
            return false;
        }
    }

    static native void nativeSetEnvironment(String internalFilesDir, String externalFilesDir,
            String gameRoot);

    static native void nativeSetGpuDriver(String driversDir, String driverName, boolean turbo);

    static native void nativeSetLogSession(String sessionDir, String level);

    /** Virtual gamepad bridge (android_virtual_gamepad.cpp). Attach returns
     *  false while SDL is not initialized yet; retry on a timer or on the
     *  next input event. */
    static native boolean nativeVirtualGamepadAttach();

    static native void nativeVirtualGamepadDetach();

    /** axis: 0..5 = leftx, lefty, rightx, righty, triggerL, triggerR.
     *  Sticks: -32768..32767, 0 center. Triggers: idle -32768, full 32767. */
    static native void nativeVirtualGamepadAxis(int axis, short value);

    /** button: SDL_GamepadButton 0..15 (A, B, X, Y, back, guide, start,
     *  L3, R3, LB, RB, dpad up/down/left/right, misc1). */
    static native void nativeVirtualGamepadButton(int button, boolean down);
}
