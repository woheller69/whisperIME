package com.whispertflite;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.ViewGroup;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.RecordingCountdown;
import com.whispertflite.utils.TapOrHoldRecordListener;

import java.io.File;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";
    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private ProgressBar processingBar = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;
    private RecordingCountdown countDownTimer;
    private static boolean translate = false;
    private boolean modeAuto = false;
    private RelativeLayout layoutButtons;

    @Override
    public void onCreate() {
        mContext = this;
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (attribute.inputType ==  EditorInfo.TYPE_NULL) {
            Log.d(TAG, "Cancelling: onStartInput: inputType=" + attribute.inputType + ", package=" + attribute.packageName + ", fieldId=" + attribute.fieldId);
            deinitModel();
            if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting){
        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));

        if (!selectedTfliteFile.exists()) {
            switchToPreviousInputMethod();  //switch back and download models first
            Intent intent = new Intent(this, DownloadActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            if (mWhisper == null)
                initModel(selectedTfliteFile);
            else {
                if (!mWhisper.getCurrentModelPath().equals(selectedTfliteFile.getAbsolutePath())){
                    deinitModel();
                    initModel(selectedTfliteFile);
                }
            }
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.bottomMargin = insets.bottom;
            mlp.rightMargin = insets.right;
            v.setLayoutParams(mlp);

            return WindowInsetsCompat.CONSUMED;
        });

        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnTranslate = view.findViewById(R.id.btnTranslate);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        processingBar = view.findViewById(R.id.processing_bar);
        tvStatus = view.findViewById(R.id.tv_status);
        sdcardDataFolder = this.getExternalFilesDir(null);
        btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        checkRecordPermission();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) countDownTimer.cancel();
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                        processingBar.setProgress(0);
                    });
                }
            }

        });

        if (modeAuto) {
            layoutButtons.setVisibility(View.GONE);
            HapticFeedback.vibrate(this);
            startRecording();
            startCountDown();
            handler.post(() -> {
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
            });
        }

        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private Runnable initialDeleteRunnable;
            private Runnable repeatDeleteRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    // Post the initial delay of 500ms
                    initialDeleteRunnable = new Runnable() {
                        @Override
                        public void run() {
                            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                            // Start repeating every 100ms
                            repeatDeleteRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                                    handler.postDelayed(this, 100);
                                }
                            };
                            handler.postDelayed(repeatDeleteRunnable, 100);
                        }
                    };
                    handler.postDelayed(initialDeleteRunnable, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    // Remove both callbacks
                    if (initialDeleteRunnable != null) {
                        handler.removeCallbacks(initialDeleteRunnable);
                    }
                    if (repeatDeleteRunnable != null) {
                        handler.removeCallbacks(repeatDeleteRunnable);
                    }
                    initialDeleteRunnable = null;
                    repeatDeleteRunnable = null;
                }
                return true;
            }
        });

        TapOrHoldRecordListener.attach(btnRecord, new TapOrHoldRecordListener.Callback() {
            @Override
            public boolean onStartRecording() {
                if (!checkRecordPermission()) return false;
                if (mWhisper == null || mWhisper.isInProgress()) {
                    handler.post(() -> {
                        tvStatus.setText(getString(R.string.please_wait));
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                    return false;
                }
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                HapticFeedback.vibrate(mContext);
                startRecording();
                startCountDown();
                handler.post(() -> {
                    tvStatus.setText("");
                    tvStatus.setVisibility(View.GONE);
                });
                return true;
            }

            @Override
            public void onStopRecording() {
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }

            @Override
            public boolean isRecording() {
                return mRecorder != null && mRecorder.isInProgress();
            }
        });

        btnKeyboard.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            switchToPreviousInputMethod();
        });

        btnTranslate.setOnClickListener(v -> {
            translate = !translate;
            btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });

        btnEnter.setOnClickListener(v -> {
            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        });

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", modeAuto);
            editor.apply();
            layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            switchToPreviousInputMethod();
        });
        return view;
    }

    private void startCountDown() {
        if (countDownTimer != null) countDownTimer.cancel();
        countDownTimer = new RecordingCountdown(processingBar);  // the view (and progress bar) may have been recreated
        countDownTimer.start();
    }

    private void startRecording() {
        if (modeAuto) mRecorder.initVad();
        mRecorder.start();
    }

    // Model initialization
    private void initModel(File modelFile) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handler.post(() -> processingBar.setIndeterminate(false));
                handler.post(() -> {
                    tvStatus.setText("");
                    tvStatus.setVisibility(View.GONE);
                });

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")){
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                boolean commitSuccess = false;
                if (result.trim().length() > 0) commitSuccess = getCurrentInputConnection().commitText(result.trim() + " ",1);
                if (modeAuto && commitSuccess) handler.postDelayed(() -> switchToPreviousInputMethod(), 100); //slightly delayed, otherwise some apps, e.g. WhatsApp, do not accept the committed text (commitText on inactive InputConnection)
            }
        });
    }

    private void startTranscription() {
        if (countDownTimer!=null) countDownTimer.cancel();
        handler.post(() -> processingBar.setProgress(0));
        handler.post(() -> processingBar.setIndeterminate(true));
        if (mWhisper!=null){
            if (translate) mWhisper.setAction(Whisper.ACTION_TRANSLATE);
            else mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);

            String langCode = sp.getString("language", "auto");
            int langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
            Log.d("WhisperIME","default langToken " + langToken);
            mWhisper.setLanguage(langToken);
            mWhisper.start(mRecorder.getRecordedAudio());
        }
    }

    private void stopTranscription() {
        handler.post(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }
}