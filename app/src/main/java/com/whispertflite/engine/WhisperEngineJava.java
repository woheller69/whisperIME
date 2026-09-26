package com.whispertflite.engine;

import android.content.Context;
import android.util.Log;

import com.whispertflite.BuildConfig;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.WhisperUtil;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class WhisperEngineJava implements WhisperEngine {
    private final String TAG = "WhisperEngineJava";
    // The cut between two 30 s chunks is searched in the last CHUNK_CUT_SEARCH_SECONDS of a chunk
    private static final int CHUNK_CUT_SEARCH_SECONDS = 8;
    private final WhisperUtil mWhisperUtil = new WhisperUtil();

    private final Context mContext;
    private boolean mIsInitialized = false;
    private Interpreter mInterpreter = null;

    public WhisperEngineJava(Context context) {
        mContext = context;
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public void initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        // Load model
        long t0 = System.currentTimeMillis();
        loadModel(modelPath);
        if (BuildConfig.DEBUG) Log.d(TAG, "Model load took " + (System.currentTimeMillis() - t0) + "ms");
        Log.d(TAG, "Model is loaded..." + modelPath);

        // Load filters and vocab
        boolean ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath);
        if (ret) {
            mIsInitialized = true;
            Log.d(TAG, "Filters and Vocab are loaded..." + vocabPath);
        } else {
            mIsInitialized = false;
            Log.d(TAG, "Failed to load Filters and Vocab...");
        }

    }

    // Unload the model by closing the interpreter
    @Override
    public void deinitialize() {
        if (mInterpreter != null) {
            mInterpreter.setCancelled(true);
            mInterpreter.close();
            mInterpreter = null; // Optional: Set to null to avoid accidental reuse
        }
    }

    @Override
    public void cancelInference() {
        Interpreter interpreter = mInterpreter;
        if (interpreter != null) interpreter.setCancelled(true);
    }

    @Override
    public WhisperResult processRecordBuffer(float[] samples, Whisper.Action mAction, int mLangToken,
                                             BooleanSupplier isCancelled, ProgressListener progressListener) {
        // Whisper only accepts 30s windows: split longer recordings into chunks, cut at quiet points
        int chunkSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        StringBuilder text = new StringBuilder();
        String language = "";
        Whisper.Action task = null;
        int langToken = mLangToken;
        int chunk = 0;
        int start = 0;
        do {
            if (isCancelled.getAsBoolean()) return null;
            if (progressListener != null) {
                int remaining = (samples.length - start + chunkSize - 1) / chunkSize;
                progressListener.onProgress(chunk, chunk + Math.max(1, remaining));
            }
            int end = findChunkEnd(samples, start, chunkSize);
            if (BuildConfig.DEBUG) Log.d(TAG, "Processing chunk " + start + " - " + end + " of " + samples.length);

            // Calculate Mel spectrogram
            long t0 = System.currentTimeMillis();
            float[] melSpectrogram = getMelSpectrogram(samples, start, end - start);
            long t1 = System.currentTimeMillis();
            if (BuildConfig.DEBUG) Log.d(TAG, "Mel spectrogram is calculated...! " + (t1 - t0) + "ms");

            // Perform inference
            Interpreter interpreter = mInterpreter;
            if (interpreter == null) return null;  // model unloaded meanwhile
            interpreter.setCancelled(false);
            if (isCancelled.getAsBoolean()) return null;  // cancelled while the flag above was being reset
            WhisperResult chunkResult = runInference(melSpectrogram, mAction, langToken);
            if (BuildConfig.DEBUG) Log.d(TAG, "Inference is executed...! " + (System.currentTimeMillis() - t1) + "ms");
            if (isCancelled.getAsBoolean()) return null;

            if (start == 0 && end == samples.length) return chunkResult;  // single chunk: unchanged behaviour

            String chunkText = chunkResult.getResult().trim();
            if (!chunkText.isEmpty()) text.append(' ').append(chunkText);  // leading space like Whisper's own output
            if (language.isEmpty()) {
                language = chunkResult.getLanguage();
                // Auto-detect only once: a quiet later chunk could otherwise be detected as another language
                if (langToken == -1 && mAction == Whisper.Action.TRANSCRIBE && !language.isEmpty()) {
                    langToken = InputLang.getIdForLanguage(InputLang.getLangList(), language);
                }
            }
            if (task == null) task = chunkResult.getTask();
            chunk++;
            start = end;
        } while (start < samples.length);

        return new WhisperResult(text.toString(), language, task);
    }

    // Returns the end index of the chunk starting at start. If the rest exceeds chunkSize,
    // the chunk is cut at the quietest 100ms frame within its last CHUNK_CUT_SEARCH_SECONDS to avoid splitting words.
    private static int findChunkEnd(float[] samples, int start, int chunkSize) {
        if (samples.length - start <= chunkSize) return samples.length;
        int frame = WhisperUtil.WHISPER_SAMPLE_RATE / 10;
        int searchFrom = start + chunkSize - CHUNK_CUT_SEARCH_SECONDS * WhisperUtil.WHISPER_SAMPLE_RATE;
        int bestEnd = start + chunkSize;
        double bestEnergy = Double.MAX_VALUE;
        for (int f = searchFrom; f + frame <= start + chunkSize; f += frame / 2) {
            double energy = 0;
            for (int i = f; i < f + frame; i++) energy += samples[i] * samples[i];
            if (energy <= bestEnergy) {  // <= prefers later frames on ties
                bestEnergy = energy;
                bestEnd = f + frame / 2;
            }
        }
        return bestEnd;
    }


    // Load TFLite model
    private void loadModel(String modelPath) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(modelPath);
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = 0;
        long declaredLength = fileChannel.size();
        ByteBuffer tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

        // Set the number of threads for inference
        Interpreter.Options options = new Interpreter.Options();
        options.setUseXNNPACK(false);  //cannot be used due to dynamic tensors
        options.setNumThreads(Runtime.getRuntime().availableProcessors());
        options.setCancellable(true);

        mInterpreter = new Interpreter(tfliteModel, options);
    }

    private float[] getMelSpectrogram(float[] samples, int offset, int length) {
        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(length, fixedInputSize);

        // Normalize each chunk on its own, so a single loud peak does not attenuate the whole recording
        float maxAbsValue = 0f;
        for (int i = offset; i < offset + copyLength; i++) maxAbsValue = Math.max(maxAbsValue, Math.abs(samples[i]));
        float gain = maxAbsValue > 0f ? 1f / maxAbsValue : 1f;
        for (int i = 0; i < copyLength; i++) inputSamples[i] = samples[offset + i] * gain;

        int cores = Runtime.getRuntime().availableProcessors();
        return mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, copyLength, cores);
    }

    private WhisperResult runInference(float[] inputData, Whisper.Action mAction, int mLangToken) {
        Log.d("Whisper","Signatures "+ Arrays.toString(mInterpreter.getSignatureKeys()));

        // Create input tensor
        Tensor inputTensor = mInterpreter.getInputTensor(0);

        // Create output tensor
        Tensor outputTensor = mInterpreter.getOutputTensor(0);
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32);

        // Load input data
        int inputSize = inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * Float.BYTES;
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(inputSize);
        inputBuffer.order(ByteOrder.nativeOrder());
        for (float input : inputData) {
            inputBuffer.putFloat(input);
        }

        String signature_key = "serving_default";
        if (mAction == Whisper.Action.TRANSLATE) {
            if (Arrays.asList(mInterpreter.getSignatureKeys()).contains("serving_translate")) signature_key = "serving_translate";
        } else if (mAction == Whisper.ACTION_TRANSCRIBE) {
            if (Arrays.asList(mInterpreter.getSignatureKeys()).contains("serving_transcribe_lang") && mLangToken != -1) signature_key = "serving_transcribe_lang";
            else if (Arrays.asList(mInterpreter.getSignatureKeys()).contains("serving_transcribe")) signature_key = "serving_transcribe";
        }

        Map<String, Object> inputsMap = new HashMap<>();
        String[] inputs = mInterpreter.getSignatureInputs(signature_key);
        inputsMap.put(inputs[0], inputBuffer);
        if (signature_key.equals("serving_transcribe_lang")) {
            Log.d(TAG,"Serving_transcribe_lang " + mLangToken);
            IntBuffer langTokenBuffer = IntBuffer.allocate(1);
            langTokenBuffer.put(mLangToken);
            langTokenBuffer.rewind();
            inputsMap.put(inputs[1], langTokenBuffer);
        }

        Map<String, Object> outputsMap = new HashMap<>();
        String[] outputs = mInterpreter.getSignatureOutputs(signature_key);
        outputsMap.put(outputs[0], outputBuffer.getBuffer());

        // Run inference
        try {
            mInterpreter.runSignature(inputsMap, outputsMap, signature_key);
        } catch (Exception e) {
            return new WhisperResult("", "", mAction);
        }

        // Retrieve the results
        ArrayList<InputLang> inputLangList = InputLang.getLangList();
        String language = "";
        Whisper.Action task = null;
        int outputLen = outputBuffer.getIntArray().length;
        Log.d(TAG, "output_len: " + outputLen);
        List<byte[]> resultArray = new ArrayList<>();
        for (int i = 0; i < outputLen; i++) {
            int token = outputBuffer.getBuffer().getInt();
            if (token == mWhisperUtil.getTokenEOT())
                break;

            // Get word for token and Skip additional token
            if (token < mWhisperUtil.getTokenEOT()) {
                byte[] wordBytes = mWhisperUtil.getWordFromToken(token);
                resultArray.add(wordBytes);
            } else {
                if (token == mWhisperUtil.getTokenTranscribe()){
                    Log.d(TAG, "It is Transcription...");
                    task = Whisper.Action.TRANSCRIBE;
                }

                if (token == mWhisperUtil.getTokenTranslate()){
                    Log.d(TAG, "It is Translation...");
                    task = Whisper.Action.TRANSLATE;
                }

                if (token >= 50259 && token <= 50357){
                    language = InputLang.getLanguageCodeById(inputLangList, token);
                    Log.d(TAG, "Detected language code: "+ language);
                }
                byte[] wordBytes = mWhisperUtil.getWordFromToken(token);
                Log.d(TAG, "Skipping token: " + token + ", word: " + new String(wordBytes, StandardCharsets.UTF_8));
            }
        }

        // Calculate the total length of the combined byte array
        int totalLength = 0;
        for (byte[] byteArray : resultArray) {
            totalLength += byteArray.length;
        }

        // Combine the byte arrays into a single byte array
        byte[] combinedBytes = new byte[totalLength];
        int offset = 0;
        for (byte[] byteArray : resultArray) {
            System.arraycopy(byteArray, 0, combinedBytes, offset, byteArray.length);
            offset += byteArray.length;
        }

        return new WhisperResult(new String(combinedBytes, StandardCharsets.UTF_8), language, task);
    }

}
