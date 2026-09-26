package com.whispertflite.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Conversion of recorded audio for the Whisper engine.
 * (Used to hold the last recording in a static field; the audio is now passed per instance,
 * so the floating mic, keyboard and app can no longer overwrite each other's recording.)
 */
public class RecordBuffer {

    // 16 bit PCM (native byte order, as written by AudioRecord) to floats in [-1, 1).
    // Not normalized: the engine normalizes each 30 s chunk separately.
    public static float[] getSamples(byte[] pcm) {
        int numSamples = pcm.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(pcm);
        byteBuffer.order(ByteOrder.nativeOrder());

        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            samples[i] = byteBuffer.getShort() / 32768.0f;
        }
        return samples;
    }
}
