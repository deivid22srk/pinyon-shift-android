package dev.pinyon.shift;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The on-screen virtual gamepad: one custom view that draws every control on
 * a Canvas and translates multi-touch input into SDL3 gamepad events through
 * the JNI bridge on PinyonActivity (nativeVirtualGamepad*).
 *
 * Touch handling: each pointer is bound to at most one control (by pointer
 * id) for the whole gesture. A gesture that starts on empty overlay space
 * returns false from onTouchEvent, so the event falls through to the SDL
 * surface underneath and the game receives it untouched. A gesture that
 * starts on a control owns that control until every finger lifts.
 *
 * Mapping conventions (see android_virtual_gamepad.cpp): buttons use the
 * SDL_GamepadButton indices 0..15; sticks send -32768..32767 with 0 center;
 * triggers idle at -32768 (full joystick range) and ramp linearly through
 * the touch, giving the game a progressive throttle instead of an on/off
 * pedal.
 *
 * The visual style follows the Forza launcher: graphite fills, yellow
 * accents, cyan active stick, chamfered triggers, Rajdhani labels. Control
 * positions are stored as fractions of the safe (notch-free) area, so a
 * layout saved on one device still lands sanely on another. All settings
 * live in VirtualGamepadPrefs and survive process death.
 */
final class VirtualGamepadOverlay extends View {

    /** Control ids; also the index into the saved-layout JSON. */
    static final int STICK_L = 0;
    static final int STICK_R = 1;
    static final int BTN_A = 2;
    static final int BTN_B = 3;
    static final int BTN_X = 4;
    static final int BTN_Y = 5;
    static final int DPAD = 6;
    static final int LB = 7;
    static final int RB = 8;
    static final int LT = 9;
    static final int RT = 10;
    static final int BACK = 11;
    static final int START = 12;
    static final int L3 = 13;
    static final int R3 = 14;
    static final int CONTROL_COUNT = 15;

    /** SDL_GamepadButton indices (SDL 3.4 standard layout). */
    private static final int SDL_A = 0;
    private static final int SDL_B = 1;
    private static final int SDL_X = 2;
    private static final int SDL_Y = 3;
    private static final int SDL_BACK = 4;
    private static final int SDL_START = 6;
    private static final int SDL_L3 = 7;
    private static final int SDL_R3 = 8;
    private static final int SDL_LB = 9;
    private static final int SDL_RB = 10;
    private static final int SDL_DPAD_UP = 11;
    private static final int SDL_DPAD_DOWN = 12;
    private static final int SDL_DPAD_LEFT = 13;
    private static final int SDL_DPAD_RIGHT = 14;

    /** SDL_GamepadAxis indices. */
    private static final int AXIS_LEFT_X = 0;
    private static final int AXIS_LEFT_Y = 1;
    private static final int AXIS_RIGHT_X = 2;
    private static final int AXIS_RIGHT_Y = 3;
    private static final int AXIS_TRIGGER_L = 4;
    private static final int AXIS_TRIGGER_R = 5;

    /** Default control anchors as fractions of the safe area. */
    private static final float[][] DEFAULT_POSITIONS = {
            {0.080f, 0.740f},  // STICK_L
            {0.750f, 0.800f},  // STICK_R
            {0.885f, 0.635f},  // BTN_A
            {0.955f, 0.565f},  // BTN_B
            {0.815f, 0.565f},  // BTN_X
            {0.885f, 0.495f},  // BTN_Y
            {0.245f, 0.860f},  // DPAD
            {0.120f, 0.300f},  // LB
            {0.880f, 0.300f},  // RB
            {0.120f, 0.120f},  // LT
            {0.880f, 0.120f},  // RT
            {0.420f, 0.115f},  // BACK
            {0.580f, 0.115f},  // START
            {0.185f, 0.600f},  // L3
            {0.665f, 0.640f},  // R3
    };

    /** Listener for coarse events (activity logging / dialog sync). */
    interface Listener {
        void onOverlayVisibilityChanged(boolean visible);
    }

    private final float density;

    /** Saved control fractions (defaults or user-dragged; never recomputed). */
    private final float[][] positions = new float[CONTROL_COUNT][2];
    /** Pixel anchors derived from positions + safe insets + view size. */
    private final float[] anchorX = new float[CONTROL_COUNT];
    private final float[] anchorY = new float[CONTROL_COUNT];
    /** bound pointerId per control (-1 none): the multi-touch registry. */
    private final int[] controlPointer = new int[CONTROL_COUNT];
    private final float[] lastSentAxis = new float[6];
    private final ValueAnimator[] triggerAnimators = new ValueAnimator[2];
    private final ValueAnimator[] stickSprings = new ValueAnimator[2];
    private final float[] triggerValue = new float[2];  // 0..1 analog ramp
    private final float[][] stickRaw = new float[2][2]; // -1..1 (x, y)

    // Style (cached: nothing is allocated during drawing).
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scratchPath = new Path();
    private final RectF scratchRect = new RectF();

    private int colorFillDark;
    private int colorStroke;
    private int colorYellow;
    private int colorCyan;
    private int colorText;
    private final int colorFaceA = Color.rgb(0x6F, 0xBF, 0x44);
    private final int colorFaceB = Color.rgb(0xE4, 0x49, 0x4C);
    private final int colorFaceX = Color.rgb(0x3E, 0x86, 0xE0);
    private final int colorFaceY = Color.rgb(0xF0, 0xC9, 0x3C);

    private float opacity = VirtualGamepadPrefs.OPACITY_DEFAULT;
    private float scale = VirtualGamepadPrefs.SCALE_DEFAULT;
    private float deadzone = VirtualGamepadPrefs.DEADZONE_DEFAULT;
    private boolean hapticsEnabled = true;

    private boolean overlayVisible = false;
    private boolean editMode = false;
    private int dragTarget = -1;
    private int editPointerId = -1;
    private int touchDpadArm = -1; // SDL button held by the current dpad gesture
    private final int[] safeInsets = {0, 0, 0, 0};
    private Listener listener;

    VirtualGamepadOverlay(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        loadStyle();
        loadSavedPositions();
        applyPrefs();
        setOnApplyWindowInsetsListener((view, insets) -> {
            safeInsets[0] = insets.getSystemWindowInsetLeft();
            safeInsets[1] = insets.getSystemWindowInsetTop();
            safeInsets[2] = insets.getSystemWindowInsetRight();
            safeInsets[3] = insets.getSystemWindowInsetBottom();
            computeAnchors();
            return insets;
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------- settings

    /** Re-reads opacity, scale and dead zone from the preferences. */
    void applyPrefs() {
        opacity = VirtualGamepadPrefs.getOpacity(getContext());
        scale = VirtualGamepadPrefs.getScale(getContext());
        deadzone = VirtualGamepadPrefs.getDeadzone(getContext());
        hapticsEnabled = VirtualGamepadPrefs.getHaptics(getContext());
        computeAnchors();
        invalidate();
    }

    boolean isOverlayVisible() {
        return overlayVisible;
    }

    boolean isEditMode() {
        return editMode;
    }

    /** Shows or hides the overlay with a short fade (the Forza entrance). */
    void setOverlayVisible(final boolean visible, boolean animate) {
        if (overlayVisible == visible) {
            return;
        }
        overlayVisible = visible;
        if (!visible) {
            releaseEverything();
            cancelAnimations();
            if (editMode) {
                // Hiding mid-edit commits what was dragged so far.
                editMode = false;
                dragTarget = -1;
                editPointerId = -1;
                VirtualGamepadPrefs.saveLayout(getContext(), collectLayout());
            }
        } else {
            // The game is already running when the overlay shows: attach the
            // SDL virtual device right away (the first input event and the
            // activity retry timer cover the not-initialized-yet case).
            PinyonActivity.nativeVirtualGamepadAttach();
        }
        animate().cancel();
        if (animate) {
            if (visible) {
                setVisibility(VISIBLE);
                setAlpha(0f);
            }
            animate().alpha(visible ? 1f : 0f)
                    .setDuration(190L)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(() -> {
                        if (!overlayVisible) {
                            setVisibility(GONE);
                        }
                    })
                    .start();
        } else {
            setAlpha(visible ? 1f : 0f);
            setVisibility(visible ? VISIBLE : GONE);
        }
        if (listener != null) {
            listener.onOverlayVisibilityChanged(visible);
        }
    }

    /** Enters/exits layout editing. Positions are saved on exit. */
    void setEditMode(boolean enabled) {
        if (editMode == enabled) {
            return;
        }
        editMode = enabled;
        dragTarget = -1;
        editPointerId = -1;
        if (!enabled) {
            VirtualGamepadPrefs.saveLayout(getContext(), collectLayout());
        }
        if (!overlayVisible) {
            setOverlayVisible(true, true);
        }
        invalidate();
    }

    /** Restores the built-in control positions and leaves them saved. */
    void resetLayout() {
        VirtualGamepadPrefs.resetLayout(getContext());
        loadSavedPositions();
        computeAnchors();
        invalidate();
    }

    // -------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        computeAnchors();
    }

    private void loadSavedPositions() {
        JSONObject saved = VirtualGamepadPrefs.getLayout(getContext());
        for (int i = 0; i < CONTROL_COUNT; i++) {
            positions[i][0] = DEFAULT_POSITIONS[i][0];
            positions[i][1] = DEFAULT_POSITIONS[i][1];
            JSONArray entry = saved.optJSONArray(String.valueOf(i));
            if (entry != null && entry.length() == 2) {
                positions[i][0] = clamp((float) entry.optDouble(0, positions[i][0]), 0.02f, 0.98f);
                positions[i][1] = clamp((float) entry.optDouble(1, positions[i][1]), 0.02f, 0.98f);
            }
        }
    }

    private JSONObject collectLayout() {
        JSONObject saved = new JSONObject();
        try {
            for (int i = 0; i < CONTROL_COUNT; i++) {
                JSONArray entry = new JSONArray();
                entry.put((double) positions[i][0]);
                entry.put((double) positions[i][1]);
                saved.put(String.valueOf(i), entry);
            }
        } catch (Exception e) {
            return new JSONObject();
        }
        return saved;
    }

    /** Pixels for every control anchor: fractions mapped into the safe area. */
    private void computeAnchors() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int left = safeInsets[0];
        int top = safeInsets[1];
        int right = width - safeInsets[2];
        int bottom = height - safeInsets[3];
        for (int i = 0; i < CONTROL_COUNT; i++) {
            anchorX[i] = left + positions[i][0] * (right - left);
            anchorY[i] = top + positions[i][1] * (bottom - top);
        }
    }

    // --------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!overlayVisible) {
            return false;
        }
        if (editMode) {
            return handleEditTouch(event);
        }

        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                boolean consumedAny = false;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointerId = event.getPointerId(i);
                    if (isPointerBound(pointerId)) {
                        continue;
                    }
                    int control = hitControl(event.getX(i), event.getY(i));
                    if (control >= 0 && controlPointer[control] == -1) {
                        controlPointer[control] = pointerId;
                        pressControl(control, event.getX(i), event.getY(i));
                        consumedAny = true;
                    }
                }
                // Returning false (the initial down hit nothing) lets the
                // whole gesture fall through to the game.
                return consumedAny;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int control = 0; control < CONTROL_COUNT; control++) {
                    int pointerId = controlPointer[control];
                    if (pointerId == -1) {
                        continue;
                    }
                    int index = event.findPointerIndex(pointerId);
                    if (index >= 0) {
                        moveControl(control, event.getX(index), event.getY(index));
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerId = event.getPointerId(event.getActionIndex());
                releasePointer(pointerId);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                releasePointer(event.getPointerId(event.getActionIndex()));
                releaseAllPointers();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                releaseEverything();
                releaseAllPointers();
                return true;
            }
            default:
                return true;
        }
    }

    private boolean isPointerBound(int pointerId) {
        for (int control = 0; control < CONTROL_COUNT; control++) {
            if (controlPointer[control] == pointerId) {
                return true;
            }
        }
        return false;
    }

    private void releasePointer(int pointerId) {
        for (int control = 0; control < CONTROL_COUNT; control++) {
            if (controlPointer[control] == pointerId) {
                releaseControl(control);
                controlPointer[control] = -1;
            }
        }
    }

    private void releaseAllPointers() {
        for (int control = 0; control < CONTROL_COUNT; control++) {
            if (controlPointer[control] != -1) {
                releaseControl(control);
                controlPointer[control] = -1;
            }
        }
    }

    /** Edit mode: drag the nearest control anywhere; nothing reaches the game. */
    private boolean handleEditTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                editPointerId = event.getPointerId(0);
                dragTarget = nearestControl(event.getX(0), event.getY(0));
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (dragTarget < 0) {
                    int index = event.getActionIndex();
                    editPointerId = event.getPointerId(index);
                    dragTarget = nearestControl(event.getX(index), event.getY(index));
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragTarget >= 0 && editPointerId >= 0) {
                    int index = event.findPointerIndex(editPointerId);
                    if (index >= 0) {
                        moveDragTarget(event.getX(index), event.getY(index));
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (event.getPointerId(event.getActionIndex()) == editPointerId) {
                    dragTarget = -1;
                    editPointerId = -1;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragTarget = -1;
                editPointerId = -1;
                return true;
            default:
                return true;
        }
    }

    private void moveDragTarget(float x, float y) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || dragTarget < 0) {
            return;
        }
        int left = safeInsets[0];
        int top = safeInsets[1];
        int right = width - safeInsets[2];
        int bottom = height - safeInsets[3];
        float spanX = Math.max(1f, right - left);
        float spanY = Math.max(1f, bottom - top);
        positions[dragTarget][0] = clamp((x - left) / spanX, 0.02f, 0.98f);
        positions[dragTarget][1] = clamp((y - top) / spanY, 0.02f, 0.98f);
        anchorX[dragTarget] = left + positions[dragTarget][0] * spanX;
        anchorY[dragTarget] = top + positions[dragTarget][1] * spanY;
        invalidate();
    }

    // ------------------------------------------------------------ controls

    private void pressControl(int control, float x, float y) {
        switch (control) {
            case STICK_L:
            case STICK_R:
                cancelStickSpring(control == STICK_L ? 0 : 1);
                moveControl(control, x, y);
                break;
            case DPAD:
                touchDpadArm = hitDpadArm(x, y);
                if (touchDpadArm >= 0) {
                    PinyonActivity.nativeVirtualGamepadButton(touchDpadArm, true);
                }
                break;
            case BTN_A:
            case BTN_B:
            case BTN_X:
            case BTN_Y:
            case LB:
            case RB:
            case BACK:
            case START:
            case L3:
            case R3:
                PinyonActivity.nativeVirtualGamepadButton(sdlButtonOf(control), true);
                break;
            case LT:
            case RT:
                animateTrigger(control == LT ? 0 : 1, 1f, 140L);
                break;
            default:
                break;
        }
        if (hapticsEnabled) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        invalidate();
    }

    private void moveControl(int control, float x, float y) {
        if (control != STICK_L && control != STICK_R) {
            return; // buttons/triggers keep their state while held
        }
        int stick = control == STICK_L ? 0 : 1;
        float outer = stickOuterRadius();
        float dx = x - anchorX[control];
        float dy = y - anchorY[control];
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float maxDistance = outer * 0.82f;
        if (distance > maxDistance && distance > 0f) {
            dx = dx / distance * maxDistance;
            dy = dy / distance * maxDistance;
            distance = maxDistance;
        }
        stickRaw[stick][0] = distance > 0f ? dx / maxDistance : 0f;
        stickRaw[stick][1] = distance > 0f ? dy / maxDistance : 0f;
        sendStickAxis(stick);
        invalidate();
    }

    private void releaseControl(int control) {
        switch (control) {
            case STICK_L:
            case STICK_R:
                animateStickSpring(control == STICK_L ? 0 : 1);
                break;
            case DPAD:
                if (touchDpadArm >= 0) {
                    PinyonActivity.nativeVirtualGamepadButton(touchDpadArm, false);
                    touchDpadArm = -1;
                }
                break;
            case BTN_A:
            case BTN_B:
            case BTN_X:
            case BTN_Y:
            case LB:
            case RB:
            case BACK:
            case START:
            case L3:
            case R3:
                PinyonActivity.nativeVirtualGamepadButton(sdlButtonOf(control), false);
                break;
            case LT:
            case RT:
                animateTrigger(control == LT ? 0 : 1, 0f, 110L);
                break;
            default:
                break;
        }
        invalidate();
    }

    /** Stops every held control (pause/destroy, gesture cancellation). */
    void releaseEverything() {
        for (int stick = 0; stick < 2; stick++) {
            stickRaw[stick][0] = 0f;
            stickRaw[stick][1] = 0f;
            sendStickAxis(stick);
            cancelStickSpring(stick);
        }
        for (int trigger = 0; trigger < 2; trigger++) {
            triggerValue[trigger] = 0f;
            sendTriggerAxis(trigger);
            if (triggerAnimators[trigger] != null) {
                triggerAnimators[trigger].cancel();
                triggerAnimators[trigger] = null;
            }
        }
        if (touchDpadArm >= 0) {
            PinyonActivity.nativeVirtualGamepadButton(touchDpadArm, false);
            touchDpadArm = -1;
        }
        for (int control = 0; control < CONTROL_COUNT; control++) {
            if (control == STICK_L || control == STICK_R
                    || control == DPAD || control == LT || control == RT) {
                continue; // handled above
            }
            PinyonActivity.nativeVirtualGamepadButton(sdlButtonOf(control), false);
        }
        invalidate();
    }

    private void cancelAnimations() {
        for (int i = 0; i < 2; i++) {
            cancelStickSpring(i);
            if (triggerAnimators[i] != null) {
                triggerAnimators[i].cancel();
                triggerAnimators[i] = null;
            }
        }
    }

    // ------------------------------------------------- native event helpers

    private static int sdlButtonOf(int control) {
        switch (control) {
            case BTN_A: return SDL_A;
            case BTN_B: return SDL_B;
            case BTN_X: return SDL_X;
            case BTN_Y: return SDL_Y;
            case BACK: return SDL_BACK;
            case START: return SDL_START;
            case L3: return SDL_L3;
            case R3: return SDL_R3;
            case LB: return SDL_LB;
            case RB: return SDL_RB;
            default: return -1;
        }
    }

    private void sendStickAxis(int stick) {
        float nx = stickRaw[stick][0];
        float ny = stickRaw[stick][1];
        float magnitude = (float) Math.sqrt(nx * nx + ny * ny);
        float outX;
        float outY;
        if (magnitude <= deadzone || magnitude <= 0f) {
            outX = 0f;
            outY = 0f;
        } else {
            float rescaled = (magnitude - deadzone) / (1f - deadzone);
            outX = nx / magnitude * rescaled;
            outY = ny / magnitude * rescaled;
        }
        int base = stick == 0 ? AXIS_LEFT_X : AXIS_RIGHT_X;
        sendAxis(base, outX);
        sendAxis(base + 1, outY);
    }

    private void sendAxis(int axis, float value) {
        if (Math.abs(value - lastSentAxis[axis]) < 0.008f && value != 0f) {
            return; // below the send threshold; keeps the JNI traffic low
        }
        lastSentAxis[axis] = value;
        PinyonActivity.nativeVirtualGamepadAxis(axis,
                (short) clamp(value * 32767f, -32768f, 32767f));
    }

    private void sendTriggerAxis(int trigger) {
        int axis = trigger == 0 ? AXIS_TRIGGER_L : AXIS_TRIGGER_R;
        lastSentAxis[axis] = triggerValue[trigger];
        // Full joystick range: idle is -32768 so the gamepad layer reads 0.
        PinyonActivity.nativeVirtualGamepadAxis(axis,
                (short) (-32768f + clamp(triggerValue[trigger], 0f, 1f) * 65534f));
    }

    private void animateTrigger(final int trigger, float target, long duration) {
        if (triggerAnimators[trigger] != null) {
            triggerAnimators[trigger].cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(triggerValue[trigger], target);
        animator.setDuration(duration);
        animator.setInterpolator(target > triggerValue[trigger]
                ? new DecelerateInterpolator(2f) : new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            triggerValue[trigger] = (Float) animation.getAnimatedValue();
            sendTriggerAxis(trigger);
            invalidate();
        });
        animator.start();
        triggerAnimators[trigger] = animator;
    }

    private void animateStickSpring(final int stick) {
        cancelStickSpring(stick);
        final float fromX = stickRaw[stick][0];
        final float fromY = stickRaw[stick][1];
        if (fromX == 0f && fromY == 0f) {
            return;
        }
        ValueAnimator spring = ValueAnimator.ofFloat(1f, 0f);
        spring.setDuration(110L);
        spring.setInterpolator(new DecelerateInterpolator(1.2f));
        spring.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            stickRaw[stick][0] = fromX * progress;
            stickRaw[stick][1] = fromY * progress;
            sendStickAxis(stick);
            invalidate();
        });
        spring.start();
        stickSprings[stick] = spring;
    }

    private void cancelStickSpring(int stick) {
        if (stickSprings[stick] != null) {
            stickSprings[stick].cancel();
            stickSprings[stick] = null;
        }
    }

    // ----------------------------------------------------------- hit tests

    private float stickOuterRadius() {
        return 0.10f * getHeight() * scale;
    }

    /** Which control covers the point (-1 none); nearest-center wins. */
    private int hitControl(float x, float y) {
        int best = -1;
        float bestScore = 1f;
        for (int control = 0; control < CONTROL_COUNT; control++) {
            float radius = hitRadius(control);
            float dx = x - anchorX[control];
            float dy = y - anchorY[control];
            float score = (float) Math.sqrt(dx * dx + dy * dy) / radius;
            if (score <= bestScore) {
                bestScore = score;
                best = control;
            }
        }
        if (best != DPAD && hitDpadArm(x, y) >= 0) {
            // The dpad arms extend past its grab circle; an arm touch counts
            // only when no closer control claimed the point.
            best = DPAD;
        }
        return best;
    }

    private int nearestControl(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int control = 0; control < CONTROL_COUNT; control++) {
            float dx = x - anchorX[control];
            float dy = y - anchorY[control];
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = control;
            }
        }
        return best;
    }

    /** Grab radius per control, with generous slop for thumbs. */
    private float hitRadius(int control) {
        float unit = getHeight() * scale;
        float slop = 12f * density;
        switch (control) {
            case STICK_L:
            case STICK_R:
                return stickOuterRadius() * 1.35f + slop;
            case BTN_A:
            case BTN_B:
            case BTN_X:
            case BTN_Y:
                return 0.052f * unit * 0.5f + slop;
            case L3:
            case R3:
                return 0.034f * unit * 0.5f + slop;
            case LB:
            case RB:
                return 0.055f * unit * 0.5f + slop;
            case LT:
            case RT:
                return 0.065f * unit * 0.5f + slop;
            case BACK:
            case START:
                return 0.042f * unit * 0.5f + slop;
            case DPAD:
                return 0.075f * unit * 0.5f + slop; // center; arms checked separately
            default:
                return slop;
        }
    }

    /** Which dpad arm covers the point (SDL button index, -1 none). */
    private int hitDpadArm(float x, float y) {
        float unit = getHeight() * scale;
        float cx = anchorX[DPAD];
        float cy = anchorY[DPAD];
        float armLength = 0.115f * unit;
        float armWidth = 0.070f * unit;
        float dx = x - cx;
        float dy = y - cy;
        if (Math.abs(dx) <= armWidth / 2f && Math.abs(dy) <= armLength) {
            return dy < 0 ? SDL_DPAD_UP : SDL_DPAD_DOWN;
        }
        if (Math.abs(dy) <= armWidth / 2f && Math.abs(dx) <= armLength) {
            return dx < 0 ? SDL_DPAD_LEFT : SDL_DPAD_RIGHT;
        }
        return -1;
    }

    // -------------------------------------------------------------- drawing

    private void loadStyle() {
        colorFillDark = getResources().getColor(R.color.forza_background, null);
        colorStroke = getResources().getColor(R.color.forza_stroke, null);
        colorYellow = getResources().getColor(R.color.forza_yellow, null);
        colorCyan = getResources().getColor(R.color.forza_cyan, null);
        colorText = getResources().getColor(R.color.forza_text_primary, null);

        Typeface label = ResourcesCompat.getFont(getContext(), R.font.rajdhani_bold);
        labelPaint.setTypeface(label);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!overlayVisible) {
            return;
        }
        final float baseAlpha = editMode ? 1f : opacity;
        for (int control = 0; control < CONTROL_COUNT; control++) {
            drawControl(canvas, control, baseAlpha);
        }
        if (editMode) {
            drawEditChrome(canvas);
        }
    }

    private void drawControl(Canvas canvas, int control, float baseAlpha) {
        switch (control) {
            case STICK_L:
            case STICK_R:
                drawStick(canvas, control, baseAlpha);
                break;
            case BTN_A:
            case BTN_B:
            case BTN_X:
            case BTN_Y:
                drawFaceButton(canvas, control, baseAlpha);
                break;
            case DPAD:
                drawDpad(canvas, baseAlpha);
                break;
            case LB:
            case RB:
                drawBumper(canvas, control, baseAlpha);
                break;
            case LT:
            case RT:
                drawTrigger(canvas, control, baseAlpha);
                break;
            case BACK:
            case START:
                drawSmallButton(canvas, control, baseAlpha);
                break;
            case L3:
            case R3:
                drawStickClick(canvas, control, baseAlpha);
                break;
            default:
                break;
        }
    }

    private boolean isPressed(int control) {
        return controlPointer[control] != -1;
    }

    private void drawStick(Canvas canvas, int control, float baseAlpha) {
        int stick = control == STICK_L ? 0 : 1;
        float cx = anchorX[control];
        float cy = anchorY[control];
        float outer = stickOuterRadius();
        float knob = outer * 0.44f;
        float active = Math.min(1f, (float) Math.sqrt(
                stickRaw[stick][0] * stickRaw[stick][0]
                        + stickRaw[stick][1] * stickRaw[stick][1]));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(colorFillDark, 0.62f * baseAlpha));
        canvas.drawCircle(cx, cy, outer, fillPaint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.6f * density);
        strokePaint.setColor(applyAlpha(colorStroke, baseAlpha));
        canvas.drawCircle(cx, cy, outer, strokePaint);

        // Ring ticks (racing-dashboard feel).
        strokePaint.setStrokeWidth(1.2f * density);
        strokePaint.setColor(applyAlpha(colorStroke, 0.8f * baseAlpha));
        for (int i = 0; i < 4; i++) {
            double angle = Math.toRadians(45 + i * 90);
            float x1 = cx + (float) Math.cos(angle) * (outer * 0.86f);
            float y1 = cy + (float) Math.sin(angle) * (outer * 0.86f);
            float x2 = cx + (float) Math.cos(angle) * outer;
            float y2 = cy + (float) Math.sin(angle) * outer;
            canvas.drawLine(x1, y1, x2, y2, strokePaint);
        }

        // Knob, drawn at the raw (pre-deadzone) position for honest feedback.
        float knobX = cx + stickRaw[stick][0] * outer * 0.82f;
        float knobY = cy + stickRaw[stick][1] * outer * 0.82f;
        boolean engaged = active > 0.02f;
        fillPaint.setColor(applyAlpha(engaged ? colorCyan : colorFillDark,
                (engaged ? 0.85f : 0.92f) * baseAlpha));
        canvas.drawCircle(knobX, knobY, knob, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.8f * density);
        strokePaint.setColor(applyAlpha(engaged ? colorCyan : colorYellow,
                0.9f * baseAlpha));
        canvas.drawCircle(knobX, knobY, knob, strokePaint);
    }

    private void drawFaceButton(Canvas canvas, int control, float baseAlpha) {
        float radius = 0.052f * getHeight() * scale * 0.5f;
        float cx = anchorX[control];
        float cy = anchorY[control];
        int ring = control == BTN_A ? colorFaceA : control == BTN_B ? colorFaceB
                : control == BTN_X ? colorFaceX : colorFaceY;
        boolean pressed = isPressed(control);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(pressed ? ring : colorFillDark,
                (pressed ? 0.88f : 0.62f) * baseAlpha));
        canvas.drawCircle(cx, cy, radius, fillPaint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.8f * density);
        strokePaint.setColor(applyAlpha(ring, (pressed ? 1f : 0.85f) * baseAlpha));
        canvas.drawCircle(cx, cy, radius, strokePaint);

        labelPaint.setColor(applyAlpha(pressed ? Color.BLACK : colorText, baseAlpha));
        labelPaint.setTextSize(radius * 0.95f);
        canvas.drawText(control == BTN_A ? "A" : control == BTN_B ? "B"
                : control == BTN_X ? "X" : "Y", cx, cy + radius * 0.34f, labelPaint);
    }

    private void drawDpad(Canvas canvas, float baseAlpha) {
        float unit = getHeight() * scale;
        float cx = anchorX[DPAD];
        float cy = anchorY[DPAD];
        float armLength = 0.115f * unit;
        float armWidth = 0.070f * unit;
        int held = isPressed(DPAD) ? touchDpadArm : -1;

        scratchPath.reset();
        scratchPath.addRoundRect(cx - armWidth / 2f, cy - armLength,
                cx + armWidth / 2f, cy + armLength, 5f * density, 5f * density,
                Path.Direction.CW);
        scratchPath.addRoundRect(cx - armLength, cy - armWidth / 2f,
                cx + armLength, cy + armWidth / 2f, 5f * density, 5f * density,
                Path.Direction.CW);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(colorFillDark, 0.62f * baseAlpha));
        canvas.drawPath(scratchPath, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.6f * density);
        strokePaint.setColor(applyAlpha(colorStroke, baseAlpha));
        canvas.drawPath(scratchPath, strokePaint);

        // Chevron on each arm, yellow while that arm is held.
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(2.2f * density);
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        drawDpadChevron(canvas, cx, cy - armLength * 0.55f, 0, held == SDL_DPAD_UP, baseAlpha);
        drawDpadChevron(canvas, cx, cy + armLength * 0.55f, 2, held == SDL_DPAD_DOWN, baseAlpha);
        drawDpadChevron(canvas, cx - armLength * 0.55f, cy, 3, held == SDL_DPAD_LEFT, baseAlpha);
        drawDpadChevron(canvas, cx + armLength * 0.55f, cy, 1, held == SDL_DPAD_RIGHT, baseAlpha);
    }

    private void drawDpadChevron(Canvas canvas, float cx, float cy,
            int direction, boolean held, float baseAlpha) {
        accentPaint.setColor(applyAlpha(held ? colorYellow : colorStroke, baseAlpha));
        float size = 3.4f * density;
        if (direction == 0) { // up
            canvas.drawLine(cx - size, cy + size * 0.6f, cx, cy - size * 0.6f, accentPaint);
            canvas.drawLine(cx, cy - size * 0.6f, cx + size, cy + size * 0.6f, accentPaint);
        } else if (direction == 2) { // down
            canvas.drawLine(cx - size, cy - size * 0.6f, cx, cy + size * 0.6f, accentPaint);
            canvas.drawLine(cx, cy + size * 0.6f, cx + size, cy - size * 0.6f, accentPaint);
        } else if (direction == 3) { // left
            canvas.drawLine(cx + size * 0.6f, cy - size, cx - size * 0.6f, cy, accentPaint);
            canvas.drawLine(cx - size * 0.6f, cy, cx + size * 0.6f, cy + size, accentPaint);
        } else { // right
            canvas.drawLine(cx - size * 0.6f, cy - size, cx + size * 0.6f, cy, accentPaint);
            canvas.drawLine(cx + size * 0.6f, cy, cx - size * 0.6f, cy + size, accentPaint);
        }
    }

    private void drawBumper(Canvas canvas, int control, float baseAlpha) {
        float unit = getHeight() * scale;
        float width = 0.11f * unit;
        float height = 0.048f * unit;
        float cx = anchorX[control];
        float cy = anchorY[control];
        boolean pressed = isPressed(control);

        scratchRect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(colorFillDark, (pressed ? 0.85f : 0.62f) * baseAlpha));
        canvas.drawRoundRect(scratchRect, 4f * density, 4f * density, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.8f * density);
        strokePaint.setColor(applyAlpha(pressed ? colorYellow : colorStroke, baseAlpha));
        canvas.drawRoundRect(scratchRect, 4f * density, 4f * density, strokePaint);

        labelPaint.setColor(applyAlpha(pressed ? colorYellow : colorText, baseAlpha));
        labelPaint.setTextSize(height * 0.48f);
        canvas.drawText(control == LB ? "LB" : "RB", cx, cy + height * 0.17f, labelPaint);
    }

    private void drawTrigger(Canvas canvas, int control, float baseAlpha) {
        float unit = getHeight() * scale;
        float width = 0.11f * unit;
        float height = 0.065f * unit;
        float cx = anchorX[control];
        float cy = anchorY[control];
        int trigger = control == LT ? 0 : 1;
        float value = triggerValue[trigger];

        // Chamfered slab (Forza angular language).
        float chamfer = width * 0.18f;
        scratchPath.reset();
        if (control == LT) {
            scratchPath.moveTo(cx - width / 2f, cy - height / 2f + chamfer);
            scratchPath.lineTo(cx - width / 2f + chamfer, cy - height / 2f);
            scratchPath.lineTo(cx + width / 2f, cy - height / 2f);
            scratchPath.lineTo(cx + width / 2f, cy + height / 2f);
            scratchPath.lineTo(cx - width / 2f, cy + height / 2f);
        } else {
            scratchPath.moveTo(cx + width / 2f, cy - height / 2f + chamfer);
            scratchPath.lineTo(cx + width / 2f - chamfer, cy - height / 2f);
            scratchPath.lineTo(cx - width / 2f, cy - height / 2f);
            scratchPath.lineTo(cx - width / 2f, cy + height / 2f);
            scratchPath.lineTo(cx + width / 2f, cy + height / 2f);
        }
        scratchPath.close();

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(colorFillDark, 0.62f * baseAlpha));
        canvas.drawPath(scratchPath, fillPaint);
        // Analog fill: how deep the pedal is pressed.
        if (value > 0.01f) {
            canvas.save();
            canvas.clipRect(cx - width / 2f, cy + height / 2f - height * value,
                    cx + width / 2f, cy + height / 2f);
            fillPaint.setColor(applyAlpha(colorYellow, 0.34f * baseAlpha));
            canvas.drawPath(scratchPath, fillPaint);
            canvas.restore();
        }
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.8f * density);
        strokePaint.setColor(applyAlpha(value > 0.02f ? colorYellow : colorStroke, baseAlpha));
        canvas.drawPath(scratchPath, strokePaint);

        labelPaint.setColor(applyAlpha(value > 0.02f ? colorYellow : colorText, baseAlpha));
        labelPaint.setTextSize(height * 0.34f);
        canvas.drawText(control == LT ? "LT" : "RT", cx, cy + height * 0.12f, labelPaint);
    }

    private void drawSmallButton(Canvas canvas, int control, float baseAlpha) {
        float unit = getHeight() * scale;
        float width = 0.07f * unit;
        float height = 0.038f * unit;
        float cx = anchorX[control];
        float cy = anchorY[control];
        boolean pressed = isPressed(control);

        scratchRect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(colorFillDark, (pressed ? 0.85f : 0.62f) * baseAlpha));
        canvas.drawRoundRect(scratchRect, 3f * density, 3f * density, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.5f * density);
        strokePaint.setColor(applyAlpha(pressed ? colorYellow : colorStroke, baseAlpha));
        canvas.drawRoundRect(scratchRect, 3f * density, 3f * density, strokePaint);

        labelPaint.setColor(applyAlpha(pressed ? colorYellow : colorText, baseAlpha));
        labelPaint.setTextSize(height * 0.42f);
        canvas.drawText(control == BACK ? "VIEW" : "MENU", cx, cy + height * 0.15f, labelPaint);
    }

    private void drawStickClick(Canvas canvas, int control, float baseAlpha) {
        float radius = 0.034f * getHeight() * scale * 0.5f;
        float cx = anchorX[control];
        float cy = anchorY[control];
        boolean pressed = isPressed(control);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(applyAlpha(colorFillDark, (pressed ? 0.85f : 0.62f) * baseAlpha));
        canvas.drawCircle(cx, cy, radius, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.4f * density);
        strokePaint.setColor(applyAlpha(pressed ? colorYellow : colorStroke, baseAlpha));
        canvas.drawCircle(cx, cy, radius, strokePaint);

        labelPaint.setColor(applyAlpha(pressed ? colorYellow : colorText, baseAlpha));
        labelPaint.setTextSize(radius * 0.85f);
        canvas.drawText(control == L3 ? "L3" : "R3", cx, cy + radius * 0.3f, labelPaint);
    }

    private void drawEditChrome(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = Math.max(28f * density, safeInsets[1] + 18f * density);
        labelPaint.setColor(applyAlpha(colorYellow, 0.95f));
        labelPaint.setTextSize(13f * density);
        canvas.drawText(getContext().getString(R.string.vgp_edit_hint), cx, cy, labelPaint);

        if (dragTarget >= 0) {
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.4f * density);
            strokePaint.setColor(applyAlpha(colorYellow, 0.9f));
            strokePaint.setPathEffect(new DashPathEffect(
                    new float[]{6f * density, 5f * density}, 0f));
            canvas.drawCircle(anchorX[dragTarget], anchorY[dragTarget],
                    hitRadius(dragTarget), strokePaint);
            strokePaint.setPathEffect(null);
        }
    }

    // -------------------------------------------------------------- helpers

    private static int applyAlpha(int color, float alpha) {
        int channel = (int) (Color.alpha(color) * alpha);
        return (channel << 24) | (color & 0x00FFFFFF);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
