package com.whispertflite.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.whispertflite.R;

/**
 * Row of rounded bars. Idle: static waveform, recording: follows the microphone level,
 * processing: travelling pulse.
 */
public class WaveformView extends View {

    public enum Mode { IDLE, RECORDING, PROCESSING }

    private static final int BAR_COUNT = 11;
    // Static shape shown when idle, symmetric like a sound wave icon
    private static final float[] IDLE_SHAPE = {0.25f, 0.4f, 0.6f, 0.45f, 0.8f, 1f, 0.8f, 0.45f, 0.6f, 0.4f, 0.25f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] levels = new float[BAR_COUNT];   // displayed heights 0..1
    private final float[] targets = new float[BAR_COUNT];  // heights to animate to
    private Mode mode = Mode.IDLE;

    // Microphone levels arrive on the recording thread (~33/s); they are collected here and
    // consumed in onDraw, so no Runnable has to be posted per audio frame
    private final Object pendingLock = new Object();
    private float pendingLevel = 0f;
    private int pendingCount = 0;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(ContextCompat.getColor(context, R.color.overlayOnButton));
        System.arraycopy(IDLE_SHAPE, 0, levels, 0, BAR_COUNT);
        System.arraycopy(IDLE_SHAPE, 0, targets, 0, BAR_COUNT);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.IDLE) System.arraycopy(IDLE_SHAPE, 0, targets, 0, BAR_COUNT);
        if (mode == Mode.RECORDING) java.util.Arrays.fill(targets, 0.1f);
        postInvalidateOnAnimation();
    }

    // Called with the current microphone level (0..1), may be called from any thread
    public void pushLevel(float level) {
        boolean first;
        synchronized (pendingLock) {
            first = pendingCount == 0;
            pendingLevel = Math.max(pendingLevel, level);
            pendingCount++;
        }
        if (first) postInvalidateOnAnimation();  // thread safe
    }

    // Scroll left and add the loudest level received since the last frame on the right
    private void consumePendingLevel() {
        float level;
        synchronized (pendingLock) {
            if (pendingCount == 0) return;
            level = pendingLevel;
            pendingLevel = 0f;
            pendingCount = 0;
        }
        if (mode != Mode.RECORDING) return;
        System.arraycopy(targets, 1, targets, 0, BAR_COUNT - 1);
        targets[BAR_COUNT - 1] = Math.max(0.1f, Math.min(1f, level));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        consumePendingLevel();

        if (mode == Mode.PROCESSING) {
            double t = SystemClock.uptimeMillis() / 180.0;
            for (int i = 0; i < BAR_COUNT; i++) {
                targets[i] = 0.3f + 0.6f * (float) Math.pow((Math.sin(t - i * 0.6) + 1) / 2, 2);
            }
        }

        boolean animating = mode == Mode.PROCESSING;
        for (int i = 0; i < BAR_COUNT; i++) {
            float diff = targets[i] - levels[i];
            if (Math.abs(diff) > 0.005f) {
                levels[i] += diff * 0.35f;
                animating = true;
            } else {
                levels[i] = targets[i];
            }
        }

        float slot = (float) w / BAR_COUNT;
        float barWidth = Math.max(2f, slot * 0.45f);
        float radius = barWidth / 2f;
        float centerY = getPaddingTop() + h / 2f;
        for (int i = 0; i < BAR_COUNT; i++) {
            float barHeight = Math.max(barWidth, levels[i] * h);
            float cx = getPaddingLeft() + slot * i + slot / 2f;
            rect.set(cx - barWidth / 2f, centerY - barHeight / 2f, cx + barWidth / 2f, centerY + barHeight / 2f);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        if (animating) postInvalidateOnAnimation();
    }
}
