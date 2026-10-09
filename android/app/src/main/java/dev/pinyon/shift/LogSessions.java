package dev.pinyon.shift;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Realtime log sessions ("Salvar logs em tempo real" on the picker screen).
 *
 * When the toggle is on, every game start creates one session directory under
 * the log root and the whole runtime writes there in real time (flush per
 * line): all.log plus the per-channel files from the native sink, logcat.txt
 * from the PID capture started by PinyonActivity, and device_info.txt written
 * here. The native side receives the session directory and the level through
 * PINYON_SHIFT_LOG_SESSION / PINYON_SHIFT_LOG_LEVEL before SDL_main starts.
 *
 * Log root: /storage/emulated/0/forza with All Files Access granted, else the
 * app's own external files dir (Android/data/<pkg>/files/forza_logs) which
 * needs no permission - the picker always shows which one is in effect. Old
 * sessions beyond the newest three are removed, each stream rotates at
 * 200 MB (one .old generation) and the native side closes the streams at a
 * whole-session budget, so logging cannot grow unbounded.
 */
public final class LogSessions {
    private static final String TAG = "PinyonShiftLogs";

    /** Preference keys, in the shared GamePickerActivity preferences file. */
    public static final String PREF_REALTIME = "logs_realtime";
    public static final String PREF_LEVEL = "logs_level";

    public static final String LEVEL_NORMAL = "normal";
    public static final String LEVEL_GPU = "gpu";
    public static final String LEVEL_FULL = "full";

    private static final String SESSION_PREFIX = "session_";

    /** Length of the "session_" prefix (session name -> timestamp substring). */
    public static int sessionPrefixLength() {
        return SESSION_PREFIX.length();
    }
    /** Sessions kept on device; older ones are deleted when a new one starts.
     *  Three, not five: each session can reach the multi-GB range before the
     *  native session budget closes the streams, and storage on a phone is
     *  shared with the game itself. */
    private static final int KEEP_SESSIONS = 3;

    private LogSessions() {
    }

    /** True when logs go to shared storage (/storage/emulated/0/forza). */
    public static boolean usingSharedStorage(Context context) {
        return Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
    }

    /**
     * The log root: the shared "forza" folder with All Files Access, otherwise
     * the app-specific external dir which is always writable. Never null; the
     * caller reports the chosen location to the player.
     */
    public static File logRoot(Context context) {
        if (usingSharedStorage(context)) {
            File shared = android.os.Environment.getExternalStorageDirectory();
            if (shared != null) {
                return new File(shared, "forza");
            }
        }
        File fallback = context.getExternalFilesDir(null);
        if (fallback == null) {
            fallback = context.getFilesDir();
        }
        return new File(fallback, "forza_logs");
    }

    /**
     * Creates the session directory for this game start (session_yyyyMMdd_HHMMSS
     * under the log root), writes device_info.txt, prunes old sessions and
     * returns it. Returns null when the directory cannot be created (storage
     * missing, permission revoked); the caller falls back to no realtime logs.
     */
    public static File createSessionDir(Context context) {
        File root = logRoot(context);
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "Could not create the log root " + root);
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File session = new File(root, SESSION_PREFIX + stamp);
        for (int attempt = 0; attempt < 5; ++attempt) {
            if (session.mkdir()) {
                writeDeviceInfo(context, session);
                pruneOldSessions(root);
                return session;
            }
            // Same-second start after a crash: make the name unique.
            session = new File(root, SESSION_PREFIX + stamp + "_" + attempt);
        }
        Log.w(TAG, "Could not create a session directory under " + root);
        return null;
    }

    /** The newest session directory, or null when none exists yet. */
    public static File latestSessionDir(Context context) {
        File root = logRoot(context);
        File[] sessions = root.listFiles((dir, name) -> name.startsWith(SESSION_PREFIX));
        if (sessions == null || sessions.length == 0) {
            return null;
        }
        List<File> ordered = new ArrayList<>(Arrays.asList(sessions));
        Collections.sort(ordered, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return ordered.get(ordered.size() - 1);
    }

    /**
     * All recorded sessions under the log root, newest first. Directories
     * only; stray files in the root are ignored.
     */
    public static List<File> listSessions(Context context) {
        File root = logRoot(context);
        File[] sessions = root.listFiles((dir, name) -> name.startsWith(SESSION_PREFIX));
        List<File> ordered = new ArrayList<>();
        if (sessions != null) {
            ordered.addAll(Arrays.asList(sessions));
            Collections.sort(ordered, (a, b) -> b.getName().compareToIgnoreCase(a.getName()));
        }
        return ordered;
    }

    /** Total bytes of every file in the directory tree (0 when missing). */
    public static long directoryBytes(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                total += directoryBytes(child);
            } else if (child.isFile()) {
                total += child.length();
            }
        }
        return total;
    }

    /** The number of live (non-rotated) files in a session directory. */
    public static int sessionFileCount(File session) {
        File[] children = session.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (child.isFile() && !child.getName().endsWith(".old")) {
                count++;
            }
        }
        return count;
    }

    /** Deletes one session directory (with confirmation handled by the UI). */
    public static void deleteSession(File session) {
        deleteTree(session);
    }

    /**
     * Zips the newest session into the app cache and returns the zip, ready to
     * share through the FileProvider. Each zip gets a unique name (millisecond
     * suffix) so concurrent or repeated shares never write the same file, and
     * only the two newest zips are kept - the system evicts the cache dir when
     * storage runs low anyway. Returns null when there is nothing to share or
     * the zip cannot be written.
     */
    public static File zipLatestSession(Context context) {
        File session = latestSessionDir(context);
        if (session == null) {
            return null;
        }
        return zipSessions(context, Collections.singletonList(session), null, true);
    }

    /**
     * Zips the given session directories into destZip (or a uniquely named
     * file in the app cache when destZip is null). Each session lands under
     * its own "session_.../" folder; when includeDeviceInfo is set, the
     * newest session's device_info.txt is also copied to the zip root so a
     * bundle of several sessions still says which device produced them.
     * Rotated (.old) generations are skipped: the live file carries the
     * session. Returns null when nothing could be written.
     */
    public static File zipSessions(Context context, List<File> sessions, File destZip,
            boolean includeDeviceInfo) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        File cache = new File(context.getCacheDir(), "log_share");
        if (!cache.exists() && !cache.mkdirs()) {
            return null;
        }
        File zip = destZip != null ? destZip
                : new File(cache, sessions.get(0).getName() + "_"
                        + System.currentTimeMillis() + ".zip");
        if (destZip == null) {
            pruneShareZips(cache);
        }
        try {
            boolean wrote = false;
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                for (File session : sessions) {
                    File[] children = session.listFiles();
                    if (children == null) {
                        continue;
                    }
                    List<File> files = new ArrayList<>(Arrays.asList(children));
                    Collections.sort(files, (a, b) ->
                            a.getName().compareToIgnoreCase(b.getName()));
                    for (File file : files) {
                        if (!file.isFile() || file.getName().endsWith(".old")) {
                            continue;
                        }
                        out.putNextEntry(new ZipEntry(session.getName() + "/" + file.getName()));
                        copyInto(out, file);
                        out.closeEntry();
                        wrote = true;
                    }
                }
                if (includeDeviceInfo) {
                    for (File session : sessions) {
                        File info = new File(session, "device_info.txt");
                        if (info.isFile()) {
                            out.putNextEntry(new ZipEntry("device_info.txt"));
                            copyInto(out, info);
                            out.closeEntry();
                            break; // the newest session's header is enough
                        }
                    }
                }
            }
            return wrote || zip.length() > 0 ? zip : null;
        } catch (IOException e) {
            Log.w(TAG, "Could not zip the log sessions", e);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            return null;
        }
    }

    private static void copyInto(ZipOutputStream out, File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** Keeps only the two newest share zips around. */
    private static void pruneShareZips(File cache) {
        File[] zips = cache.listFiles((dir, name) -> name.endsWith(".zip"));
        if (zips == null || zips.length <= 2) {
            return;
        }
        List<File> ordered = new ArrayList<>(Arrays.asList(zips));
        Collections.sort(ordered, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i + 2 < ordered.size(); ++i) {
            //noinspection ResultOfMethodCallIgnored
            ordered.get(i).delete();
        }
    }

    /** Removes oldest sessions so only KEEP_SESSIONS remain. */
    private static void pruneOldSessions(File root) {
        File[] sessions = root.listFiles((dir, name) -> name.startsWith(SESSION_PREFIX));
        if (sessions == null || sessions.length <= KEEP_SESSIONS) {
            return;
        }
        List<File> ordered = new ArrayList<>(Arrays.asList(sessions));
        Collections.sort(ordered, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (int i = 0; i + KEEP_SESSIONS < ordered.size(); ++i) {
            deleteTree(ordered.get(i));
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /**
     * The Android half of device_info.txt. The Vulkan half (device caps,
     * extensions, features, limits, supported formats) is logged by the
     * runtime as VULKAN_CAPABILITY_REPORT and lands in all.log next to this
     * file; the Turnip driver's own messages arrive in logcat.txt.
     */
    private static void writeDeviceInfo(Context context, File session) {
        StringBuilder info = new StringBuilder(2048);
        info.append("Pinyon Shift realtime log session\n");
        info.append("started=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        try {
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            info.append("app_version=").append(versionName).append('\n');
        } catch (PackageManager.NameNotFoundException e) {
            info.append("app_version=unknown\n");
        }
        info.append("device_model=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        info.append("device_board=").append(Build.BOARD).append(" (")
                .append(Build.HARDWARE).append(")\n");
        info.append("android=").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        info.append("abis=").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        info.append("log_root_shared_storage=")
                .append(usingSharedStorage(context) ? "yes" : "no (app-specific dir)").append('\n');

        SharedPreferences prefs = context
                .getSharedPreferences(GamePickerActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String driver = prefs.getString(GamePickerActivity.PREF_GPU_DRIVER, "");
        info.append("gpu_driver=").append(driver.isEmpty() ? "system" : driver).append('\n');
        info.append("gpu_turbo=")
                .append(prefs.getBoolean(GamePickerActivity.PREF_GPU_TURBO, false)).append('\n');

        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) context
                            .getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                android.app.ActivityManager.MemoryInfo memory =
                        new android.app.ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                info.append("memory_total_mb=").append(memory.totalMem / (1024 * 1024)).append('\n');
                info.append("memory_available_mb=")
                        .append(memory.availMem / (1024 * 1024)).append('\n');
                info.append("memory_threshold_mb=")
                        .append(memory.threshold / (1024 * 1024)).append('\n');
                info.append("memory_class=").append(manager.getMemoryClass())
                        .append(" (large ").append(manager.getLargeMemoryClass()).append(")\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not collect memory info", e);
        }
        try {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            info.append("display=").append(metrics.widthPixels).append('x')
                    .append(metrics.heightPixels).append(" density ")
                    .append(String.format(Locale.US, "%.2f", metrics.density)).append('\n');
            float refresh = context.getDisplay() != null
                    ? context.getDisplay().getRefreshRate() : 0f;
            info.append("display_refresh_hz=")
                    .append(String.format(Locale.US, "%.1f", refresh)).append('\n');
        } catch (Exception e) {
            Log.w(TAG, "Could not collect display info", e);
        }
        info.append("note=vulkan device capability report in all.log"
                + " (VULKAN_CAPABILITY_REPORT); driver messages in logcat.txt\n");

        File target = new File(session, "device_info.txt");
        try (OutputStreamWriter out = new OutputStreamWriter(
                new FileOutputStream(target), "UTF-8")) {
            out.write(info.toString());
        } catch (IOException e) {
            Log.w(TAG, "Could not write device_info.txt", e);
        }
    }
}
