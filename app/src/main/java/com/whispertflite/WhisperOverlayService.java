package com.whispertflite;

import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.overlay.WaveformView;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating dictation button shown while a text field is being edited.
 * Implemented as an AccessibilityService because that is what allows it to
 * - draw an overlay (TYPE_ACCESSIBILITY_OVERLAY) without SYSTEM_ALERT_WINDOW,
 * - use the microphone while another app is in the foreground,
 * - insert the transcription into the focused field of another app without switching keyboard.
 */
public class WhisperOverlayService extends AccessibilityService {
    private static final String TAG = "WhisperOverlayService";
    private static final long MODEL_UNLOAD_DELAY_MS = 2 * 60 * 1000;
    private static final long VISIBILITY_CHECK_DELAY_MS = 150;
    private static final String PREF_BUBBLE_X = "overlayBubbleX";
    private static final String PREF_BUBBLE_Y = "overlayBubbleY";
    // Time the target app gets to read the temporary clip before the previous clipboard is restored
    private static final long CLIPBOARD_RESTORE_DELAY_MS = 500;
    private static final long RECORDER_RETRY_DELAY_MS = 100;
    private static final String PREF_PANEL_X = "overlayPanelX";
    private static final String PREF_PANEL_Y = "overlayPanelY";


    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;
    private SharedPreferences sp;

    private View bubbleRoot;
    private WindowManager.LayoutParams bubbleParams;
    private boolean bubbleShown = false;

    private View panelRoot;
    private View panelCard;
    private WindowManager.LayoutParams panelParams;
    private View btnCancelRecording;
    private View btnCancelTranscription;
    private boolean panelShown = false;
    private TextView tvStatus;
    private TextView tvText;
    private View statusDot;
    private WaveformView waveform;

    private Recorder mRecorder;
    private Whisper mWhisper;
    private boolean modelReady = false;
    private boolean modelLoading = false;

    // Recording and transcription run independently: the user can keep talking while earlier
    // recordings are transcribed. Finished recordings wait in a queue and are transcribed in order.
    private boolean recording = false;
    private boolean transcribing = false;  // one queued recording is being processed by Whisper
    private final ArrayDeque<byte[]> transcriptionQueue = new ArrayDeque<>();
    private int progressChunk = -1;        // chunk progress of the running transcription, -1 = none
    private int progressTotal = 0;
    private boolean insertWhenDone = false;
    private int session = 0;               // incremented whenever the panel closes, stale results are ignored
    private int transcriptionSession = -1;
    private long recordingStartedAt;
    private final StringBuilder transcript = new StringBuilder();

    private final Runnable visibilityCheck = this::updateBubbleVisibility;
    private final Runnable unloadModel = this::unloadModelIfIdle;
    private final Runnable recordingTicker = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            updateUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = getSystemService(WindowManager.class);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        createBubble();
        createPanel();

        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                handler.post(() -> onRecorderUpdate(message));
            }

            @Override
            public void onAmplitudeReceived(float level) {
                waveform.pushLevel(level);
            }
        });
        scheduleVisibilityCheck();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Our own bubble/panel windows cause events too, they never change the focused field
        if (event.getPackageName() != null && getPackageName().contentEquals(event.getPackageName())
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        scheduleVisibilityCheck();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubbleShown) {
            clampBubblePosition();
            windowManager.updateViewLayout(bubbleRoot, bubbleParams);
        }
        if (panelShown) {
            panelParams.width = panelWidth();
            clampPanelPosition();
            windowManager.updateViewLayout(panelRoot, panelParams);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        closePanel();
        setBubbleVisible(false);
        handler.removeCallbacksAndMessages(null);
        if (mWhisper != null) {
            Whisper whisper = mWhisper;
            modelExecutor.execute(whisper::unloadModel);
        }
        modelExecutor.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void createBubble() {
        bubbleRoot = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleParams.x = sp.getInt(PREF_BUBBLE_X, dm.widthPixels);
        bubbleParams.y = sp.getInt(PREF_BUBBLE_Y, (int) (dm.heightPixels * 0.3f));

        View bubble = bubbleRoot.findViewById(R.id.overlay_bubble);
        bubble.setOnClickListener(v -> openPanel());
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private final int touchSlop = ViewConfiguration.get(WhisperOverlayService.this).getScaledTouchSlop();
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        dragging = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                            v.setPressed(false);
                        }
                        if (dragging && bubbleShown) {
                            bubbleParams.x = startX + (int) dx;
                            bubbleParams.y = startY + (int) dy;
                            windowManager.updateViewLayout(bubbleRoot, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (dragging) snapBubbleToEdge();
                        else v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (dragging) snapBubbleToEdge();
                        return true;
                }
                return false;
            }
        });
    }

    private void setBubbleVisible(boolean visible) {
        if (visible == bubbleShown || bubbleRoot == null) return;
        if (visible) {
            clampBubblePosition();
            windowManager.addView(bubbleRoot, bubbleParams);
            preloadModel();
        } else {
            windowManager.removeView(bubbleRoot);
            if (!panelShown) {
                handler.removeCallbacks(unloadModel);
                handler.postDelayed(unloadModel, MODEL_UNLOAD_DELAY_MS);
            }
        }
        bubbleShown = visible;
    }

    private void clampBubblePosition() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = bubbleSize();
        bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, dm.widthPixels - size));
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - size));
    }

    private int bubbleSize() {
        int measured = bubbleRoot.getWidth();
        return measured > 0 ? measured : dp(72);
    }

    private void snapBubbleToEdge() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = bubbleSize();
        int targetX = bubbleParams.x + size / 2 < dm.widthPixels / 2 ? 0 : dm.widthPixels - size;
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - size));
        ValueAnimator animator = ValueAnimator.ofInt(bubbleParams.x, targetX);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            if (!bubbleShown) return;
            bubbleParams.x = (int) a.getAnimatedValue();
            windowManager.updateViewLayout(bubbleRoot, bubbleParams);
        });
        animator.start();
        sp.edit().putInt(PREF_BUBBLE_X, targetX).putInt(PREF_BUBBLE_Y, bubbleParams.y).apply();
    }

    private void scheduleVisibilityCheck() {
        handler.removeCallbacks(visibilityCheck);
        handler.postDelayed(visibilityCheck, VISIBILITY_CHECK_DELAY_MS);
    }

    private void updateBubbleVisibility() {
        setBubbleVisible(!panelShown && isTextInputActive());
    }

    // True while a keyboard is shown or an editable field has input focus.
    // Hidden for password fields and while our own IME is active.
    private boolean isTextInputActive() {
        try {
            AccessibilityNodeInfo focus = findFocusedEditable();
            if (focus != null && focus.isPassword()) return false;
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    AccessibilityNodeInfo root = window.getRoot();
                    return root == null || root.getPackageName() == null
                            || !getPackageName().contentEquals(root.getPackageName());
                }
            }
            return focus != null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot inspect windows", e);
            return false;
        }
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) return focus;
        }
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo windowRoot = window.getRoot();
            if (windowRoot == null) continue;
            AccessibilityNodeInfo focus = windowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) return focus;
        }
        return null;
    }

    // ---------------------------------------------------------------- panel

    @SuppressLint("InflateParams")
    private void createPanel() {
        panelRoot = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        // x from the left edge, y from the bottom edge; default: centered near the bottom
        panelParams.gravity = Gravity.BOTTOM | Gravity.START;
        panelParams.x = sp.getInt(PREF_PANEL_X, -1);
        panelParams.y = sp.getInt(PREF_PANEL_Y, dp(16));

        panelCard = panelRoot.findViewById(R.id.overlay_card);
        tvStatus = panelRoot.findViewById(R.id.overlay_status);
        tvText = panelRoot.findViewById(R.id.overlay_text);
        tvText.setMovementMethod(new ScrollingMovementMethod());
        statusDot = panelRoot.findViewById(R.id.overlay_status_dot);
        waveform = panelRoot.findViewById(R.id.overlay_waveform);

        panelRoot.findViewById(R.id.overlay_cancel).setOnClickListener(v -> closePanel());
        btnCancelRecording = panelRoot.findViewById(R.id.overlay_cancel_recording);
        btnCancelRecording.setOnClickListener(v -> cancelRecording());
        btnCancelTranscription = panelRoot.findViewById(R.id.overlay_cancel_transcription);
        btnCancelTranscription.setOnClickListener(v -> cancelTranscription());
        setupPanelDrag(panelRoot.findViewById(R.id.overlay_drag_handle));
        panelRoot.findViewById(R.id.overlay_wave_button).setOnClickListener(v -> toggleRecording());
        panelRoot.findViewById(R.id.overlay_done).setOnClickListener(v -> finishDictation());
    }

    private int panelWidth() {
        return Math.min(getResources().getDisplayMetrics().widthPixels, dp(480));
    }

    private void openPanel() {
        if (panelShown) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!modelFile().exists()) {
            Toast.makeText(this, R.string.overlay_no_model, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }

        setBubbleVisible(false);
        transcript.setLength(0);
        insertWhenDone = false;
        panelParams.width = panelWidth();
        if (panelParams.x < 0) panelParams.x = (getResources().getDisplayMetrics().widthPixels - panelParams.width) / 2;
        clampPanelPosition();
        windowManager.addView(panelRoot, panelParams);
        panelShown = true;

        panelCard.setAlpha(0f);
        panelCard.setTranslationY(dp(24));
        panelCard.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();

        handler.removeCallbacks(unloadModel);
        ensureModelLoaded();
        startRecording();
    }

    private void closePanel() {
        if (!panelShown) return;
        if (recording) {
            recording = false;
            mRecorder.stop();
        }
        if (transcribing && mWhisper != null) {
            mWhisper.stop();  // abort the inference instead of letting it run for nothing
        }
        session++;
        transcribing = false;
        transcriptionQueue.clear();
        progressChunk = -1;
        insertWhenDone = false;
        handler.removeCallbacks(recordingTicker);
        windowManager.removeView(panelRoot);
        panelShown = false;
        handler.postDelayed(unloadModel, MODEL_UNLOAD_DELAY_MS);
        scheduleVisibilityCheck();
    }

    // "Cancel recording": discard what is being recorded now, keep the panel and the text so far
    private void cancelRecording() {
        if (!recording) return;
        recording = false;  // the recorder's MSG_RECORDING_DONE is ignored, its audio is dropped
        insertWhenDone = false;
        handler.removeCallbacks(recordingTicker);
        mRecorder.stop();
        updateUi();
        tvStatus.setText(R.string.overlay_recording_discarded);
    }

    // "Cancel transcription": abort the running transcription and drop the queued recordings,
    // keep the panel, the text so far and a recording that is still running
    private void cancelTranscription() {
        if (!transcribing && transcriptionQueue.isEmpty()) return;
        if (transcribing && mWhisper != null) mWhisper.stop();
        session++;  // ignore a result that may still arrive from the aborted transcription
        transcribing = false;
        transcriptionQueue.clear();
        progressChunk = -1;
        insertWhenDone = false;
        updateUi();
        if (!recording) tvStatus.setText(R.string.overlay_transcription_cancelled);
    }

    // The panel is moved by dragging its handle; the position is remembered
    @SuppressLint("ClickableViewAccessibility")
    private void setupPanelDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = panelParams.x;
                        startY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!panelShown) return true;
                        panelParams.x = startX + (int) (event.getRawX() - downRawX);
                        panelParams.y = startY - (int) (event.getRawY() - downRawY);  // y is measured from the bottom
                        clampPanelPosition();
                        windowManager.updateViewLayout(panelRoot, panelParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        sp.edit().putInt(PREF_PANEL_X, panelParams.x).putInt(PREF_PANEL_Y, panelParams.y).apply();
                        return true;
                }
                return false;
            }
        });
    }

    private void clampPanelPosition() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int height = panelRoot.getHeight() > 0 ? panelRoot.getHeight() : dp(320);
        panelParams.x = Math.max(0, Math.min(panelParams.x, dm.widthPixels - panelParams.width));
        panelParams.y = Math.max(0, Math.min(panelParams.y, dm.heightPixels - height));
    }

    // Wave button: start/stop recording, also while earlier recordings are still being transcribed
    private void toggleRecording() {
        if (recording) mRecorder.stop();
        else startRecording();
    }

    // Check button: stop recording if needed, wait for all transcriptions, then insert and close
    private void finishDictation() {
        if (recording) {
            insertWhenDone = true;
            mRecorder.stop();
            return;
        }
        if (transcribing || !transcriptionQueue.isEmpty()) {
            insertWhenDone = true;
            updateUi();
            return;
        }
        String text = transcript.toString().trim();
        if (!text.isEmpty()) insertText(text);
        closePanel();
    }

    private void startRecording() {
        if (recording || insertWhenDone) return;
        if (!mRecorder.start()) {
            // The previous recording is still being finished by the recorder thread, retry shortly
            handler.postDelayed(() -> {
                if (panelShown) startRecording();
            }, RECORDER_RETRY_DELAY_MS);
            return;
        }
        recording = true;
        recordingStartedAt = SystemClock.elapsedRealtime();
        HapticFeedback.vibrate(this);
        updateUi();
        handler.removeCallbacks(recordingTicker);
        handler.post(recordingTicker);
    }

    private void onRecorderUpdate(String message) {
        if (message.equals(Recorder.MSG_RECORDING)) return;
        if (!recording) return;  // cancelled
        recording = false;
        handler.removeCallbacks(recordingTicker);
        if (message.equals(Recorder.MSG_RECORDING_DONE)) {
            HapticFeedback.vibrate(this);
            // Take the audio now: a new recording may start before this one is transcribed
            transcriptionQueue.add(mRecorder.getRecordedAudio());
            transcribeNext();
            updateUi();
        } else {
            // MSG_RECORDING_ERROR (too short) or an error text
            if (insertWhenDone) {
                finishDictation();
                return;
            }
            updateUi();
            if (!transcribing) {
                tvStatus.setText(message.equals(Recorder.MSG_RECORDING_ERROR) ? getString(R.string.error_no_input) : message);
            }
        }
    }

    // Starts the transcription of the oldest queued recording, if Whisper is free and the model is loaded
    private void transcribeNext() {
        if (transcribing || transcriptionQueue.isEmpty() || !modelReady || !panelShown) return;
        if (mWhisper.isInProgress()) {
            // A cancelled transcription is still running, wait for it
            handler.postDelayed(this::transcribeNext, 200);
            return;
        }
        transcribing = true;
        transcriptionSession = session;
        progressChunk = -1;
        mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
        String langCode = sp.getString("language", "auto");
        mWhisper.setLanguage(InputLang.getIdForLanguage(InputLang.getLangList(), langCode));
        mWhisper.start(transcriptionQueue.poll());
    }

    private void onTranscriptionResult(WhisperResult whisperResult) {
        if (transcriptionSession != session || !transcribing) return;
        transcribing = false;
        progressChunk = -1;
        String result = whisperResult.getResult();
        if ("zh".equals(whisperResult.getLanguage())) {
            boolean simpleChinese = sp.getBoolean("simpleChinese", false);
            result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
        }
        result = result.trim();
        if (!result.isEmpty()) {
            if (transcript.length() > 0) transcript.append(' ');
            transcript.append(result);
        }
        transcribeNext();
        if (insertWhenDone && !recording && !transcribing && transcriptionQueue.isEmpty()) {
            finishDictation();
            return;
        }
        updateUi();
    }

    private void onTranscriptionFailed(String message) {
        if (transcriptionSession != session || !transcribing) return;
        transcribing = false;
        progressChunk = -1;
        transcribeNext();  // keep going with the other recordings
        if (insertWhenDone && !recording && !transcribing && transcriptionQueue.isEmpty()) {
            finishDictation();
            return;
        }
        updateUi();
        if (!recording) tvStatus.setText(message);
    }

    private void updateUi() {
        boolean busy = transcribing || !transcriptionQueue.isEmpty();
        String transcribingText;
        if (!modelReady) {
            transcribingText = getString(R.string.overlay_loading_model);
        } else if (progressChunk >= 0 && progressTotal > 1) {
            transcribingText = getString(R.string.overlay_transcribing_progress, progressChunk + 1, progressTotal);
        } else {
            transcribingText = getString(R.string.overlay_transcribing);
        }

        int dotColor;
        if (recording) {
            long elapsed = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000;
            String listening = getString(R.string.overlay_listening,
                    String.format(Locale.ROOT, "%d:%02d", elapsed / 60, elapsed % 60));
            tvStatus.setText(busy ? listening + "  ·  " + transcribingText : listening);
            dotColor = R.color.overlayRecording;
            waveform.setMode(WaveformView.Mode.RECORDING);
        } else if (busy) {
            tvStatus.setText(transcribingText);
            dotColor = R.color.overlayLilac;
            waveform.setMode(WaveformView.Mode.PROCESSING);
        } else {
            tvStatus.setText(R.string.overlay_ready);
            dotColor = R.color.overlayCreamDim;
            waveform.setMode(WaveformView.Mode.IDLE);
        }
        statusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, dotColor)));
        // INVISIBLE, not GONE: the layout must not move under the user's finger when a button appears
        btnCancelRecording.setVisibility(recording ? View.VISIBLE : View.INVISIBLE);
        btnCancelTranscription.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        // "Speak now" only makes sense while recording or waiting, not while only transcribing
        tvText.setHint(busy && !recording ? "" : getString(R.string.overlay_hint_speak));
        if (!TextUtils.equals(tvText.getText(), transcript)) {
            tvText.setText(transcript.toString());
            // Keep the latest text visible
            tvText.post(() -> {
                if (tvText.getLayout() == null) return;
                int bottom = tvText.getLayout().getLineTop(tvText.getLineCount())
                        - tvText.getHeight() + tvText.getPaddingTop() + tvText.getPaddingBottom();
                tvText.scrollTo(0, Math.max(0, bottom));
            });
        }
    }

    // ---------------------------------------------------------------- text insertion

    private void insertText(String text) {
        AccessibilityNodeInfo node = findFocusedEditable();
        if (node != null && node.isPassword()) return;  // never put dictated text into password fields or the clipboard
        if (node != null) {
            CharSequence current = fieldText(node);
            if (BuildConfig.DEBUG) {
                CharSequence raw = node.getText();
                Log.d(TAG, "insert: pkg=" + node.getPackageName() + " class=" + node.getClassName()
                        + " rawLen=" + (raw == null ? -1 : raw.length()) + " usedLen=" + current.length()
                        + " showingHint=" + node.isShowingHintText() + " hasHint=" + (node.getHintText() != null)
                        + " sel=" + node.getTextSelectionStart() + "," + node.getTextSelectionEnd());
            }
            int len = current.length();
            int start = node.getTextSelectionStart();
            int end = node.getTextSelectionEnd();
            if (start < 0 || end < 0 || start > len || end > len) {
                start = len;
                end = len;
            }
            if (start > end) {
                int tmp = start;
                start = end;
                end = tmp;
            }
            String insert = text;
            if (start > 0 && !Character.isWhitespace(current.charAt(start - 1))) insert = " " + insert;
            if (end < len && !Character.isWhitespace(current.charAt(end))) insert = insert + " ";

            // Paste first: it inserts at the cursor without rewriting the field, so it is independent of how the
            // app reports the field's text (some apps expose the hint, e.g. "Message", as text) and keeps undo working
            if (pasteText(node, insert)) return;

            // The app refused to paste: rewrite the field's text instead
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    TextUtils.concat(current.subSequence(0, start), insert, current.subSequence(end, len)));
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                int cursor = start + insert.length();
                Bundle selection = new Bundle();
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
                return;
            }
        }
        // No usable field: leave the text in the clipboard so the user can paste it
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.model_output), text));
        Toast.makeText(this, R.string.overlay_copied, Toast.LENGTH_LONG).show();
    }

    // Text of an editable node, "" while it only shows its hint. Many apps (WhatsApp, Telegram, ...) expose the
    // hint ("Message") as the node text without setting isShowingHintText(), so compare with the hint too.
    private static CharSequence fieldText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null || node.isShowingHintText()) return "";
        CharSequence hint = node.getHintText();
        if (hint != null && text.toString().trim().equals(hint.toString().trim())) return "";
        return text;
    }

    // Pastes via a temporary clip (marked sensitive, so no clipboard preview is shown), then restores the
    // user's previous clipboard content. Returns false, with the clipboard untouched, if the app refuses to paste.
    private boolean pasteText(AccessibilityNodeInfo node, String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.getPrimaryClip();
        ClipData clip = ClipData.newPlainText(getString(R.string.model_output), text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);  // ClipDescription.EXTRA_IS_SENSITIVE, API 33
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            if (previous != null) clipboard.setPrimaryClip(previous);
            else clipboard.clearPrimaryClip();
            return false;
        }
        handler.postDelayed(() -> {
            if (previous != null) clipboard.setPrimaryClip(previous);
            else clipboard.clearPrimaryClip();
        }, CLIPBOARD_RESTORE_DELAY_MS);
        return true;
    }

    // ---------------------------------------------------------------- model

    private File modelFile() {
        return new File(getExternalFilesDir(null), sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
    }

    private void ensureModelLoaded() {
        File modelFile = modelFile();
        if (mWhisper == null) {
            mWhisper = new Whisper(this);
            mWhisper.setListener(new Whisper.WhisperListener() {
                @Override
                public void onUpdateReceived(String message) {
                    if (message.startsWith("Transcription failed") || message.startsWith("Engine not initialized")) {
                        handler.post(() -> onTranscriptionFailed(message));
                    }
                }

                @Override
                public void onResultReceived(WhisperResult result) {
                    handler.post(() -> onTranscriptionResult(result));
                }

                @Override
                public void onProgress(int chunk, int total) {
                    handler.post(() -> {
                        if (!transcribing || !panelShown) return;
                        progressChunk = chunk;
                        progressTotal = total;
                        updateUi();
                    });
                }
            });
        }
        if (modelLoading) return;
        if (modelReady && mWhisper.getCurrentModelPath().equals(modelFile.getAbsolutePath())) return;

        modelReady = false;
        modelLoading = true;
        Whisper whisper = mWhisper;
        modelExecutor.execute(() -> {
            if (!whisper.getCurrentModelPath().isEmpty()) whisper.unloadModel();
            boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
            File vocabFile = new File(getExternalFilesDir(null), isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE);
            whisper.loadModel(modelFile, vocabFile, isMultilingualModel);
            Log.d(TAG, "Initialized: " + modelFile.getName());
            handler.post(() -> {
                modelLoading = false;
                modelReady = true;
                if (panelShown) updateUi();
                transcribeNext();
            });
        });
    }

    // Load the model while the bubble is visible, so the first dictation does not wait for it
    private void preloadModel() {
        handler.removeCallbacks(unloadModel);
        if (modelFile().exists()) ensureModelLoaded();
    }

    private void unloadModelIfIdle() {
        if (panelShown || bubbleShown || mWhisper == null || !modelReady) return;
        if (mWhisper.isInProgress()) {
            handler.postDelayed(unloadModel, MODEL_UNLOAD_DELAY_MS);
            return;
        }
        modelReady = false;
        Whisper whisper = mWhisper;
        modelExecutor.execute(whisper::unloadModel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
