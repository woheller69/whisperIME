package com.whispertflite.engine;

import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;

import java.io.IOException;
import java.util.function.BooleanSupplier;

public interface WhisperEngine {

    interface ProgressListener {
        // chunk: number of 30 s chunks already transcribed, total: estimated number of chunks
        void onProgress(int chunk, int total);
    }

    boolean isInitialized();
    void initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException;
    void deinitialize();

    /**
     * Transcribes/translates the samples (16 kHz mono floats). Recordings longer than 30 s are split into chunks.
     * Returns null if cancelled via isCancelled / {@link #cancelInference()}.
     */
    WhisperResult processRecordBuffer(float[] samples, Whisper.Action mAction, int mLangToken,
                                      BooleanSupplier isCancelled, ProgressListener progressListener);

    // Aborts a running inference as soon as possible (callable from any thread)
    void cancelInference();
}
