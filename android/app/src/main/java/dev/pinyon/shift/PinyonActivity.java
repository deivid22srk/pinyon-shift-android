package dev.pinyon.shift;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

/**
 * Android host activity.
 *
 * SDL3's activity loads the native code as libmain.so and runs SDL_main (see
 * windowed_app_main_sdl.cpp in the ShiftGlue SDK). This subclass only wires
 * the device's storage locations into the process environment before the
 * game's main starts, and stages the project gamepad mapping database from
 * the APK assets.
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
        notifyIfGameDataMissing();
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
}
