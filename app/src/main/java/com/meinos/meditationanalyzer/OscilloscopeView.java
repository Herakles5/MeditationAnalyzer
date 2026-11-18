package com.meinos.meditationanalyzer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

/**
 * 3-in-1 Meditation & Acoustic Oscilloscope View:
 * - CH2 (Top): Mic RMS Ruhe-Telemetrie (-72..0 dBFS) + θ/α (4-12 Hz) oder Tinnitus (2-16 kHz)
 * - CH1 (Mid): Echtzeit Audio-Wellenform
 * - CH3 (Bot): Brainwave Modulation FFT (1-30 Hz: Delta / Theta / 7.83 Schumann / Alpha)
 *              oder Audio-Spektrum (Tinnitus 2-16 kHz / Full 0-16 kHz)
 */
public class OscilloscopeView extends View {

    public static final int MODE_ALL3 = 0;
    public static final int MODE_DUAL = 1;
    public static final int MODE_FFT = 2;

    private final float density;
    private final SignalAnalyzer.Snapshot snap = new SignalAnalyzer.Snapshot();
    private volatile boolean hasData = false;
    private volatile int displayMode = MODE_ALL3;
    private volatile float displayGain = 2.0f;
    private volatile String sourceLabel = "HANDY-MIC";

    private Paint gridPaint;
    private Paint centerPaint;
    private Paint dashLinePaint;
    private Paint waveGlowPaint;
    private Paint wavePaint;
    private Paint micGlowPaint;
    private Paint micPaint;
    private Paint secPaint;
    private Paint peakLinePaint;
    private Paint schumannLinePaint;
    private Paint fillPaint;
    private Paint titlePaint;
    private Paint labelPaint;
    private Paint axisPaint;
    private Paint dotPaint;

    private final Path wavePath = new Path();
    private final Path micPath = new Path();
    private final Path secPath = new Path();
    private final Path peakPath = new Path();

    public OscilloscopeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.density = context.getResources().getDisplayMetrics().density;
        initPaints();
    }

    private float dp(float v) {
        return v * density;
    }

    private void initPaints() {
        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#0D1B1A"));
        gridPaint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        gridPaint.setStyle(Paint.Style.STROKE);

        centerPaint = new Paint();
        centerPaint.setColor(Color.parseColor("#163834"));
        centerPaint.setStrokeWidth(Math.max(1.2f, dp(1.0f)));
        centerPaint.setStyle(Paint.Style.STROKE);

        dashLinePaint = new Paint();
        dashLinePaint.setColor(Color.parseColor("#172B36"));
        dashLinePaint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        dashLinePaint.setStyle(Paint.Style.STROKE);
        dashLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));

        waveGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        waveGlowPaint.setColor(Color.parseColor("#004438"));
        waveGlowPaint.setStrokeWidth(dp(2.8f));
        waveGlowPaint.setStyle(Paint.Style.STROKE);

        wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setColor(Color.parseColor("#00E5FF"));
        wavePaint.setStrokeWidth(dp(1.4f));
        wavePaint.setStyle(Paint.Style.STROKE);

        micGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        micGlowPaint.setColor(Color.parseColor("#004D22"));
        micGlowPaint.setStrokeWidth(dp(3.0f));
        micGlowPaint.setStyle(Paint.Style.STROKE);

        micPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        micPaint.setColor(Color.parseColor("#00FF66"));
        micPaint.setStrokeWidth(dp(1.5f));
        micPaint.setStyle(Paint.Style.STROKE);

        secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secPaint.setColor(Color.parseColor("#8BE9FD"));
        secPaint.setStrokeWidth(dp(1.2f));
        secPaint.setStyle(Paint.Style.STROKE);
        secPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(2.5f)}, 0));

        peakLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        peakLinePaint.setColor(Color.parseColor("#F1FA8C"));
        peakLinePaint.setStrokeWidth(dp(1.1f));
        peakLinePaint.setStyle(Paint.Style.STROKE);

        schumannLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        schumannLinePaint.setColor(Color.parseColor("#FF79C6"));
        schumannLinePaint.setStrokeWidth(dp(1.3f));
        schumannLinePaint.setStyle(Paint.Style.STROKE);
        schumannLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));

        fillPaint = new Paint();
        fillPaint.setStyle(Paint.Style.FILL);

        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(dp(9.5f));
        titlePaint.setFakeBoldText(true);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTextSize(dp(8.2f));
        labelPaint.setFakeBoldText(true);

        axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisPaint.setTextSize(dp(7.4f));

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setDisplayMode(int mode) {
        this.displayMode = mode;
        postInvalidate();
    }

    public void setDisplayGain(float gain) {
        this.displayGain = gain;
    }

    public void setSourceLabel(String label) {
        this.sourceLabel = label != null ? label : "AUDIO";
        postInvalidate();
    }

    public void updateFromAnalyzer(SignalAnalyzer analyzer) {
        analyzer.copySnapshot(snap);
        hasData = true;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 20 || h <= 20) return;

        // Laboratory background graticule
        int cols = 8;
        int rows = 6;
        for (int i = 1; i < cols; i++) {
            float x = i * (w / (float) cols);
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (int j = 1; j < rows; j++) {
            float y = j * (h / (float) rows);
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        if (!hasData) {
            titlePaint.setColor(Color.parseColor("#50FA7B"));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("MEDITATION ANALYZER — BEREIT", w * 0.5f, h * 0.5f, titlePaint);
            titlePaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        if (displayMode == MODE_ALL3) {
            float h1 = h * 0.24f;
            float h2 = h * 0.31f;
            float h3 = h - (h1 + h2);
            drawMicTelemetry(canvas, 0f, 0f, w, h1);
            drawWaveform(canvas, 0f, h1, w, h2);
            drawSpectrum(canvas, 0f, h1 + h2, w, h3);
        } else if (displayMode == MODE_DUAL) {
            float hTop = h * 0.44f;
            float hBot = h - hTop;
            drawWaveform(canvas, 0f, 0f, w, hTop);
            drawSpectrum(canvas, 0f, hTop, w, hBot);
        } else {
            drawSpectrum(canvas, 0f, 0f, w, h);
        }
    }

    private void drawMicTelemetry(Canvas canvas, float ox, float oy, float ow, float oh) {
        boolean isBrain = SignalAnalyzer.BAND_BRAIN.equals(snap.bandMode);

        titlePaint.setColor(Color.parseColor("#50FA7B"));
        titlePaint.setTextAlign(Paint.Align.LEFT);
        String title = (isBrain ? "CH2: MIC RUHE & θ/α [" : "CH2: MIC TELEMETRY [") + sourceLabel + "]";
        canvas.drawText(title, ox + dp(6), oy + dp(12), titlePaint);

        String valTxt;
        if (isBrain) {
            String bLbl = (snap.brainBandLabel == null || snap.brainBandLabel.isEmpty()) ? "1-30Hz" : snap.brainBandLabel;
            valTxt = String.format(Locale.US, "MIC %.0fdB | θ/α %.0fdB | %s", snap.currentMicDb, snap.currentBrainDb, bLbl);
            labelPaint.setColor(bLbl.contains("SCHUMANN") ? Color.parseColor("#FF79C6") : Color.parseColor("#8BE9FD"));
        } else {
            valTxt = String.format(Locale.US, "MIC %.0fdB | TIN %.0fdB | PK %.0fHz", snap.currentMicDb, snap.currentTinDb, snap.audioPeakHz);
            labelPaint.setColor(snap.tinnitusAlert.isEmpty() ? Color.parseColor("#FFB86C") : Color.parseColor("#FF79C6"));
        }
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(valTxt, ox + ow - dp(6), oy + dp(12), labelPaint);

        float dbMin = -72f;
        float dbMax = 0f;
        float topY = oy + dp(17);
        float botY = oy + Math.max(dp(24), oh - dp(5));
        float plotH = Math.max(dp(8), botY - topY);

        axisPaint.setTextAlign(Paint.Align.RIGHT);
        axisPaint.setColor(Color.parseColor("#356854"));
        int[] refs = {-10, -30, -50, -68};
        String[] refNames = {"-10dB LAUT", "-30dB SPRACHE", "-50dB LEISE", "-68dB RUHE"};
        for (int r = 0; r < refs.length; r++) {
            float ny = (refs[r] - dbMin) / (dbMax - dbMin);
            float y = botY - ny * plotH;
            canvas.drawLine(ox, y, ox + ow, y, dashLinePaint);
            canvas.drawText(refNames[r], ox + ow - dp(4), y - dp(2), axisPaint);
        }

        float[] secArr = isBrain ? snap.micBrainDbHistory : snap.micTinDbHistory;
        secPaint.setColor(Color.parseColor(isBrain ? "#8BE9FD" : "#FF79C6"));
        buildDbHistoryPath(secPath, secArr, ox, ow, botY, plotH, dbMin, dbMax);
        canvas.drawPath(secPath, secPaint);

        buildDbHistoryPath(micPath, snap.micDbHistory, ox, ow, botY, plotH, dbMin, dbMax);
        canvas.drawPath(micPath, micGlowPaint);
        canvas.drawPath(micPath, micPaint);

        float lastVal = snap.micDbHistory[snap.micDbHistory.length - 1];
        float lastNy = Math.max(0f, Math.min(1f, (lastVal - dbMin) / (dbMax - dbMin)));
        float lastY = botY - lastNy * plotH;
        dotPaint.setColor(Color.WHITE);
        canvas.drawCircle(ox + ow - dp(3), lastY, dp(2.8f), dotPaint);
        canvas.drawLine(ox, oy + oh, ox + ow, oy + oh, centerPaint);
    }

    private void buildDbHistoryPath(Path path, float[] hist, float ox, float ow, float botY, float plotH, float dbMin, float dbMax) {
        path.reset();
        int n = hist.length;
        for (int i = 0; i < n; i++) {
            float x = ox + (i / (float) (n - 1)) * ow;
            float ny = Math.max(0f, Math.min(1f, (hist[i] - dbMin) / (dbMax - dbMin)));
            float y = botY - ny * plotH;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
    }

    private void drawWaveform(Canvas canvas, float ox, float oy, float ow, float oh) {
        titlePaint.setColor(Color.parseColor("#00E5FF"));
        titlePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("CH1: AUDIO WAVEFORM [" + sourceLabel + "]", ox + dp(6), oy + dp(12), titlePaint);

        labelPaint.setColor(Color.parseColor("#50FA7B"));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.US, "GAIN x%.1f | %.2fV", displayGain, snap.vpp), ox + ow - dp(6), oy + dp(12), labelPaint);

        float cy = oy + oh * 0.54f;
        canvas.drawLine(ox, cy, ox + ow, cy, dashLinePaint);

        wavePath.reset();
        float[] wave = snap.waveform;
        float maxH = oh * 0.38f;
        for (int i = 0; i < wave.length; i++) {
            float x = ox + (i / (float) (wave.length - 1)) * ow;
            float clamped = Math.max(-1.05f, Math.min(1.05f, wave[i]));
            float y = cy - clamped * maxH;
            if (i == 0) wavePath.moveTo(x, y);
            else wavePath.lineTo(x, y);
        }
        canvas.drawPath(wavePath, waveGlowPaint);
        canvas.drawPath(wavePath, wavePaint);
        canvas.drawLine(ox, oy + oh, ox + ow, oy + oh, centerPaint);
    }

    private void drawSpectrum(Canvas canvas, float ox, float oy, float ow, float oh) {
        if (SignalAnalyzer.BAND_BRAIN.equals(snap.bandMode)) {
            drawBrainSpectrum(canvas, ox, oy, ow, oh);
        } else {
            drawAudioSpectrum(canvas, ox, oy, ow, oh);
        }
    }

    private void drawBrainSpectrum(Canvas canvas, float ox, float oy, float ow, float oh) {
        titlePaint.setColor(Color.parseColor("#BD93F9"));
        titlePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("CH3: BRAINWAVE ENVELOPE (1-30Hz)", ox + dp(6), oy + dp(12), titlePaint);

        String rightTxt = (snap.brainBandLabel != null && !snap.brainBandLabel.isEmpty())
                ? snap.brainBandLabel
                : "θ 4-8 | 7.83 | α 8-12";
        labelPaint.setColor(rightTxt.contains("SCHUMANN") ? Color.parseColor("#FF79C6") : Color.parseColor("#8BE9FD"));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(rightTxt, ox + ow - dp(6), oy + dp(12), labelPaint);

        float fmin = 1.0f;
        float fmax = 30.0f;
        float topY = oy + dp(18);
        float botY = oy + oh - dp(15);
        float plotH = Math.max(dp(14), botY - topY);

        // Delta, Theta, Alpha shaded background zones
        drawBandBox(canvas, ox, ow, fmin, fmax, 1f, 4f, topY, botY, "#101726", "#4F6282", "δ");
        drawBandBox(canvas, ox, ow, fmin, fmax, 4f, 8f, topY, botY, "#191229", "#8E6CC9", "θ THETA");
        drawBandBox(canvas, ox, ow, fmin, fmax, 8f, 12f, topY, botY, "#0F2228", "#4AA8BC", "α ALPHA");

        // Vertical Schumann 7.83 Hz marker
        float sx = ox + ((7.83f - fmin) / (fmax - fmin)) * ow;
        canvas.drawLine(sx, topY + dp(9), sx, botY, schumannLinePaint);
        axisPaint.setTextAlign(Paint.Align.LEFT);
        axisPaint.setColor(Color.parseColor("#FF79C6"));
        canvas.drawText("7.83Hz", sx + dp(2), topY + dp(17), axisPaint);

        float dbFloor = -60f;
        float dbCeil = 0f;
        axisPaint.setColor(Color.parseColor("#3C5A70"));
        for (int ref = -50; ref <= -10; ref += 10) {
            float ny = (ref - dbFloor) / (dbCeil - dbFloor);
            float ry = botY - ny * plotH;
            canvas.drawLine(ox, ry, ox + ow, ry, dashLinePaint);
            canvas.drawText(ref + "dB", ox + dp(3), ry - dp(2), axisPaint);
        }

        double envBinHz = SignalAnalyzer.ENV_RATE / (double) SignalAnalyzer.ENV_FFT_N;
        int bStart = Math.max(1, (int) Math.ceil(fmin / envBinHz));
        int bEnd = Math.min(snap.brainDb.length - 1, (int) Math.floor(fmax / envBinHz));
        int span = Math.max(1, bEnd - bStart + 1);
        int numBars = Math.min(140, span);
        float barW = ow / (float) numBars;

        peakPath.reset();
        for (int bi = 0; bi < numBars; bi++) {
            int i0 = bStart + (bi * span) / numBars;
            int i1 = Math.max(i0 + 1, bStart + ((bi + 1) * span) / numBars);
            i1 = Math.min(i1, bEnd + 1);
            float maxDb = dbFloor;
            float maxPk = dbFloor;
            for (int k = i0; k < i1; k++) {
                if (snap.brainDb[k] > maxDb) maxDb = snap.brainDb[k];
                if (snap.brainPeakHold[k] > maxPk) maxPk = snap.brainPeakHold[k];
            }
            float normH = Math.max(0f, Math.min(1f, (maxDb - dbFloor) / (dbCeil - dbFloor)));
            float normPk = Math.max(0f, Math.min(1f, (maxPk - dbFloor) / (dbCeil - dbFloor)));
            float x1 = ox + bi * barW;
            float x2 = x1 + Math.max(1.2f, barW - dp(0.7f));
            float y1 = botY - normH * plotH;
            float yp = botY - normPk * plotH;

            double centerHz = ((i0 + i1 - 1) * 0.5) * envBinHz;
            if (centerHz >= 7.45 && centerHz <= 8.20) fillPaint.setColor(Color.parseColor("#FF79C6"));
            else if (centerHz >= 4.0 && centerHz < 8.0) fillPaint.setColor(Color.parseColor("#BD93F9"));
            else if (centerHz >= 8.0 && centerHz <= 12.0) fillPaint.setColor(Color.parseColor("#8BE9FD"));
            else if (centerHz < 4.0) fillPaint.setColor(Color.parseColor("#6272A4"));
            else fillPaint.setColor(Color.parseColor("#50FA7B"));

            if (normH > 0.02f) {
                canvas.drawRect(x1, y1, x2, botY, fillPaint);
            }
            float cx = (x1 + x2) * 0.5f;
            if (bi == 0) peakPath.moveTo(cx, yp);
            else peakPath.lineTo(cx, yp);
        }
        canvas.drawPath(peakPath, peakLinePaint);

        int[] ticks = {1, 2, 4, 6, 8, 10, 12, 16, 20, 25, 30};
        axisPaint.setTextAlign(Paint.Align.CENTER);
        axisPaint.setColor(Color.parseColor("#7089A3"));
        for (int t : ticks) {
            float fx = ox + ((t - fmin) / (fmax - fmin)) * ow;
            canvas.drawLine(fx, botY, fx, botY + dp(3), centerPaint);
            canvas.drawText(t + "", fx, botY + dp(11), axisPaint);
        }
        canvas.drawLine(ox, botY, ox + ow, botY, centerPaint);
    }

    private void drawBandBox(Canvas canvas, float ox, float ow, float fmin, float fmax,
                             float b0, float b1, float topY, float botY,
                             String fillHex, String txtHex, String text) {
        float x1 = ox + ((b0 - fmin) / (fmax - fmin)) * ow;
        float x2 = ox + ((b1 - fmin) / (fmax - fmin)) * ow;
        fillPaint.setColor(Color.parseColor(fillHex));
        canvas.drawRect(x1, topY, x2, botY, fillPaint);
        axisPaint.setColor(Color.parseColor(txtHex));
        axisPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, (x1 + x2) * 0.5f, topY + dp(8), axisPaint);
    }

    private void drawAudioSpectrum(Canvas canvas, float ox, float oy, float ow, float oh) {
        boolean isTinnitus = SignalAnalyzer.BAND_TINNITUS.equals(snap.bandMode);
        float fmin = isTinnitus ? 2000f : 0f;
        float fmax = Math.min(16000f, snap.sampleRate * 0.49f);

        titlePaint.setColor(Color.parseColor("#FF79C6"));
        titlePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(isTinnitus ? "CH3: TINNITUS (2-16kHz)" : "CH3: FULL SPEKTRUM (0-16kHz)", ox + dp(6), oy + dp(12), titlePaint);

        String rightTxt = !snap.tinnitusAlert.isEmpty() ? snap.tinnitusAlert : String.format(Locale.US, "PK: %.0fHz", snap.audioPeakHz);
        labelPaint.setColor(!snap.tinnitusAlert.isEmpty() ? Color.parseColor("#FF5555") : Color.parseColor("#8BE9FD"));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(rightTxt, ox + ow - dp(6), oy + dp(12), labelPaint);

        float topY = oy + dp(18);
        float botY = oy + oh - dp(15);
        float plotH = Math.max(dp(14), botY - topY);

        if (!isTinnitus && fmax > 2500f) {
            drawBandBox(canvas, ox, ow, fmin, fmax, 2000f, Math.min(12000f, fmax), topY, botY, "#190E1C", "#7A4287", "TINNITUS 2k-12k");
        }

        float dbFloor = -72f;
        float dbCeil = 0f;
        axisPaint.setTextAlign(Paint.Align.LEFT);
        axisPaint.setColor(Color.parseColor("#3C5A70"));
        for (int ref : new int[]{-60, -40, -20}) {
            float ny = (ref - dbFloor) / (dbCeil - dbFloor);
            float ry = botY - ny * plotH;
            canvas.drawLine(ox, ry, ox + ow, ry, dashLinePaint);
            canvas.drawText(ref + "dB", ox + dp(3), ry - dp(2), axisPaint);
        }

        double binHz = snap.sampleRate / (double) SignalAnalyzer.AUDIO_FFT_N;
        int bStart = Math.max(1, (int) Math.ceil(fmin / binHz));
        int bEnd = Math.min(snap.audioDb.length - 1, (int) Math.floor(fmax / binHz));
        int span = Math.max(1, bEnd - bStart + 1);
        int numBars = Math.min(140, span);
        float barW = ow / (float) numBars;

        peakPath.reset();
        for (int bi = 0; bi < numBars; bi++) {
            int i0 = bStart + (bi * span) / numBars;
            int i1 = Math.max(i0 + 1, bStart + ((bi + 1) * span) / numBars);
            i1 = Math.min(i1, bEnd + 1);
            float maxDb = dbFloor;
            float maxPk = dbFloor;
            for (int k = i0; k < i1; k++) {
                if (snap.audioDb[k] > maxDb) maxDb = snap.audioDb[k];
                if (snap.audioPeakHold[k] > maxPk) maxPk = snap.audioPeakHold[k];
            }
            float normH = Math.max(0f, Math.min(1f, (maxDb - dbFloor) / (dbCeil - dbFloor)));
            float normPk = Math.max(0f, Math.min(1f, (maxPk - dbFloor) / (dbCeil - dbFloor)));
            float x1 = ox + bi * barW;
            float x2 = x1 + Math.max(1.2f, barW - dp(0.7f));
            float y1 = botY - normH * plotH;
            float yp = botY - normPk * plotH;

            double centerHz = ((i0 + i1 - 1) * 0.5) * binHz;
            boolean inTin = centerHz >= 2000.0 && centerHz <= 12000.0;
            if (normH > 0.72f) fillPaint.setColor(Color.parseColor("#FF5555"));
            else if (normH > 0.45f) fillPaint.setColor(Color.parseColor(inTin ? "#FFB86C" : "#F1FA8C"));
            else fillPaint.setColor(Color.parseColor(inTin ? "#BD93F9" : "#00FF88"));

            if (normH > 0.02f) {
                canvas.drawRect(x1, y1, x2, botY, fillPaint);
            }
            float cx = (x1 + x2) * 0.5f;
            if (bi == 0) peakPath.moveTo(cx, yp);
            else peakPath.lineTo(cx, yp);
        }
        canvas.drawPath(peakPath, peakLinePaint);

        axisPaint.setTextAlign(Paint.Align.CENTER);
        axisPaint.setColor(Color.parseColor("#7089A3"));
        for (int khz = (int) Math.ceil(fmin / 2000f) * 2; khz <= (int) (fmax / 1000f); khz += 2) {
            float fx = ox + ((khz * 1000f - fmin) / Math.max(1f, fmax - fmin)) * ow;
            canvas.drawLine(fx, botY, fx, botY + dp(3), centerPaint);
            canvas.drawText(khz + "k", fx, botY + dp(11), axisPaint);
        }
        canvas.drawLine(ox, botY, ox + ow, botY, centerPaint);
    }
}
