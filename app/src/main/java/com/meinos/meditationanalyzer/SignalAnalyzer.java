package com.meinos.meditationanalyzer;

import java.util.Arrays;
import java.util.Locale;

/**
 * Pure-Java real-time DSP engine matching desktop htc_ne20_oscilloscope.py:
 * - High-resolution audio FFT (4096-pt Hanning) + Tinnitus tonal detector (2-16 kHz)
 * - Envelope modulation FFT (100 Hz RMS stream, 2048-pt zero-padded Hanning) for 1-30 Hz:
 *   Delta (1-4 Hz), Theta (4-8 Hz), Schumann Resonance (7.83 Hz), Alpha (8-12 Hz).
 */
public class SignalAnalyzer {

    public static final String BAND_BRAIN = "BRAIN";
    public static final String BAND_TINNITUS = "TINNITUS";
    public static final String BAND_FULL = "FULL";

    public static final int AUDIO_BUF_LEN = 512;
    public static final int AUDIO_FFT_N = 4096;
    public static final int ENV_RATE = 100;
    public static final int ENV_WIN_LEN = 512;
    public static final int ENV_FFT_N = 2048;
    public static final int HISTORY_LEN = 120;

    private volatile int sampleRate = 44100;
    private volatile float gain = 2.0f;
    private volatile String bandMode = BAND_BRAIN;

    // Audio rings
    private final float[] audioWave = new float[AUDIO_BUF_LEN];
    private final float[] fftRing = new float[AUDIO_FFT_N];
    private final float[] audioWin = new float[AUDIO_FFT_N];
    private final float[] audioDb = new float[AUDIO_FFT_N / 2 + 1];
    private final float[] audioPeakHold = new float[AUDIO_FFT_N / 2 + 1];
    private final float[] fftRe = new float[AUDIO_FFT_N];
    private final float[] fftIm = new float[AUDIO_FFT_N];

    // Envelope brainwave buffers (100 Hz)
    private double envSqAccum = 0.0;
    private int envCount = 0;
    private final float[] envRing = new float[ENV_WIN_LEN];
    private final float[] envWin = new float[ENV_WIN_LEN];
    private final float[] brainDb = new float[ENV_FFT_N / 2 + 1];
    private final float[] brainPeakHold = new float[ENV_FFT_N / 2 + 1];
    private final float[] envRe = new float[ENV_FFT_N];
    private final float[] envIm = new float[ENV_FFT_N];

    // Rolling histories (latest at index HISTORY_LEN - 1)
    private final float[] micDbHistory = new float[HISTORY_LEN];
    private final float[] micTinDbHistory = new float[HISTORY_LEN];
    private final float[] micBrainDbHistory = new float[HISTORY_LEN];

    private float vpp = 0f;
    private float vrms = 0f;
    private float currentMicDb = -72f;
    private float currentTinDb = -72f;
    private float currentBrainDb = -60f;
    private double audioPeakHz = 0.0;
    private float audioPeakDb = -72f;
    private float fftSnrDb = 0f;
    private String tinnitusAlert = "";

    private double brainPeakHz = 0.0;
    private String brainBandLabel = "";

    public static class Snapshot {
        public int sampleRate;
        public String bandMode;
        public final float[] waveform = new float[AUDIO_BUF_LEN];
        public final float[] audioDb = new float[AUDIO_FFT_N / 2 + 1];
        public final float[] audioPeakHold = new float[AUDIO_FFT_N / 2 + 1];
        public final float[] brainDb = new float[ENV_FFT_N / 2 + 1];
        public final float[] brainPeakHold = new float[ENV_FFT_N / 2 + 1];
        public final float[] micDbHistory = new float[HISTORY_LEN];
        public final float[] micTinDbHistory = new float[HISTORY_LEN];
        public final float[] micBrainDbHistory = new float[HISTORY_LEN];
        public float vpp;
        public float vrms;
        public float currentMicDb;
        public float currentTinDb;
        public float currentBrainDb;
        public double audioPeakHz;
        public float audioPeakDb;
        public float fftSnrDb;
        public String tinnitusAlert = "";
        public double brainPeakHz;
        public String brainBandLabel = "";
    }

    public SignalAnalyzer() {
        for (int i = 0; i < AUDIO_FFT_N; i++) {
            audioWin[i] = (float) (0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / (AUDIO_FFT_N - 1))));
        }
        for (int i = 0; i < ENV_WIN_LEN; i++) {
            envWin[i] = (float) (0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / (ENV_WIN_LEN - 1))));
        }
        resetState();
    }

    public synchronized void resetState() {
        Arrays.fill(audioWave, 0f);
        Arrays.fill(fftRing, 0f);
        Arrays.fill(envRing, 0f);
        envSqAccum = 0.0;
        envCount = 0;
        Arrays.fill(micDbHistory, -72f);
        Arrays.fill(micTinDbHistory, -72f);
        Arrays.fill(micBrainDbHistory, -60f);
        currentMicDb = -72f;
        currentTinDb = -72f;
        currentBrainDb = -60f;
        resetPeaks();
    }

    public synchronized void resetPeaks() {
        Arrays.fill(audioDb, -72f);
        Arrays.fill(audioPeakHold, -72f);
        Arrays.fill(brainDb, -60f);
        Arrays.fill(brainPeakHold, -60f);
        tinnitusAlert = "";
        audioPeakHz = 0.0;
        audioPeakDb = -72f;
        fftSnrDb = 0f;
        brainPeakHz = 0.0;
        brainBandLabel = "";
    }

    public void setGain(float gain) {
        this.gain = Math.max(0.2f, Math.min(20.0f, gain));
    }

    public float getGain() {
        return gain;
    }

    public void setBandMode(String bandMode) {
        if (BAND_BRAIN.equals(bandMode) || BAND_TINNITUS.equals(bandMode) || BAND_FULL.equals(bandMode)) {
            this.bandMode = bandMode;
        }
    }

    public String getBandMode() {
        return bandMode;
    }

    public synchronized void processPcm(short[] pcm, int length, int sr) {
        if (pcm == null || length <= 0 || sr <= 0) return;
        if (sr != this.sampleRate) {
            this.sampleRate = sr;
            envSqAccum = 0.0;
            envCount = 0;
        }

        // Convert to normalized float, DC-remove
        float sum = 0f;
        float[] norm = new float[length];
        for (int i = 0; i < length; i++) {
            float v = pcm[i] / 32768.0f;
            norm[i] = v;
            sum += v;
        }
        float mean = sum / length;

        double sqTotal = 0.0;
        float minWave = Float.MAX_VALUE;
        float maxWave = -Float.MAX_VALUE;
        float g = this.gain;

        for (int i = 0; i < length; i++) {
            float s = norm[i] - mean;
            norm[i] = s;
            sqTotal += (double) s * s;
        }

        // Downsample or append into waveform display ring (length 512)
        if (length >= AUDIO_BUF_LEN) {
            int start = length - AUDIO_BUF_LEN;
            for (int i = 0; i < AUDIO_BUF_LEN; i++) {
                audioWave[i] = norm[start + i] * g;
            }
        } else {
            System.arraycopy(audioWave, length, audioWave, 0, AUDIO_BUF_LEN - length);
            int dst = AUDIO_BUF_LEN - length;
            for (int i = 0; i < length; i++) {
                audioWave[dst + i] = norm[i] * g;
            }
        }
        double waveSq = 0.0;
        for (int i = 0; i < AUDIO_BUF_LEN; i++) {
            float v = audioWave[i];
            if (v < minWave) minWave = v;
            if (v > maxWave) maxWave = v;
            waveSq += (double) v * v;
        }
        this.vpp = Math.max(0f, maxWave - minWave);
        this.vrms = (float) Math.sqrt(waveSq / AUDIO_BUF_LEN);

        // Append into 4096 FFT ring
        if (length >= AUDIO_FFT_N) {
            System.arraycopy(norm, length - AUDIO_FFT_N, fftRing, 0, AUDIO_FFT_N);
        } else {
            System.arraycopy(fftRing, length, fftRing, 0, AUDIO_FFT_N - length);
            System.arraycopy(norm, 0, fftRing, AUDIO_FFT_N - length, length);
        }

        // Compute Audio FFT
        for (int i = 0; i < AUDIO_FFT_N; i++) {
            fftRe[i] = fftRing[i] * audioWin[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);

        float magScale = Math.max(1.0f, g * 0.5f);
        float normDiv = AUDIO_FFT_N * 0.25f;
        int halfAudioBins = AUDIO_FFT_N / 2 + 1;
        double binHz = sr / (double) AUDIO_FFT_N;
        double nyqCap = sr * 0.49;

        float tinMaxDb = -72f;
        for (int b = 0; b < halfAudioBins; b++) {
            float mag = (float) Math.hypot(fftRe[b], fftIm[b]) / normDiv;
            float db = Math.max(-72f, Math.min(0f, (float) (20.0 * Math.log10(Math.max(1e-5f, mag * magScale)))));
            audioDb[b] = db;
            audioPeakHold[b] = Math.max(db, audioPeakHold[b] - 0.35f);
            double hz = b * binHz;
            if (hz >= 2000.0 && hz <= Math.min(16000.0, nyqCap)) {
                if (db > tinMaxDb) tinMaxDb = db;
            }
        }

        float rawRms = (float) Math.sqrt(sqTotal / length);
        float micDb = Math.max(-72f, Math.min(0f, (float) (20.0 * Math.log10(Math.max(1e-5f, rawRms)))));
        this.currentMicDb = micDb;
        this.currentTinDb = Math.max(-72f, Math.min(0f, tinMaxDb));
        pushHistory(micDbHistory, this.currentMicDb);
        pushHistory(micTinDbHistory, this.currentTinDb);

        // Update Audio Peak / Tinnitus tone detector in active band
        double fmin = BAND_TINNITUS.equals(bandMode) ? 1800.0 : 60.0;
        double fmax = Math.min(16000.0, nyqCap);
        int bStart = Math.max(1, (int) Math.ceil(fmin / binHz));
        int bEnd = Math.min(halfAudioBins - 1, (int) Math.floor(fmax / binHz));
        if (bEnd >= bStart) {
            int count = bEnd - bStart + 1;
            float[] slice = new float[count];
            int bestIdx = bStart;
            float bestDb = -100f;
            for (int b = bStart; b <= bEnd; b++) {
                float d = audioDb[b];
                slice[b - bStart] = d;
                if (d > bestDb) {
                    bestDb = d;
                    bestIdx = b;
                }
            }
            Arrays.sort(slice);
            float medianDb = slice[count / 2];
            float snr = Math.max(0f, bestDb - medianDb);
            this.audioPeakHz = bestIdx * binHz;
            this.audioPeakDb = bestDb;
            this.fftSnrDb = snr;
            if (snr >= 10.0f && bestDb > -48.0f && (this.audioPeakHz >= 1800.0 || BAND_TINNITUS.equals(bandMode))) {
                this.tinnitusAlert = String.format(Locale.US, "TONAL/TINNITUS: %.0f Hz (+%.1f dB)", this.audioPeakHz, snr);
            } else {
                this.tinnitusAlert = "";
            }
        }

        // Accumulate 100 Hz RMS envelope frames for Brainwave Modulation FFT (1-30 Hz)
        int envStep = Math.max(1, (int) Math.round(sr / (double) ENV_RATE));
        boolean updatedEnv = false;
        for (int i = 0; i < length; i++) {
            double v = norm[i];
            envSqAccum += v * v;
            envCount++;
            if (envCount >= envStep) {
                float envVal = (float) Math.sqrt(envSqAccum / envCount);
                envSqAccum = 0.0;
                envCount = 0;
                System.arraycopy(envRing, 1, envRing, 0, ENV_WIN_LEN - 1);
                envRing[ENV_WIN_LEN - 1] = envVal;
                updatedEnv = true;
            }
        }

        if (updatedEnv) {
            computeBrainEnvelopeFft();
        }
    }

    private void computeBrainEnvelopeFft() {
        double sum = 0.0;
        for (int i = 0; i < ENV_WIN_LEN; i++) {
            sum += envRing[i];
        }
        double meanEnv = sum / ENV_WIN_LEN;
        int halfBrainBins = ENV_FFT_N / 2 + 1;

        if (meanEnv > 5e-4) {
            Arrays.fill(envRe, 0f);
            Arrays.fill(envIm, 0f);
            for (int i = 0; i < ENV_WIN_LEN; i++) {
                double ac = (envRing[i] - meanEnv) / (meanEnv + 1e-4);
                envRe[i] = (float) (ac * envWin[i]);
            }
            fft(envRe, envIm);
            float normDiv = ENV_WIN_LEN * 0.25f;
            for (int b = 0; b < halfBrainBins; b++) {
                float mag = (float) Math.hypot(envRe[b], envIm[b]) / normDiv;
                float edb = Math.max(-60f, Math.min(0f, (float) (20.0 * Math.log10(Math.max(1e-4f, mag)))));
                brainDb[b] = edb;
                brainPeakHold[b] = Math.max(edb, brainPeakHold[b] - 0.25f);
            }
        } else {
            for (int b = 0; b < halfBrainBins; b++) {
                brainDb[b] = -60f;
                brainPeakHold[b] = Math.max(-60f, brainPeakHold[b] - 0.25f);
            }
        }

        double envBinHz = ENV_RATE / (double) ENV_FFT_N;
        int taStart = Math.max(1, (int) Math.ceil(4.0 / envBinHz));
        int taEnd = Math.min(halfBrainBins - 1, (int) Math.floor(12.0 / envBinHz));
        float taMax = -60f;
        for (int b = taStart; b <= taEnd; b++) {
            if (brainDb[b] > taMax) taMax = brainDb[b];
        }
        this.currentBrainDb = taMax;
        pushHistory(micBrainDbHistory, this.currentBrainDb);

        int bStart = Math.max(1, (int) Math.ceil(1.5 / envBinHz));
        int bEnd = Math.min(halfBrainBins - 1, (int) Math.floor(30.0 / envBinHz));
        int bestIdx = bStart;
        float bestDb = -60f;
        for (int b = bStart; b <= bEnd; b++) {
            if (brainDb[b] > bestDb) {
                bestDb = brainDb[b];
                bestIdx = b;
            }
        }
        double peakHz = bestIdx * envBinHz;
        if (bestDb > -38.0f) {
            this.brainPeakHz = peakHz;
            this.brainBandLabel = classifyBrainHz(peakHz);
        } else {
            this.brainPeakHz = 0.0;
            this.brainBandLabel = "";
        }
    }

    public static String classifyBrainHz(double hz) {
        if (hz >= 7.45 && hz <= 8.20) {
            return String.format(Locale.US, "SCHUMANN %.2f Hz", hz);
        } else if (hz >= 4.0 && hz < 8.0) {
            return String.format(Locale.US, "THETA %.1f Hz", hz);
        } else if (hz >= 8.0 && hz <= 12.0) {
            return String.format(Locale.US, "ALPHA %.1f Hz", hz);
        } else if (hz < 4.0) {
            return String.format(Locale.US, "DELTA %.1f Hz", hz);
        } else {
            return String.format(Locale.US, "BETA %.1f Hz", hz);
        }
    }

    private static void pushHistory(float[] hist, float val) {
        System.arraycopy(hist, 1, hist, 0, hist.length - 1);
        hist[hist.length - 1] = val;
    }

    public synchronized void copySnapshot(Snapshot out) {
        out.sampleRate = this.sampleRate;
        out.bandMode = this.bandMode;
        System.arraycopy(audioWave, 0, out.waveform, 0, AUDIO_BUF_LEN);
        System.arraycopy(audioDb, 0, out.audioDb, 0, audioDb.length);
        System.arraycopy(audioPeakHold, 0, out.audioPeakHold, 0, audioPeakHold.length);
        System.arraycopy(brainDb, 0, out.brainDb, 0, brainDb.length);
        System.arraycopy(brainPeakHold, 0, out.brainPeakHold, 0, brainPeakHold.length);
        System.arraycopy(micDbHistory, 0, out.micDbHistory, 0, HISTORY_LEN);
        System.arraycopy(micTinDbHistory, 0, out.micTinDbHistory, 0, HISTORY_LEN);
        System.arraycopy(micBrainDbHistory, 0, out.micBrainDbHistory, 0, HISTORY_LEN);
        out.vpp = this.vpp;
        out.vrms = this.vrms;
        out.currentMicDb = this.currentMicDb;
        out.currentTinDb = this.currentTinDb;
        out.currentBrainDb = this.currentBrainDb;
        out.audioPeakHz = this.audioPeakHz;
        out.audioPeakDb = this.audioPeakDb;
        out.fftSnrDb = this.fftSnrDb;
        out.tinnitusAlert = this.tinnitusAlert;
        out.brainPeakHz = this.brainPeakHz;
        out.brainBandLabel = this.brainBandLabel;
    }

    /** In-place iterative Radix-2 Cooley-Tukey FFT (length must be power of 2). */
    public static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wlenRe = Math.cos(ang);
            double wlenIm = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double wRe = 1.0;
                double wIm = 0.0;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    double tRe = wRe * re[v] - wIm * im[v];
                    double tIm = wRe * im[v] + wIm * re[v];
                    re[v] = (float) (re[u] - tRe);
                    im[v] = (float) (im[u] - tIm);
                    re[u] = (float) (re[u] + tRe);
                    im[u] = (float) (im[u] + tIm);
                    double nextWRe = wRe * wlenRe - wIm * wlenIm;
                    wIm = wRe * wlenIm + wIm * wlenRe;
                    wRe = nextWRe;
                }
            }
        }
    }
}
