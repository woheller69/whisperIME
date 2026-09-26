package com.whispertflite.utils;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Record button behaviour:
 * - press and hold: records while held, stops on release (push-to-talk)
 * - short tap: starts recording and keeps going, a second tap stops it
 * - accessibility click (TalkBack double tap, switch access): toggles recording
 * Use {@link #attach(View, Callback)} so both touch and click are handled.
 */
public class TapOrHoldRecordListener implements View.OnTouchListener, View.OnClickListener {

    public interface Callback {
        // Returns true if recording was actually started
        boolean onStartRecording();
        void onStopRecording();
        boolean isRecording();
    }

    // Presses shorter than this are taps (toggle), longer ones are push-to-talk
    private static final long HOLD_THRESHOLD_MS = 400;

    private final Callback callback;
    private boolean latched = false;           // recording continues after a short tap
    private boolean startedByThisPress = false;
    private long downTime;
    private boolean clickFromTouch = false;  // performClick() called by onTouch, already handled there

    private TapOrHoldRecordListener(Callback callback) {
        this.callback = callback;
    }

    public static void attach(View button, Callback callback) {
        TapOrHoldRecordListener listener = new TapOrHoldRecordListener(callback);
        button.setOnTouchListener(listener);
        button.setOnClickListener(listener);
    }

    // Only reached without touch, e.g. from TalkBack
    @Override
    public void onClick(View v) {
        if (clickFromTouch) return;
        if (callback.isRecording()) {
            latched = false;
            callback.onStopRecording();
        } else {
            latched = callback.onStartRecording();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startedByThisPress = false;
            if (latched && callback.isRecording()) {
                latched = false;
                callback.onStopRecording();
            } else {
                latched = false;
                downTime = event.getEventTime();
                startedByThisPress = callback.onStartRecording();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!startedByThisPress) return true;
            startedByThisPress = false;
            boolean shortTap = action == MotionEvent.ACTION_UP && event.getEventTime() - downTime < HOLD_THRESHOLD_MS;
            if (shortTap && callback.isRecording()) {
                latched = true;
                clickFromTouch = true;
                v.performClick();  // accessibility event only, see onClick
                clickFromTouch = false;
            } else {
                callback.onStopRecording();
            }
        }
        return true;
    }
}
