package com.whispertflite;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.RecordingCountdown;
import com.whispertflite.utils.TapOrHoldRecordListener;

import java.io.File;
import java.util.ArrayList;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final String TAG = "WhisperRecognizeActivity";
    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private ImageButton btnModeAuto;
    private ProgressBar processingBar = null;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Context mContext;
    private RecordingCountdown countDownTimer;
    private boolean modeAuto = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        sdcardDataFolder = this.getExternalFilesDir(null);
        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        if (!selectedTfliteFile.exists()) {
            Intent intent = new Intent(this, DownloadActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }
        String targetLang = getIntent().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        String langCode = sp.getString("language", "auto");
        int langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
        Log.d("WhisperRecognition","default langToken " + langToken);

        if (targetLang != null) {
            Log.d("WhisperRecognition","StartListening in " + targetLang);
            langCode = targetLang.split("[-_]")[0].toLowerCase();  //support both de_DE and de-DE
            langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
        } else {
            Log.d("WhisperRecognition","StartListening, no language specified");
        }

        initModel(selectedTfliteFile, langToken);

        setContentView(R.layout.activity_recognize);

        // Set the window layout parameters
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height =  WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM; // Position at the bottom of the screen

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        btnModeAuto = findViewById(R.id.btnModeAuto);
        processingBar = findViewById(R.id.processing_bar);

        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) countDownTimer.cancel();
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        processingBar.setProgress(0);
                        Toast.makeText(mContext,R.string.error_no_input,Toast.LENGTH_SHORT).show();
                    });
                }
            }

        });

        if (modeAuto) {
            btnRecord.setVisibility(View.GONE);
            HapticFeedback.vibrate(this);
            startRecording();
            startCountDown();
        }

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", modeAuto);
            editor.apply();
            btnRecord.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            if (mWhisper != null) stopTranscription();
            setResult(RESULT_CANCELED, null);
            finish();
        });

        TapOrHoldRecordListener.attach(btnRecord, new TapOrHoldRecordListener.Callback() {
            @Override
            public boolean onStartRecording() {
                if (!checkRecordPermission()) return false;
                if (mWhisper == null || mWhisper.isInProgress()) {
                    runOnUiThread(() -> Toast.makeText(mContext, getString(R.string.please_wait),Toast.LENGTH_SHORT).show());
                    return false;
                }
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                HapticFeedback.vibrate(mContext);
                startRecording();
                startCountDown();
                return true;
            }

            @Override
            public void onStopRecording() {
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }

            @Override
            public boolean isRecording() {
                return mRecorder != null && mRecorder.isInProgress();
            }
        });

        btnCancel.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            setResult(RESULT_CANCELED, null);
            finish();
        });

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
    private void initModel(File modelFile, int langToken) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setLanguage(langToken);
        Log.d(TAG, "Language token " + langToken);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                runOnUiThread(() -> processingBar.setIndeterminate(false));

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")){
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                if (result.trim().length() > 0){
                    sendResult(result.trim());
                }
            }
        });
    }

    private void sendResult(String result) {
        Intent sendResultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        sendResultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        sendResultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, sendResultIntent);

        PendingIntent pendingIntent = null;
        try {
            pendingIntent = getIntent().getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get EXTRA_RESULTS_PENDINGINTENT", e);
        }

        if (pendingIntent != null) {
            Intent pendingResultIntent = new Intent();
            pendingResultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
            pendingResultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
            pendingResultIntent.putExtra(SearchManager.QUERY, result);

            if (getIntent().hasExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)) {
                Bundle pendingIntentBundle = null;
                try {
                    pendingIntentBundle = getIntent().getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get EXTRA_RESULTS_PENDINGINTENT_BUNDLE", e);
                }
                if (pendingIntentBundle == null) {
                    pendingIntentBundle = new Bundle();
                }
                pendingIntentBundle.putStringArrayList(RecognizerIntent.EXTRA_RESULTS, results);
                pendingIntentBundle.putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
                pendingIntentBundle.putString(SearchManager.QUERY, result);

                pendingResultIntent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, pendingIntentBundle);
            }

            try {
                pendingIntent.send(this, RESULT_OK, pendingResultIntent);
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "PendingIntent cancelled", e);
            }
        } else if (getCallingActivity() == null) {
            String action = getIntent().getAction();
            if (RecognizerIntent.ACTION_WEB_SEARCH.equals(action)) {
                Intent webSearchIntent = new Intent(Intent.ACTION_WEB_SEARCH);
                webSearchIntent.putExtra(SearchManager.QUERY, result);
                webSearchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(webSearchIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start web search intent", e);
                }
            } else {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Whisper result", result);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, R.string.copy_to_clipboard, Toast.LENGTH_SHORT).show();
                }
            }
        }
        finish();
    }

    private void startTranscription() {
        if (countDownTimer!=null) countDownTimer.cancel();
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        if (mWhisper!=null){
            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
            mWhisper.start(mRecorder.getRecordedAudio());
            Log.d(TAG,"Start Transcription");
        }
    }

    private void stopTranscription() {
        runOnUiThread(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.need_record_audio_permission),Toast.LENGTH_SHORT).show();
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    @Override
    public void onDestroy() {
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }
}
