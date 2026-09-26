package com.whispertflite.utils;

import android.os.CountDownTimer;
import android.widget.ProgressBar;

import com.whispertflite.asr.Recorder;

/**
 * Progress bar that runs down from 100 to 0 over the maximum recording length.
 * Must be started and cancelled on the UI thread.
 */
public class RecordingCountdown {
    private final ProgressBar progressBar;
    private CountDownTimer timer;

    public RecordingCountdown(ProgressBar progressBar) {
        this.progressBar = progressBar;
    }

    public void start() {
        cancel();
        progressBar.setProgress(100);
        timer = new CountDownTimer(Recorder.MAX_RECORDING_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                progressBar.setProgress((int) (millisUntilFinished * 100 / Recorder.MAX_RECORDING_MS));
            }

            @Override
            public void onFinish() {
                progressBar.setProgress(0);
            }
        };
        timer.start();
    }

    public void cancel() {
        if (timer != null) timer.cancel();
        timer = null;
    }
}
