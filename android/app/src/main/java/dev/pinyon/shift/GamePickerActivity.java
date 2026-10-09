/*
 ******************************************************************************
 * Pinyon Shift - game location picker (launcher screen)
 ******************************************************************************
 * Modeled after XenDroid's game library: the player picks the game content
 * where it already lives on the device (SAF folder picker, remembered with a
 * persistable URI permission) and the game reads it in place — nothing is
 * copied into the app's private storage.
 *
 * The ShiftGlue runtime reaches game files through plain filesystem paths
 * (std::filesystem + HostPathDevice), so after the SAF pick the tree URI is
 * resolved back to its real device path (/storage/emulated/0/... or
 * /storage/XXXX-XXXX/...). Reading arbitrary (non-media) files in shared
 * storage through a real path needs, like on XenDroid:
 *   - Android 10 (API 29): READ_EXTERNAL_STORAGE + legacy storage flag.
 *   - Android 11+ (API 30+): All Files Access (MANAGE_EXTERNAL_STORAGE).
 * A built-in folder browser covers devices whose SAF picker refuses folders
 * (MIUI/HyperOS) once All Files Access is granted.
 *
 * The expected selection is the folder extracted from the game disc — the
 * same folder the Windows launcher uses (it must contain media/ui, the host
 * UI reads Fonts.zip from there). A raw .iso cannot be used directly.
 */

package dev.pinyon.shift;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GamePickerActivity extends Activity {

    private static final String TAG = "PinyonShiftPicker";

    /** Intent extra PinyonActivity receives the selected game root through. */
    public static final String EXTRA_GAME_ROOT = "dev.pinyon.shift.GAME_ROOT";

    static final String PREFS_NAME = "pinyon_game";
    static final String PREF_GAME_ROOT = "game_root";
    static final String PREF_GAME_URI = "game_tree_uri";
    static final String PREF_GPU_DRIVER = "gpu_driver";
    static final String PREF_GPU_TURBO = "gpu_turbo";

    private static final int REQUEST_PICK_TREE = 41;
    private static final int REQUEST_ALL_FILES = 42;
    private static final int REQUEST_READ_STORAGE = 43;
    private static final int REQUEST_PICK_DRIVER_ZIP = 44;

    /** Below this the app cannot comfortably keep saves, caches and logs. */
    private static final long LOW_STORAGE_BYTES = 512L * 1024 * 1024;

    private TextView statusTitle;
    private TextView statusPath;
    private TextView statusDetail;
    private Button selectButton;
    private Button permissionButton;
    private Button browseButton;
    private Button driverButton;
    private Button logsButton;
    private Button playButton;

    private boolean autoOpenedPicker = false;

    // Forza-style launcher chrome (header, chip, columns, footer).
    private View rootView;
    private View titleBlock;
    private View leftColumn;
    private View rightColumn;
    private TextView driverChip;
    private TextView driverIndicator;
    private TextView versionText;

    /** Shared easing for the focus glow and the entrance transitions. */
    private static final DecelerateInterpolator FORZA_INTERPOLATOR =
            new DecelerateInterpolator(1.4f);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_picker);

        rootView = findViewById(R.id.picker_root);
        titleBlock = findViewById(R.id.picker_title_block);
        leftColumn = findViewById(R.id.picker_left_column);
        rightColumn = findViewById(R.id.picker_right_column);
        driverChip = findViewById(R.id.picker_driver_chip);
        driverIndicator = findViewById(R.id.picker_driver_indicator);
        versionText = findViewById(R.id.picker_version_text);

        statusTitle = findViewById(R.id.picker_status_title);
        statusPath = findViewById(R.id.picker_status_path);
        statusDetail = findViewById(R.id.picker_status_detail);
        selectButton = findViewById(R.id.picker_select_button);
        permissionButton = findViewById(R.id.picker_permission_button);
        browseButton = findViewById(R.id.picker_browse_button);
        driverButton = findViewById(R.id.picker_driver_button);
        logsButton = findViewById(R.id.picker_logs_button);
        playButton = findViewById(R.id.picker_play_button);

        selectButton.setOnClickListener(v -> openDocumentPicker());
        permissionButton.setOnClickListener(v -> requestStorageAccess());
        browseButton.setOnClickListener(v -> openFolderBrowser(Environment.getExternalStorageDirectory()));
        driverButton.setOnClickListener(v -> openDriverDialog());
        logsButton.setOnClickListener(v -> openLogsDialog());
        playButton.setOnClickListener(v -> startGame());

        setupImmersiveMode();
        applyDisplayCutoutPadding();
        showAppVersion();
        refreshUi();
        attachForzaFeedback();
        playEntranceAnimation();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();

        // First run: jump straight into the picker, like XenDroid's empty
        // library. Only once — after a cancel the buttons stay visible.
        if (currentGameRoot() == null && !autoOpenedPicker) {
            autoOpenedPicker = true;
            openDocumentPicker();
        }
    }

    // ------------------------------------------------------------------ UI

    private void refreshUi() {
        updateDriverStatus();
        refreshGameStatus();
    }

    private void refreshGameStatus() {
        String root = currentGameRoot();

        if (root == null) {
            statusTitle.setText(R.string.picker_status_none);
            statusPath.setText(R.string.picker_status_none_hint);
            statusDetail.setText(R.string.picker_help_location);
            permissionButton.setVisibility(View.GONE);
            playButton.setEnabled(false);
            return;
        }

        statusPath.setText(root);

        File dir = new File(root);
        if (!dir.isDirectory()) {
            // Gone (unplugged drive, deleted, renamed): ask for a new pick.
            statusTitle.setText(R.string.picker_status_missing);
            statusDetail.setText(R.string.picker_status_missing_detail);
            permissionButton.setVisibility(View.GONE);
            playButton.setEnabled(false);
            return;
        }

        if (!ensureReadAccess(dir)) {
            statusTitle.setText(R.string.picker_status_need_permission);
            statusDetail.setText(Build.VERSION.SDK_INT >= 30
                    ? R.string.picker_help_all_files_access
                    : R.string.picker_help_read_storage);
            permissionButton.setVisibility(View.VISIBLE);
            playButton.setEnabled(false);
            return;
        }

        if (isGameContentRoot(dir)) {
            repairMtpDroppedDirectories(dir);
            statusTitle.setText(R.string.picker_status_ready);
            statusDetail.setText(readyDetail(dir));
            permissionButton.setVisibility(View.GONE);
            playButton.setEnabled(true);
        } else {
            statusTitle.setText(R.string.picker_status_invalid);
            statusDetail.setText(looksLikeIsoFolder(dir)
                    ? R.string.picker_status_iso_hint
                    : R.string.picker_help_location);
            permissionButton.setVisibility(View.GONE);
            playButton.setEnabled(false);
        }
    }

    /** True when the folder looks like the extracted disc content. */
    private static boolean isGameContentRoot(File dir) {
        return new File(dir, "media" + File.separator + "ui").isDirectory();
    }

    /**
     * The ready-state detail line: the normal in-place note plus the device
     * warnings the game would otherwise only reveal after a failed start (a
     * missing Vulkan 1.1 report, or internal storage too full for saves and
     * caches). Warnings, not blockers: the native runtime repeats both checks
     * with the authoritative answer and its own error reporting.
     */
    private String readyDetail(File dir) {
        StringBuilder detail = new StringBuilder(
                getString(R.string.picker_status_ready_detail));
        if (!hasVulkan11()) {
            Log.w(TAG, "Device does not report the Vulkan 1.1 feature level");
            detail.append('\n').append(getString(R.string.picker_warning_no_vulkan));
        }
        File files = getFilesDir();
        long usable = files != null ? files.getUsableSpace() : -1L;
        if (usable >= 0 && usable < LOW_STORAGE_BYTES) {
            Log.w(TAG, "Internal storage low: " + usable + " bytes usable");
            detail.append('\n').append(getString(R.string.picker_warning_low_storage));
        }
        if (isColourGradingMapsEmpty(dir)) {
            Log.w(TAG, "media/dynamicpost/colourgradingmaps has no files; the disc "
                    + "copy likely lost them over MTP");
            detail.append('\n').append(getString(R.string.picker_warning_no_grading_maps));
        } else {
            // Integrity summary beyond existence: the file count and size, so
            // "all files present" vs "a partial copy" are distinguishable in
            // the logcat the owner sends back with a bug report.
            int gradingFiles = colourGradingFiles(dir);
            long gradingBytes = colourGradingMapsBytes(dir);
            Log.i(TAG, "media/dynamicpost/colourgradingmaps: " + gradingFiles
                    + " file(s), " + gradingBytes + " bytes");
        }
        return detail.toString();
    }

    /**
     * True when the disc's colour grading map folder holds no files. The game
     * loads its exposure/color-grading look-up tables from
     * media/dynamicpost/colourgradingmaps (a build 33 device log shows the
     * folder itself failing STATUS_NO_SUCH_FILE on MTP copies, so a copy that
     * skipped the files too leaves the folder - recreated empty by
     * repairMtpDroppedDirectories - without any of them). Warning, not a
     * blocker: the game boots and renders, but exposure/bloom grading can
     * look blown out until the folder is re-copied from the disc extraction.
     */
    private static boolean isColourGradingMapsEmpty(File root) {
        return colourGradingFiles(root) == 0;
    }

    /** The number of files in the disc's colour grading map folder (0 also
     *  when the folder is missing, which repairMtpDroppedDirectories then
     *  recreates empty). */
    private static int colourGradingFiles(File root) {
        File gradingMaps =
                new File(new File(new File(root, "media"), "dynamicpost"), "colourgradingmaps");
        String[] entries = gradingMaps.list();
        return entries == null ? 0 : entries.length;
    }

    /** Total bytes of the colour grading map files, for the integrity log. */
    private static long colourGradingMapsBytes(File root) {
        File gradingMaps =
                new File(new File(new File(root, "media"), "dynamicpost"), "colourgradingmaps");
        File[] files = gradingMaps.listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    /** True when the system reports the Vulkan 1.1 feature level (0x00401000). */
    private boolean hasVulkan11() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                0x00401000);
    }

    /**
     * Recreates disc directories that MTP copies are known to drop: file
     * transfer over MTP skips empty folders, and the disc has some the game
     * expects to open. The runtime log of a build 33 session shows
     * game:\media\effects\, game:\media\stringtables\en\ and
     * game:\media\dynamicpost\colourgradingmaps\ failing with
     * STATUS_NO_SUCH_FILE (0xc000000f) on copies made over USB. Creating them
     * is a no-op when they are already there.
     */
    private static void repairMtpDroppedDirectories(File root) {
        File media = new File(root, "media");
        if (media.isDirectory()) {
            new File(media, "effects").mkdirs();
            new File(new File(media, "stringtables"), "en").mkdirs();
            new File(new File(media, "dynamicpost"), "colourgradingmaps").mkdirs();
        }
    }

    /** True when the folder only seems to hold disc image files. */
    private static boolean looksLikeIsoFolder(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        int isos = 0;
        int others = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".iso") || name.endsWith(".xex")) {
                isos++;
            } else {
                others++;
            }
        }
        return isos > 0 && others == 0;
    }

    // ------------------------------------------------------- picker + paths

    private void openDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        String rememberedUri = prefs().getString(PREF_GAME_URI, null);
        if (rememberedUri != null && Build.VERSION.SDK_INT >= 26) {
            // Seed the system picker at the last used location.
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(rememberedUri));
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_TREE);
        } catch (Exception e) {
            Log.w(TAG, "No SAF picker available", e);
            Toast.makeText(this, R.string.picker_no_saf, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_TREE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            handleTreeResult(data);
        } else if (requestCode == REQUEST_ALL_FILES) {
            refreshUi();
        } else if (requestCode == REQUEST_PICK_DRIVER_ZIP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            importDriverZip(data.getData());
        }
    }

    private void handleTreeResult(Intent data) {
        Uri treeUri = data.getData();
        try {
            // Canonical pattern: persist whatever the picker actually granted
            // (the tree picker grants read and write for the picked tree).
            final int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                       | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not persist URI permission", e);
        }

        String path = resolveTreeUriToPath(treeUri);
        if (path == null) {
            Toast.makeText(this, R.string.picker_unresolvable_location, Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences.Editor editor = prefs().edit();
        editor.putString(PREF_GAME_ROOT, path);
        editor.putString(PREF_GAME_URI, treeUri.toString());
        editor.apply();
        refreshUi();

        if (!ensureReadAccess(new File(path))) {
            // The folder is fine, the app just may not read it yet.
            requestStorageAccess();
        }
    }

    /**
     * Maps a SAF tree URI from the device storage provider back to its real
     * filesystem path, e.g.
     * content://com.android.externalstorage.documents/tree/primary%3AGames%2FFH
     * -> /storage/emulated/0/Games/FH. Returns null for providers that have no
     * filesystem location (documents providers, cloud, etc.).
     */
    static String resolveTreeUriToPath(Uri treeUri) {
        if (treeUri == null
                || !"com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return null;
        }
        if (docId == null) {
            return null;
        }
        int sep = docId.indexOf(':');
        if (sep < 0) {
            return null;
        }
        String volume = docId.substring(0, sep);
        String relative = docId.substring(sep + 1);

        String base;
        if ("primary".equals(volume)) {
            File primary = Environment.getExternalStorageDirectory();
            base = primary != null ? primary.getAbsolutePath() : "/storage/emulated/0";
        } else {
            base = findVolumeRoot(volume);
            if (base == null) {
                return null;
            }
        }
        File resolved = relative.isEmpty() ? new File(base) : new File(base, relative);
        return resolved.getAbsolutePath();
    }

    /** Locates removable/secondary storage, e.g. volume 1234-ABCD -> /storage/1234-ABCD. */
    private static String findVolumeRoot(String volume) {
        File[] candidates = {new File("/storage", volume), new File("/mnt/media_rw", volume)};
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    // ----------------------------------------------------------- permissions

    private boolean ensureReadAccess(File dir) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                return false;
            }
        } else if (checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        // Some devices report the permission but still hide files on FUSE.
        try {
            return dir.isDirectory() && dir.canRead() && dir.listFiles() != null;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            if (intent.resolveActivity(getPackageManager()) == null) {
                intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            }
            try {
                startActivityForResult(intent, REQUEST_ALL_FILES);
            } catch (Exception e) {
                Log.w(TAG, "All Files Access screen unavailable", e);
                Toast.makeText(this, R.string.picker_no_all_files_screen, Toast.LENGTH_LONG).show();
            }
        } else {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
            }, REQUEST_READ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_READ_STORAGE) {
            refreshUi();
        }
    }

    // -------------------------------------------------------- folder browser

    /**
     * Minimal directory browser for devices whose SAF picker refuses folders
     * (MIUI/HyperOS, same reason XenDroid ships one): with All Files Access
     * granted the game content can be chosen by walking the real filesystem.
     */
    private void openFolderBrowser(final File startAt) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, R.string.picker_help_all_files_access, Toast.LENGTH_LONG).show();
            requestStorageAccess();
            return;
        }
        File start = startAt != null && startAt.isDirectory() ? startAt : new File("/storage");
        showBrowserDialog(start);
    }

    private void showBrowserDialog(final File initial) {
        final ListView list = new ListView(this);
        final File[] current = {initial};

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.picker_browse_title)
                .setView(list)
                .setPositiveButton(R.string.picker_browse_use_folder, (d, w) -> {
                    File chosen = current[0];
                    prefs().edit()
                            .putString(PREF_GAME_ROOT, chosen.getAbsolutePath())
                            .putString(PREF_GAME_URI, null)
                            .apply();
                    refreshUi();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();

        Runnable[] updater = new Runnable[1];
        updater[0] = () -> {
            File dir = current[0];
            dialog.setTitle(dir.getAbsolutePath());
            List<String> entries = new ArrayList<>();
            final List<File> dirs = new ArrayList<>();
            File parent = dir.getParentFile();
            if (parent != null && parent.getAbsolutePath().startsWith("/storage")) {
                entries.add("..");
                dirs.add(parent);
            }
            File[] children = dir.listFiles();
            if (children == null) {
                entries.add(getString(R.string.picker_browse_empty));
            } else {
                List<File> sorted = new ArrayList<>();
                Collections.addAll(sorted, children);
                Collections.sort(sorted, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File child : sorted) {
                    if (child.isDirectory() && !child.getName().startsWith(".")) {
                        entries.add(child.getName());
                        dirs.add(child);
                    }
                }
            }
            list.setAdapter(new ArrayAdapter<>(this, R.layout.item_forza_list, entries));
            list.setOnItemClickListener((parentView, view, position, id) -> {
                if (position < dirs.size()) {
                    current[0] = dirs.get(position);
                    updater[0].run();
                }
            });
        };
        updater[0].run();
    }

    // --------------------------------------------------------- realtime logs

    /**
     * The "Salvar logs em tempo real" settings: a persistent toggle, a level
     * (Normal / Verbose GPU / Debug total) and the share/open actions. Changes
     * apply to the next game start: the session directory and the logcat
     * capture are created before the game process starts its main.
     */
    private void openLogsDialog() {
        final SharedPreferences prefs = prefs();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);

        final CheckBox realtime = new CheckBox(this);
        realtime.setText(R.string.logs_realtime_toggle);
        realtime.setChecked(prefs.getBoolean(LogSessions.PREF_REALTIME, false));
        realtime.setButtonTintList(ColorStateList.valueOf(ResourcesCompat.getColor(
                getResources(), R.color.forza_yellow, getTheme())));
        setForzaTypeface(realtime, R.font.rajdhani_medium, false);
        content.addView(realtime);

        TextView destination = new TextView(this);
        destination.setText(getString(
                LogSessions.usingSharedStorage(this) ? R.string.logs_destination_shared
                                                      : R.string.logs_destination_fallback,
                LogSessions.logRoot(this).getAbsolutePath()));
        destination.setTextSize(12f);
        setForzaTypeface(destination, R.font.rajdhani_regular, false);
        content.addView(destination);

        TextView levelLabel = new TextView(this);
        levelLabel.setText(R.string.logs_level_label);
        levelLabel.setTextColor(ResourcesCompat.getColor(getResources(),
                R.color.forza_yellow, getTheme()));
        levelLabel.setPadding(0, padding / 2, 0, 0);
        setForzaTypeface(levelLabel, R.font.rajdhani_bold, true);
        content.addView(levelLabel);

        final RadioGroup level = new RadioGroup(this);
        String current = prefs.getString(LogSessions.PREF_LEVEL, LogSessions.LEVEL_NORMAL);
        RadioButton normal = new RadioButton(this);
        normal.setId(R.id.logs_level_normal);
        normal.setText(R.string.logs_level_normal);
        normal.setChecked(!LogSessions.LEVEL_GPU.equals(current)
                && !LogSessions.LEVEL_FULL.equals(current));
        normal.setButtonTintList(ColorStateList.valueOf(ResourcesCompat.getColor(
                getResources(), R.color.forza_yellow, getTheme())));
        setForzaTypeface(normal, R.font.rajdhani_medium, false);
        level.addView(normal);
        RadioButton gpu = new RadioButton(this);
        gpu.setId(R.id.logs_level_gpu);
        gpu.setText(R.string.logs_level_gpu);
        gpu.setChecked(LogSessions.LEVEL_GPU.equals(current));
        gpu.setButtonTintList(ColorStateList.valueOf(ResourcesCompat.getColor(
                getResources(), R.color.forza_yellow, getTheme())));
        setForzaTypeface(gpu, R.font.rajdhani_medium, false);
        level.addView(gpu);
        RadioButton full = new RadioButton(this);
        full.setId(R.id.logs_level_full);
        full.setText(R.string.logs_level_full);
        full.setChecked(LogSessions.LEVEL_FULL.equals(current));
        full.setButtonTintList(ColorStateList.valueOf(ResourcesCompat.getColor(
                getResources(), R.color.forza_yellow, getTheme())));
        setForzaTypeface(full, R.font.rajdhani_medium, false);
        level.addView(full);
        content.addView(level);

        final Button share = new Button(this);
        share.setText(R.string.logs_share_button);
        share.setAllCaps(false);
        setForzaTypeface(share, R.font.rajdhani_bold, true);
        share.setOnClickListener(v -> shareLatestLogs());
        content.addView(share);

        new AlertDialog.Builder(this)
                .setTitle(R.string.logs_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String chosen = LogSessions.LEVEL_NORMAL;
                    if (level.getCheckedRadioButtonId() == R.id.logs_level_gpu) {
                        chosen = LogSessions.LEVEL_GPU;
                    } else if (level.getCheckedRadioButtonId() == R.id.logs_level_full) {
                        chosen = LogSessions.LEVEL_FULL;
                    }
                    prefs.edit()
                            .putBoolean(LogSessions.PREF_REALTIME, realtime.isChecked())
                            .putString(LogSessions.PREF_LEVEL, chosen)
                            .apply();
                    Toast.makeText(this, R.string.logs_saved_next_start, Toast.LENGTH_LONG)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Zips the newest session on a worker thread and hands it to the system
     *  share sheet - a "full" level session can be hundreds of megabytes, and
     *  compressing that on the UI thread would freeze the picker. Re-entrant
     *  taps are ignored while a zip is in flight (two threads writing the same
     *  file would corrupt it), and each zip gets a unique name so a leftover
     *  share from an earlier attempt never collides.
     */
    private final java.util.concurrent.atomic.AtomicBoolean zipInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void shareLatestLogs() {
        if (!zipInFlight.compareAndSet(false, true)) {
            return;
        }
        Toast.makeText(this, R.string.logs_zipping, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File zip = LogSessions.zipLatestSession(this);
            runOnUiThread(() -> {
                zipInFlight.set(false);
                if (zip == null) {
                    Toast.makeText(this, R.string.logs_no_session, Toast.LENGTH_LONG).show();
                    return;
                }
                shareZip(zip);
            });
        }, "pinyon-log-zip").start();
    }

    private void shareZip(File zip) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".logfiles", zip);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/zip");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_SUBJECT, zip.getName());
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, getString(R.string.logs_share_button)));
        } catch (Exception e) {
            Log.w(TAG, "Could not share the log zip", e);
            Toast.makeText(this, R.string.logs_share_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------- gpu drivers

    /** The selected custom driver folder name; empty means the system driver. */
    static String currentGpuDriver(SharedPreferences prefs) {
        return prefs.getString(PREF_GPU_DRIVER, "");
    }

    private void openDriverDialog() {
        List<GpuDrivers.InstalledDriver> installed = GpuDrivers.list(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, 0);

        TextView note = new TextView(this);
        note.setText(R.string.picker_driver_active_note);
        note.setTextSize(12);
        note.setTextColor(ResourcesCompat.getColor(getResources(),
                R.color.forza_text_dim, getTheme()));
        setForzaTypeface(note, R.font.rajdhani_regular, false);
        content.addView(note);

        // Item 0 is the system driver; the rest are the installed packages.
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.picker_driver_system));
        for (GpuDrivers.InstalledDriver driver : installed) {
            String label = driver.displayName;
            if (!driver.version.isEmpty()) {
                label += "  (" + driver.version + ")";
            }
            labels.add(label);
        }
        final int[] choice = {0};
        String current = prefs().getString(PREF_GPU_DRIVER, "");
        for (int i = 0; i < installed.size(); i++) {
            if (installed.get(i).folderName.equals(current)) {
                choice[0] = i + 1;
                break;
            }
        }

        CheckBox turbo = new CheckBox(this);
        turbo.setText(R.string.picker_driver_turbo);
        turbo.setChecked(prefs().getBoolean(PREF_GPU_TURBO, false));
        turbo.setButtonTintList(ColorStateList.valueOf(ResourcesCompat.getColor(
                getResources(), R.color.forza_yellow, getTheme())));
        setForzaTypeface(turbo, R.font.rajdhani_medium, false);
        content.addView(turbo);

        ListView list = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.item_driver_choice, labels);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setItemChecked(choice[0], true);
        content.addView(list,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1, 1f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.picker_driver_title)
                .setView(content)
                .setPositiveButton(R.string.picker_browse_use_folder, (d, w) -> {
                    String folder = choice[0] == 0 ? "" : installed.get(choice[0] - 1).folderName;
                    prefs().edit()
                            .putString(PREF_GPU_DRIVER, folder)
                            .putBoolean(PREF_GPU_TURBO, turbo.isChecked())
                            .apply();
                    refreshUi();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.picker_driver_install, (d, w) -> pickDriverZip())
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> choice[0] = position);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position == 0) {
                return false;
            }
            confirmRemoveDriver(dialog, installed.get(position - 1));
            return true;
        });
        dialog.show();
    }

    private void confirmRemoveDriver(AlertDialog parent, GpuDrivers.InstalledDriver driver) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.picker_driver_title)
                .setMessage(getString(R.string.picker_driver_remove_confirm, driver.displayName))
                .setPositiveButton(android.R.string.yes, (d, w) -> {
                    GpuDrivers.remove(this, driver.folderName);
                    if (prefs().getString(PREF_GPU_DRIVER, "").equals(driver.folderName)) {
                        // The active driver is gone: back to the system driver.
                        prefs().edit().putString(PREF_GPU_DRIVER, "").apply();
                    }
                    parent.dismiss();
                    openDriverDialog();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickDriverZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        try {
            startActivityForResult(intent, REQUEST_PICK_DRIVER_ZIP);
        } catch (Exception e) {
            // Some providers only offer the generic type.
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQUEST_PICK_DRIVER_ZIP);
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.picker_no_saf, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void importDriverZip(Uri zipUri) {
        Toast.makeText(this, R.string.picker_driver_importing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                GpuDrivers.InstalledDriver driver = GpuDrivers.importZip(this, zipUri);
                // The fresh install becomes the active driver right away: the
                // natural flow is install-and-play, and the dialog stays
                // available for switching back to another package or the
                // system driver. Takes effect on the next game start.
                prefs().edit().putString(PREF_GPU_DRIVER, driver.folderName).apply();
                message = getString(R.string.picker_driver_installed_ok, driver.displayName);
            } catch (Exception e) {
                message = e.getMessage() != null
                        ? e.getMessage()
                        : getString(R.string.picker_driver_import_failed);
            }
            final String shown = message;
            runOnUiThread(() -> {
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                refreshUi();
            });
        }, "driver-import").start();
    }

    // -------------------------------------------------------- forza chrome

    /**
     * Immersive mode: system bars hidden (a swipe reveals them
     * temporarily, like on the in-game activity) and the window allowed
     * to draw behind display cutouts. The notch insets become padding on
     * the screen root, so the launcher chrome never lands under a notch.
     */
    private void setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemBars();
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(attributes);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** Pads the screen content away from display cutouts and system bars. */
    private void applyDisplayCutoutPadding() {
        final int basePadding =
                getResources().getDimensionPixelSize(R.dimen.forza_screen_padding);
        rootView.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = WindowInsetsCompat.toCompatInsets(insets).getInsets(
                    WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.systemBars());
            view.setPadding(basePadding + safe.left, safe.top,
                    basePadding + safe.right, safe.bottom);
            return insets;
        });
    }

    /** Writes the app version under the START GAME slab. */
    private void showAppVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionText.setText(getString(R.string.picker_version_format, info.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not read the app version", e);
            versionText.setText(R.string.app_name);
        }
    }

    /**
     * Gamepad + touch feel: every action button grows slightly and picks
     * up its glow stroke when the D-pad focus lands on it, shrinks on
     * press and springs back on release. The initial focus goes to the
     * primary action when the game is ready to play.
     */
    private void attachForzaFeedback() {
        Button[] actions = {selectButton, permissionButton, browseButton,
                driverButton, logsButton, playButton};
        for (Button button : actions) {
            button.setOnFocusChangeListener((view, hasFocus) -> view.animate()
                    .scaleX(hasFocus ? 1.04f : 1f)
                    .scaleY(hasFocus ? 1.04f : 1f)
                    .setDuration(120)
                    .setInterpolator(FORZA_INTERPOLATOR)
                    .start());
            button.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.97f).scaleY(0.97f)
                                .setDuration(60).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float resting = view.isFocused() ? 1.04f : 1f;
                        view.animate().scaleX(resting).scaleY(resting)
                                .setDuration(90).start();
                        break;
                    default:
                        break;
                }
                // Touch feedback only; the normal click flow keeps running.
                return false;
            });
        }
        if (playButton.isEnabled()) {
            playButton.requestFocus();
        } else {
            selectButton.requestFocus();
        }
    }

    /**
     * Entrance transition: the header slides in from the left, the driver
     * chip drops from the top and the two columns slide in from opposite
     * sides, staggered like a racing front end booting up.
     */
    private void playEntranceAnimation() {
        int step = getResources().getDimensionPixelSize(R.dimen.forza_screen_padding);
        animateEntrance(titleBlock, -step, 0, 0);
        animateEntrance(driverChip, 0, -step / 2f, 90);
        animateEntrance(leftColumn, -step, 0, 150);
        animateEntrance(rightColumn, step, 0, 220);
    }

    private void animateEntrance(View view, float fromX, float fromY, long delayMillis) {
        view.setAlpha(0f);
        view.setTranslationX(fromX);
        view.setTranslationY(fromY);
        view.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(420)
                .setStartDelay(delayMillis)
                .setInterpolator(FORZA_INTERPOLATOR)
                .start();
    }

    /**
     * The driver chip (header) and the indicator under START GAME always
     * mirror the active Vulkan driver: the system driver when nothing is
     * selected, otherwise the installed package name and version.
     */
    private void updateDriverStatus() {
        String folder = prefs().getString(PREF_GPU_DRIVER, "");
        String label = null;
        if (!folder.isEmpty()) {
            for (GpuDrivers.InstalledDriver driver : GpuDrivers.list(this)) {
                if (driver.folderName.equals(folder)) {
                    label = driver.version.isEmpty()
                            ? driver.displayName
                            : driver.displayName + " (" + driver.version + ")";
                    break;
                }
            }
        }
        if (label == null) {
            driverChip.setText(R.string.picker_driver_chip_default);
            driverIndicator.setText(R.string.picker_driver_indicator_default);
        } else {
            driverChip.setText(getString(R.string.picker_driver_chip_format, label));
            driverIndicator.setText(getString(R.string.picker_driver_indicator_format, label));
        }
    }

    /** Applies one of the bundled Forza fonts to a programmatically
     * created dialog view. */
    private void setForzaTypeface(TextView view, int fontResId, boolean allCaps) {
        Typeface face = ResourcesCompat.getFont(this, fontResId);
        if (face != null) {
            view.setTypeface(face);
        }
        view.setAllCaps(allCaps);
    }

    // ------------------------------------------------------------------ game

    private void startGame() {
        String root = currentGameRoot();
        if (root == null || !isGameContentRoot(new File(root))) {
            refreshUi();
            return;
        }
        Intent intent = new Intent(this, PinyonActivity.class);
        intent.putExtra(EXTRA_GAME_ROOT, root);
        startActivity(intent);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    /**
     * The selected game root, with fallbacks: the saved selection, then the
     * legacy Android/data location used before the picker existed.
     */
    private String currentGameRoot() {
        String saved = prefs().getString(PREF_GAME_ROOT, null);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        File external = getExternalFilesDir(null);
        if (external != null) {
            File legacy = new File(external, "game/base");
            if (isGameContentRoot(legacy) && legacy.canRead()) {
                return legacy.getAbsolutePath();
            }
        }
        return null;
    }
}
