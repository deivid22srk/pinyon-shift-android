package dev.pinyon.shift;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Forza-styled logs screen: one place to flip realtime recording on and
 * off (with the detail level), to see every recorded session with its size,
 * and to view / share / save / delete each of them. Sessions live under the
 * log root chosen by LogSessions (shared "forza" folder with All Files
 * Access, else the app-specific dir); zips are staged in the app cache and
 * handed out through the .logfiles FileProvider, so no broad storage
 * permission is needed for exporting.
 */
public class LogsActivity extends Activity {
    private static final String TAG = "PinyonShiftLogs";

    private static final int REQUEST_SAVE_LOG = 51;

    /** Viewer read cap: files can reach the rotation limit (200 MB); the
     *  in-app viewer shows the tail, which is where the interesting state is. */
    private static final long VIEWER_TAIL_BYTES = 2L * 1024 * 1024;

    private View rootView;
    private ListView sessionList;
    private TextView emptyView;
    private CheckBox realtimeToggle;
    private TextView destinationText;
    private Button exportAllButton;
    private TextView kicker;

    private List<File> sessions = new ArrayList<>();
    private File pendingSaveZip;
    private String pendingSaveName;

    private final AtomicBoolean zipInFlight = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        rootView = findViewById(R.id.logs_root);
        sessionList = findViewById(R.id.logs_sessions_list);
        emptyView = findViewById(R.id.logs_sessions_empty);
        realtimeToggle = findViewById(R.id.logs_realtime_toggle);
        destinationText = findViewById(R.id.logs_destination);
        exportAllButton = findViewById(R.id.logs_export_all_button);
        kicker = findViewById(R.id.logs_kicker);

        setupImmersiveMode();
        applyDisplayCutoutPadding();

        SharedPreferences prefs = prefs();
        realtimeToggle.setChecked(prefs.getBoolean(LogSessions.PREF_REALTIME, false));
        realtimeToggle.setOnCheckedChangeListener(this::onRealtimeToggled);
        refreshDestination();
        restoreLevelSelection(prefs);

        exportAllButton.setOnClickListener(v -> exportAllSessions());

        sessionList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < sessions.size()) {
                openSessionMenu(sessions.get(position));
            }
        });

        loadSessions();
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
        // A game session may have produced new logs since we were here.
        loadSessions();
    }

    // ------------------------------------------------------------- sessions

    private void loadSessions() {
        new Thread(() -> {
            final List<File> found = LogSessions.listSessions(this);
            final List<CharSequence> rows = new ArrayList<>();
            SimpleDateFormat input = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            SimpleDateFormat output =
                    new SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault());
            for (File session : found) {
                String stamp = session.getName().substring(LogSessions.sessionPrefixLength());
                String when = stamp;
                try {
                    when = output.format(input.parse(stamp));
                } catch (Exception ignored) {
                    // keep the raw stamp
                }
                long bytes = LogSessions.directoryBytes(session);
                int files = LogSessions.sessionFileCount(session);
                rows.add(getString(R.string.logs_session_row, when,
                        formatBytes(bytes), files));
            }
            runOnUiThread(() -> {
                sessions = found;
                emptyView.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                exportAllButton.setEnabled(!found.isEmpty());
                sessionList.setAdapter(new ArrayAdapter<>(this,
                        R.layout.item_log_session, R.id.log_item_title, found) {
                    @Override
                    public View getView(int position, View convertView,
                            android.view.ViewGroup parent) {
                        View row = super.getView(position, convertView, parent);
                        TextView title = row.findViewById(R.id.log_item_title);
                        TextView detail = row.findViewById(R.id.log_item_detail);
                        if (position < rows.size()) {
                            title.setText(sessions.get(position).getName());
                            detail.setText(rows.get(position));
                        }
                        return row;
                    }
                });
            });
        }, "pinyon-logs-list").start();
    }

    private void openSessionMenu(final File session) {
        new AlertDialog.Builder(this)
                .setTitle(session.getName())
                .setItems(R.array.logs_session_actions, (dialog, which) -> {
                    if (which == 0) {
                        openViewer(session);
                    } else if (which == 1) {
                        shareSessions(java.util.Collections.singletonList(session));
                    } else if (which == 2) {
                        pickSaveDestination(java.util.Collections.singletonList(session));
                    } else {
                        confirmDelete(session);
                    }
                })
                .show();
    }

    private void confirmDelete(final File session) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.logs_delete_title)
                .setMessage(getString(R.string.logs_delete_confirm, session.getName()))
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    LogSessions.deleteSession(session);
                    Log.i(TAG, "Log session deleted: " + session.getName());
                    loadSessions();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------- settings

    private void onRealtimeToggled(CompoundButton button, boolean checked) {
        prefs().edit().putBoolean(LogSessions.PREF_REALTIME, checked).apply();
        Log.i(TAG, "Realtime log recording " + (checked ? "enabled" : "disabled"));
        refreshDestination();
        Toast.makeText(this,
                checked ? R.string.logs_saved_next_start : R.string.logs_turned_off,
                Toast.LENGTH_SHORT).show();
    }

    private void restoreLevelSelection(SharedPreferences prefs) {
        String current = prefs.getString(LogSessions.PREF_LEVEL, LogSessions.LEVEL_NORMAL);
        RadioGroup group = findViewById(R.id.logs_level_group);
        int id = LogSessions.LEVEL_GPU.equals(current) ? R.id.logs_level_gpu
                : LogSessions.LEVEL_FULL.equals(current) ? R.id.logs_level_full
                : R.id.logs_level_normal;
        group.check(id);
        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            String chosen = LogSessions.LEVEL_NORMAL;
            if (checkedId == R.id.logs_level_gpu) {
                chosen = LogSessions.LEVEL_GPU;
            } else if (checkedId == R.id.logs_level_full) {
                chosen = LogSessions.LEVEL_FULL;
            }
            prefs().edit().putString(LogSessions.PREF_LEVEL, chosen).apply();
        });
    }

    private void refreshDestination() {
        destinationText.setText(getString(
                LogSessions.usingSharedStorage(this) ? R.string.logs_destination_shared
                        : R.string.logs_destination_fallback,
                LogSessions.logRoot(this).getAbsolutePath()));
    }

    // ------------------------------------------------------------- exporting

    /** Zips every recorded session (plus device_info.txt) and shares it. */
    private void exportAllSessions() {
        shareSessions(new ArrayList<>(sessions));
    }

    private void shareSessions(final List<File> toShare) {
        if (toShare.isEmpty()) {
            Toast.makeText(this, R.string.logs_no_session, Toast.LENGTH_LONG).show();
            return;
        }
        if (!zipInFlight.compareAndSet(false, true)) {
            return;
        }
        Toast.makeText(this, R.string.logs_zipping, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File zip = LogSessions.zipSessions(this, toShare, null, true);
            runOnUiThread(() -> {
                zipInFlight.set(false);
                if (zip == null) {
                    Toast.makeText(this, R.string.logs_share_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                shareZip(zip);
            });
        }, "pinyon-log-zip").start();
    }

    private void shareZip(File zip) {
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".logfiles", zip);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/zip");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_SUBJECT, zip.getName());
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, getString(R.string.logs_action_share)));
        } catch (Exception e) {
            Log.w(TAG, "Could not share the log zip", e);
            Toast.makeText(this, R.string.logs_share_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** "Save to Downloads" through ACTION_CREATE_DOCUMENT: the player picks
     *  the destination, the app writes the zip to the returned URI — no
     *  broad storage permission involved. */
    private void pickSaveDestination(final List<File> toSave) {
        if (toSave.isEmpty()) {
            Toast.makeText(this, R.string.logs_no_session, Toast.LENGTH_LONG).show();
            return;
        }
        if (!zipInFlight.compareAndSet(false, true)) {
            return;
        }
        Toast.makeText(this, R.string.logs_zipping, Toast.LENGTH_SHORT).show();
        final boolean bundle = toSave.size() > 1;
        new Thread(() -> {
            File zip = LogSessions.zipSessions(this, toSave, null, true);
            runOnUiThread(() -> {
                zipInFlight.set(false);
                if (zip == null) {
                    Toast.makeText(this, R.string.logs_save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                pendingSaveZip = zip;
                pendingSaveName = bundle
                        ? "forza_logs_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(new Date()) + ".zip"
                        : zip.getName();
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.addCategory(Intent.CATEGORY_OPENABLE);
                create.setType("application/zip");
                create.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
                try {
                    startActivityForResult(create, REQUEST_SAVE_LOG);
                } catch (Exception e) {
                    Log.w(TAG, "No create-document picker available", e);
                    Toast.makeText(this, R.string.logs_save_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "pinyon-log-zip-save").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_LOG) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingSaveZip = null;
                return;
            }
            saveZipTo(pendingSaveZip, data.getData());
            pendingSaveZip = null;
        }
    }

    private void saveZipTo(final File zip, final Uri destination) {
        if (zip == null) {
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            try (InputStream in = new FileInputStream(zip);
                 OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    ok = true;
                }
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Could not save the log zip", e);
            }
            final boolean saved = ok;
            runOnUiThread(() -> Toast.makeText(this,
                    saved ? R.string.logs_saved : R.string.logs_save_failed,
                    saved ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show());
        }, "pinyon-log-save").start();
    }

    // --------------------------------------------------------------- viewer

    /** In-app log viewer with a level filter (tail of the main log file). */
    private void openViewer(final File session) {
        File file = pickViewerFile(session);
        if (file == null) {
            Toast.makeText(this, R.string.logs_viewer_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final AtomicBoolean busy = zipInFlight;
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            final String content = readTail(file, VIEWER_TAIL_BYTES);
            runOnUiThread(() -> {
                busy.set(false);
                if (content == null) {
                    Toast.makeText(this, R.string.logs_viewer_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                showViewerDialog(session, file, content);
            });
        }, "pinyon-log-view").start();
    }

    /** The most interesting file of a session: all.log first, then logcat. */
    private static File pickViewerFile(File session) {
        String[] preference = {"all.log", "logcat.txt", "logcat_crash.txt",
                "crash.log", "gpu.log", "vulkan.log", "fmv.log"};
        for (String name : preference) {
            File file = new File(session, name);
            if (file.isFile() && file.length() > 0) {
                return file;
            }
        }
        File[] children = session.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && !child.getName().endsWith(".old")) {
                    return child;
                }
            }
        }
        return null;
    }

    /** Reads at most maxBytes from the end of the file, cut at a line start. */
    private static String readTail(File file, long maxBytes) {
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            long skipped = 0;
            if (length > maxBytes) {
                skipped = in.skip(length - maxBytes);
            }
            byte[] buffer = new byte[(int) Math.max(1, length - skipped)];
            int offset = 0;
            int read;
            while (offset < buffer.length
                    && (read = in.read(buffer, offset, buffer.length - offset)) > 0) {
                offset += read;
            }
            String text = new String(buffer, 0, offset, StandardCharsets.UTF_8);
            int firstLine = text.indexOf('\n');
            if (skipped > 0 && firstLine >= 0) {
                // Drop the partial line the skip landed in.
                text = text.substring(firstLine + 1);
            }
            return text;
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + file, e);
            return null;
        }
    }

    private void showViewerDialog(final File session, final File file, final String content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        TextView header = new TextView(this);
        header.setText(getString(R.string.logs_viewer_header, file.getName(),
                formatBytes(file.length())));
        header.setTextSize(11f);
        header.setTextColor(ResourcesCompat.getColor(getResources(),
                R.color.forza_text_dim, getTheme()));
        setForzaTypeface(header, R.font.rajdhani_regular, false);
        root.addView(header);

        // Level filter chips: ALL / INFO / WARNINGS / ERRORS.
        final TextView logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(9f);
        logView.setTextColor(ResourcesCompat.getColor(getResources(),
                R.color.forza_text_primary, getTheme()));
        logView.setTextIsSelectable(true);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER);
        final String[] filtered = new String[1];
        filtered[0] = content;
        View[] buttons = new View[4];
        int[] labels = {R.string.logs_filter_all, R.string.logs_filter_info,
                R.string.logs_filter_warnings, R.string.logs_filter_errors};
        for (int i = 0; i < 4; i++) {
            final int mode = i;
            Button chip = new Button(this, null, android.R.attr.borderlessButtonStyle);
            chip.setText(labels[i]);
            chip.setAllCaps(true);
            setForzaTypeface(chip, R.font.rajdhani_bold, true);
            chip.setTextColor(ResourcesCompat.getColor(getResources(),
                    R.color.forza_yellow, getTheme()));
            chip.setOnClickListener(v -> {
                String applied = applyLevelFilter(content, mode);
                filtered[0] = applied;
                logView.setText(applied.isEmpty()
                        ? getString(R.string.logs_filter_empty) : applied);
            });
            filters.addView(chip);
        }
        root.addView(filters);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(logView);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1, 1f));

        new AlertDialog.Builder(this)
                .setTitle(session.getName())
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Level filter over the two line formats in a session:
     * all.log: "[2026-10-09 12:00:00.123] [info] [category] ..."
     * logcat:  "10-09 12:00:00.123 E/Tag(pid): ..."
     */
    private static String applyLevelFilter(String content, int mode) {
        if (mode == 0) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length() / 4);
        for (String line : content.split("\n", -1)) {
            if (linePassesFilter(line, mode)) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean linePassesFilter(String line, int mode) {
        boolean error = line.contains("[err") || line.contains("[critical")
                || line.contains("E/") || line.contains("F/")
                || line.contains("Exception") || line.contains("SIGSEGV")
                || line.contains("SIGABRT") || line.contains("FATAL");
        boolean warning = line.contains("[warning") || line.contains("W/");
        boolean debug = line.contains("[debug") || line.contains("[trace")
                || line.contains("D/") || line.contains("V/");
        if (mode == 3) { // errors
            return error;
        }
        if (mode == 2) { // warnings and above
            return error || warning;
        }
        // Info and above: drop the debug/verbose chatter.
        return !debug;
    }

    // -------------------------------------------------------- forza chrome

    private void setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemBars();
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
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

    private void applyDisplayCutoutPadding() {
        final int basePadding =
                getResources().getDimensionPixelSize(R.dimen.forza_screen_padding);
        rootView.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(
                    WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.systemBars());
            view.setPadding(basePadding + safe.left, safe.top,
                    basePadding + safe.right, safe.bottom);
            return insets;
        });
    }

    // -------------------------------------------------------------- helpers

    private static String formatBytes(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.US, "%.1f MB", bytes / (double) (1L << 20));
        }
        if (bytes >= 1L << 10) {
            return String.format(Locale.US, "%.0f KB", bytes / (double) (1L << 10));
        }
        return bytes + " B";
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(GamePickerActivity.PREFS_NAME, MODE_PRIVATE);
    }

    private void setForzaTypeface(TextView view, int fontResId, boolean allCaps) {
        Typeface face = ResourcesCompat.getFont(this, fontResId);
        if (face != null) {
            view.setTypeface(face);
        }
        view.setAllCaps(allCaps);
    }
}
