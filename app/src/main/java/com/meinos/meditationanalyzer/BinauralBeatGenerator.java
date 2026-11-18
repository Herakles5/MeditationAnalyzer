package com.meinos.meditationanalyzer;

import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Click-free local stereo Binaural Beat Synthesizer (44.1 kHz, 16-bit PCM).
 * Left channel emits carrierHz; Right channel emits carrierHz + beatHz.
 */
public class BinauralBeatGenerator {

    public static final int SAMPLE_RATE = 44100;

    public interface MonitorListener {
        void onMonitorMonoChunk(short[] monoPcm, int length, int sampleRate);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile double beatHz = 7.83;
    private volatile double carrierHz = 200.0;
    private volatile float volume = 0.25f;
    private volatile MonitorListener monitorListener;

    private Thread workerThread;

    public void setBeatHz(double beatHz) {
        this.beatHz = Math.max(0.5, Math.min(35.0, beatHz));
    }

    public double getBeatHz() {
        return beatHz;
    }

    public void setCarrierHz(double carrierHz) {
        this.carrierHz = Math.max(60.0, Math.min(800.0, carrierHz));
    }

    public double getCarrierHz() {
        return carrierHz;
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0.0f, Math.min(0.85f, vol));
    }

    public void setMonitorListener(MonitorListener listener) {
        this.monitorListener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        workerThread = new Thread(this::runSynthLoop, "BinauralBeatThread");
        workerThread.setPriority(Thread.MAX_PRIORITY);
        workerThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            try {
                workerThread.join(400);
            } catch (InterruptedException ignored) {}
            workerThread = null;
        }
    }

    private void runSynthLoop() {
        int minBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int framesPerChunk = 1024; // ~23.2 ms
        int trackBytes = Math.max(minBytes, framesPerChunk * 4 * 4);

        AudioTrack track = null;
        try {
            track = AudioCaptureEngine.createStreamAudioTrack(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, trackBytes);

            track.play();

            short[] stereoBuf = new short[framesPerChunk * 2];
            short[] monitorMono = new short[framesPerChunk];
            double phaseL = 0.0;
            double phaseR = 0.0;
            double fade = 0.0;
            final double twoPi = 2.0 * Math.PI;

            while (running.get()) {
                double fL = this.carrierHz;
                double fR = fL + this.beatHz;
                double incL = twoPi * fL / SAMPLE_RATE;
                double incR = twoPi * fR / SAMPLE_RATE;
                float targetAmp = this.volume;

                for (int i = 0; i < framesPerChunk; i++) {
                    if (fade < 1.0) {
                        fade = Math.min(1.0, fade + (1.0 / (SAMPLE_RATE * 0.12)));
                    }
                    double amp = targetAmp * fade;
                    double sL = Math.sin(phaseL) * amp;
                    double sR = Math.sin(phaseR) * amp;

                    phaseL += incL;
                    phaseR += incR;
                    if (phaseL >= twoPi) phaseL -= twoPi;
                    if (phaseR >= twoPi) phaseR -= twoPi;

                    short pcmL = (short) Math.max(-32767, Math.min(32767, sL * 32767.0));
                    short pcmR = (short) Math.max(-32767, Math.min(32767, sR * 32767.0));
                    stereoBuf[i * 2] = pcmL;
                    stereoBuf[i * 2 + 1] = pcmR;

                    // Acoustic interference sum (L + R) / 2 shows exact beat modulation envelope
                    monitorMono[i] = (short) (((int) pcmL + (int) pcmR) >> 1);
                }

                track.write(stereoBuf, 0, stereoBuf.length);

                MonitorListener ml = this.monitorListener;
                if (ml != null) {
                    ml.onMonitorMonoChunk(monitorMono, framesPerChunk, SAMPLE_RATE);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                    track.release();
                } catch (Exception ignored) {}
            }
        }
    }
}
