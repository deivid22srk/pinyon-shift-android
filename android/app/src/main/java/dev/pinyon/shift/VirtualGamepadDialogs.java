package dev.pinyon.shift;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

/**
 * Forza-styled virtual gamepad settings, shared by the picker screen (where
 * only the persisted values are edited) and the in-game floating button
 * (long-press), where the overlay is adjusted live and layout editing is
 * reachable. Sliders apply immediately so the change is visible while
 * dragging; everything lands in VirtualGamepadPrefs on the way.
 */
final class VirtualGamepadDialogs {

    private VirtualGamepadDialogs() {
    }

    /** Opens the settings; pass the live overlay when in the game activity. */
    static void openSettings(final Activity activity, final VirtualGamepadOverlay overlay) {
        final boolean live = overlay != null;
        // Snapshots for the cancel path: sliders apply live, so cancelling
        // must roll the values (and the overlay) back to what was saved.
        final boolean originalEnabled = VirtualGamepadPrefs.isEnabled(activity);
        final float originalOpacity = VirtualGamepadPrefs.getOpacity(activity);
        final float originalScale = VirtualGamepadPrefs.getScale(activity);
        final float originalDeadzone = VirtualGamepadPrefs.getDeadzone(activity);
        final boolean originalHaptics = VirtualGamepadPrefs.getHaptics(activity);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad / 2, pad, 0);

        // Master switch: when off, the game activity shows nothing at all.
        final CheckBox enable = styledCheck(activity, R.string.vgp_enable_switch);
        enable.setChecked(VirtualGamepadPrefs.isEnabled(activity));
        enable.setOnCheckedChangeListener((button, checked) ->
                VirtualGamepadPrefs.setEnabled(activity, checked));
        content.addView(enable);

        TextView note = styledText(activity, R.string.vgp_note, 12f,
                R.font.rajdhani_regular, R.color.forza_text_dim);
        content.addView(note);

        // Opacity: 20%..100%.
        addSlider(activity, content, R.string.vgp_opacity,
                Math.round(VirtualGamepadPrefs.getOpacity(activity) * 100f), 20, 100,
                value -> {
                    VirtualGamepadPrefs.setOpacity(activity, value / 100f);
                    if (live) {
                        overlay.applyPrefs();
                    }
                });

        // Control size: 60%..160%.
        addSlider(activity, content, R.string.vgp_size,
                Math.round(VirtualGamepadPrefs.getScale(activity) * 100f), 60, 160,
                value -> {
                    VirtualGamepadPrefs.setScale(activity, value / 100f);
                    if (live) {
                        overlay.applyPrefs();
                    }
                });

        // Stick dead zone: 0%..35% of the stick radius.
        addSlider(activity, content, R.string.vgp_deadzone,
                Math.round(VirtualGamepadPrefs.getDeadzone(activity) * 100f), 0, 35,
                value -> {
                    VirtualGamepadPrefs.setDeadzone(activity, value / 100f);
                    if (live) {
                        overlay.applyPrefs();
                    }
                });

        final CheckBox haptics = styledCheck(activity, R.string.vgp_haptics);
        haptics.setChecked(VirtualGamepadPrefs.getHaptics(activity));
        haptics.setOnCheckedChangeListener((button, checked) ->
                VirtualGamepadPrefs.setHaptics(activity, checked));
        content.addView(haptics);

        // Layout editing is only meaningful over the running game.
        final Button edit = new Button(activity);
        edit.setAllCaps(false);
        setForzaTypeface(activity, edit, R.font.rajdhani_bold);
        if (live) {
            edit.setText(overlay.isEditMode()
                    ? R.string.vgp_done_layout : R.string.vgp_edit_layout);
            edit.setOnClickListener(v -> {
                boolean entering = !overlay.isEditMode();
                overlay.setEditMode(entering);
                edit.setText(entering
                        ? R.string.vgp_done_layout : R.string.vgp_edit_layout);
            });
            content.addView(edit);

            final Button reset = new Button(activity);
            reset.setAllCaps(false);
            setForzaTypeface(activity, reset, R.font.rajdhani_bold);
            reset.setText(R.string.vgp_reset_layout);
            reset.setOnClickListener(v -> {
                overlay.resetLayout();
                Toast.makeText(activity, R.string.vgp_layout_reset, Toast.LENGTH_SHORT).show();
            });
            content.addView(reset);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.vgp_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (live && overlay.isEditMode()) {
                        overlay.setEditMode(false);
                    }
                    if (activity instanceof PinyonActivity) {
                        // Rebuild or remove the in-game UI to match the new
                        // master switch (no-op when nothing changed).
                        ((PinyonActivity) activity).onVirtualGamepadEnabledChanged();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    // Sliders applied live: roll everything back to the
                    // values saved when the dialog opened.
                    VirtualGamepadPrefs.setEnabled(activity, originalEnabled);
                    VirtualGamepadPrefs.setOpacity(activity, originalOpacity);
                    VirtualGamepadPrefs.setScale(activity, originalScale);
                    VirtualGamepadPrefs.setDeadzone(activity, originalDeadzone);
                    VirtualGamepadPrefs.setHaptics(activity, originalHaptics);
                    if (live) {
                        overlay.applyPrefs();
                        if (overlay.isEditMode()) {
                            overlay.setEditMode(false);
                        }
                    }
                })
                .show();
    }

    private interface SliderCallback {
        void onValue(int value);
    }

    private static void addSlider(Activity activity, LinearLayout content, int labelRes,
            int initial, int min, int max, SliderCallback callback) {
        int pad = (int) (6 * activity.getResources().getDisplayMetrics().density);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, pad, 0, 0);

        TextView label = styledText(activity, labelRes, 14f,
                R.font.rajdhani_semibold, R.color.forza_text_primary);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = styledText(activity, R.string.vgp_percent_format, 14f,
                R.font.rajdhani_bold, R.color.forza_yellow);
        row.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(row);

        SeekBar seek = new SeekBar(activity);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(initial);
        seek.setKeyProgressIncrement(1);
        value.setText(activity.getString(R.string.vgp_percent_format, initial));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(activity.getString(R.string.vgp_percent_format, progress));
                callback.onValue(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        content.addView(seek);
    }

    private static CheckBox styledCheck(Activity activity, int textRes) {
        CheckBox check = new CheckBox(activity);
        check.setText(textRes);
        check.setButtonTintList(ColorStateList.valueOf(ResourcesCompat.getColor(
                activity.getResources(), R.color.forza_yellow, activity.getTheme())));
        setForzaTypeface(activity, check, R.font.rajdhani_medium);
        return check;
    }

    private static TextView styledText(Activity activity, int textRes, float sizeSp,
            int fontRes, int colorRes) {
        TextView text = new TextView(activity);
        text.setText(textRes);
        text.setTextSize(sizeSp);
        text.setTextColor(ResourcesCompat.getColor(activity.getResources(),
                colorRes, activity.getTheme()));
        setForzaTypeface(activity, text, fontRes);
        return text;
    }

    private static void setForzaTypeface(Activity activity, TextView view, int fontRes) {
        android.graphics.Typeface face = ResourcesCompat.getFont(activity, fontRes);
        if (face != null) {
            view.setTypeface(face);
        }
    }
}
