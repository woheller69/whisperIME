package com.whispertflite.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.whispertflite.R;

import java.io.ByteArrayOutputStream;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);

        // Normalized input level 0..1, called for every audio frame while recording
        default void onAmplitudeReceived(float level) {}
    }

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";
    // Longer recordings are split into 30s chunks by the engine. 5 min = 9.6 MB of 16 bit PCM
    // (+ 19 MB as floats while transcribing) and about 10 inference passes, a sensible upper bound.
    public static final int MAX_RECORDING_SECONDS = 300;
    public static final long MAX_RECORDING_MS = MAX_RECORDING_SECONDS * 1000L;

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);  // recording requested / running
    private final AtomicBoolean mBusy = new AtomicBoolean(false);        // worker thread still owns the microphone
    private volatile byte[] mRecordedAudio;                               // 16 bit PCM of the last recording

    private RecorderListener mListener;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private VadWebRTC vad = null;
    private static final int VAD_FRAME_SIZE = 480;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;

        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }


    /** Starts recording; returns false if the previous recording is still being finished. */
    public boolean start() {
        // Refuse while a previous recording is still being finished by the worker thread
        if (!mBusy.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return false;
        }
        mInProgress.set(true);
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
        return true;
    }

    public void initVad(){
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(800)
                .setSpeechDurationMs(200)
                .build();
        useVAD = true;
        Log.d(TAG, "VAD initialized");
    }


    /**
     * Asks the recording thread to stop. Non-blocking (safe on the UI thread): the audio is
     * available via {@link #getRecordedAudio()} once MSG_RECORDING_DONE has been sent.
     */
    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);
    }

    /** 16 bit PCM (16 kHz mono) of the last finished recording, owned by this Recorder instance. */
    public byte[] getRecordedAudio() {
        return mRecordedAudio;
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }


    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
                mBusy.set(false);
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = builder.build();
        audioRecord.startRecording();

        // Calculate maximum byte count for the maximum recording length
        int maxBytes = sampleRateInHz * bytesPerSample * channels * MAX_RECORDING_SECONDS;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Output buffer; truncated to maxBytes before passing to RecordBuffer

        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;

        boolean isSpeech;
        boolean isRecording = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  // One frame at 16-bit (480 samples × 2 bytes)

        while (mInProgress.get() && totalBytesRead < maxBytes) { // Save all bytes read up to maxBytes
            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead > 0) {
                outputBuffer.write(audioData, 0, bytesRead);
                totalBytesRead += bytesRead;
                if (mListener != null) mListener.onAmplitudeReceived(computeLevel(audioData, bytesRead));
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }

            if (useVAD){
                if (bytesRead == VAD_FRAME_SIZE * 2) {
                    // Use the frame just read (16 bit) for VAD; avoids copying the whole (possibly long) output buffer
                    System.arraycopy(audioData, 0, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);

                    isSpeech = vad.isSpeech(vadAudioBuffer);
                    if (isSpeech) {
                        if (!isRecording) {
                            Log.d(TAG, "VAD Speech detected: recording starts");
                            sendUpdate(MSG_RECORDING);
                        }
                        isRecording = true;
                    } else {
                        if (isRecording) {
                            isRecording = false;
                            mInProgress.set(false);
                        }
                    }
                }
            } else {
                if (!isRecording) sendUpdate(MSG_RECORDING);
                isRecording = true;
            }
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

        if (useVAD){
            useVAD = false;
            vad.close();
            vad = null;
            Log.d(TAG, "Closing VAD");
        }
        audioRecord.stop();
        audioRecord.release();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        // Keep the recorded audio (up to MAX_RECORDING_SECONDS) for the transcription
        byte[] data = outputBuffer.toByteArray();
        mRecordedAudio = data.length > maxBytes ? Arrays.copyOf(data, maxBytes) : data;

        if (totalBytesRead > 6400){  //min 0.2s
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }

    }

    // RMS of a 16 bit PCM frame, mapped to 0..1 with a slight boost for quiet speech
    private static float computeLevel(byte[] pcm, int length) {
        int samples = length / 2;
        if (samples == 0) return 0f;
        double sum = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / samples) / 32768.0;
        return (float) Math.min(1.0, Math.sqrt(rms) * 2.5);
    }

}
