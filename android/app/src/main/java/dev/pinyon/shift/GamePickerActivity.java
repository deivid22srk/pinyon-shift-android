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
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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

    private static final int REQUEST_PICK_TREE = 41;
    private static final int REQUEST_ALL_FILES = 42;
    private static final int REQUEST_READ_STORAGE = 43;

    private TextView statusTitle;
    private TextView statusPath;
    private TextView statusDetail;
    private Button selectButton;
    private Button permissionButton;
    private Button browseButton;
    private Button playButton;

    private boolean autoOpenedPicker = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_picker);

        statusTitle = findViewById(R.id.picker_status_title);
        statusPath = findViewById(R.id.picker_status_path);
        statusDetail = findViewById(R.id.picker_status_detail);
        selectButton = findViewById(R.id.picker_select_button);
        permissionButton = findViewById(R.id.picker_permission_button);
        browseButton = findViewById(R.id.picker_browse_button);
        playButton = findViewById(R.id.picker_play_button);

        selectButton.setOnClickListener(v -> openDocumentPicker());
        permissionButton.setOnClickListener(v -> requestStorageAccess());
        browseButton.setOnClickListener(v -> openFolderBrowser(Environment.getExternalStorageDirectory()));
        playButton.setOnClickListener(v -> startGame());

        refreshUi();
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
            statusDetail.setText(R.string.picker_status_ready_detail);
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
     * Recreates disc directories that MTP copies are known to drop: file
     * transfer over MTP skips empty folders, and the disc has some the game
     * expects to open (the runtime log shows game:\\Media\\effects\\ failing
     * with STATUS_NO_SUCH_FILE on copies made over USB). Creating them is a
     * no-op when they are already there.
     */
    private static void repairMtpDroppedDirectories(File root) {
        File media = new File(root, "media");
        if (media.isDirectory()) {
            new File(media, "effects").mkdirs();
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
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, entries));
            list.setOnItemClickListener((parentView, view, position, id) -> {
                if (position < dirs.size()) {
                    current[0] = dirs.get(position);
                    updater[0].run();
                }
            });
        };
        updater[0].run();
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
