package com.whispertflite.asr;

import android.content.Context;
import android.util.Log;

import com.whispertflite.engine.WhisperEngine;
import com.whispertflite.engine.WhisperEngineJava;
import com.whispertflite.utils.CustomVocabulary;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);

        // Recordings longer than 30 s are transcribed in chunks: chunk of total are done
        default void onProgress(int chunk, int total) {}
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;
    private String currentModelPath = "";

    public enum Action {
        TRANSLATE, TRANSCRIBE
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);
    private volatile boolean mCancelled = false;
    private volatile byte[] mAudio;

    private final WhisperEngine mWhisperEngine;
    private final Context mContext;
    private Action mAction;
    private int mLangToken = -1;
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;

    public Whisper(Context context) {
        this.mContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.mWhisperEngine = new WhisperEngineJava(context);

        // Start thread for RecordBuffer transcription
        Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
        threadProcessRecordBuffer.start();

    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        loadModel(modelPath.getAbsolutePath(), vocabPath.getAbsolutePath(), isMultilingual);
        currentModelPath = modelPath.getAbsolutePath();
    }

    public void loadModel(String modelPath, String vocabPath, boolean isMultilingual) {
        try {
            mWhisperEngine.initialize(modelPath, vocabPath, isMultilingual);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            sendUpdate("Model initialization failed");
        }
    }

    public String getCurrentModelPath(){
        return currentModelPath;
    }

    /**
     * Unloads the model. A running transcription is cancelled first and this waits for it to end,
     * so the interpreter is never closed while it is in use.
     */
    public void unloadModel() {
        stop();
        synchronized (mWhisperEngine) {  // same lock as processRecordBuffer()
            mWhisperEngine.deinitialize();
        }
        currentModelPath = "";
    }

    public void setAction(Action action) {
        this.mAction = action;
    }

    public void setLanguage(int language){
        this.mLangToken = language;
    }

    /** Transcribes the given 16 bit PCM (16 kHz mono) recording, see {@link Recorder#getRecordedAudio()}. */
    public void start(byte[] audio) {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        mAudio = audio;
        mCancelled = false;
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    /**
     * Cancels the running transcription: the interpreter is aborted and no result is sent.
     * isInProgress() stays true until the worker thread has actually finished.
     */
    public void stop() {
        mCancelled = true;
        mWhisperEngine.cancelInference();
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            byte[] audio = mAudio;
            mAudio = null;
            if (mWhisperEngine.isInitialized() && audio != null) {
                long startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);

                WhisperResult whisperResult;
                synchronized (mWhisperEngine) {
                    whisperResult = mWhisperEngine.processRecordBuffer(RecordBuffer.getSamples(audio), mAction, mLangToken,
                            () -> mCancelled, (chunk, total) -> {
                                if (mUpdateListener != null) mUpdateListener.onProgress(chunk, total);
                            });
                }
                if (whisperResult == null || mCancelled) {
                    Log.d(TAG, "Transcription cancelled");
                    return;
                }
                // Fix names and terms from the user's custom vocabulary
                whisperResult = new WhisperResult(CustomVocabulary.apply(mContext, whisperResult.getResult()),
                        whisperResult.getLanguage(), whisperResult.getTask());
                sendResult(whisperResult);

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(MSG_PROCESSING_DONE);
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}
