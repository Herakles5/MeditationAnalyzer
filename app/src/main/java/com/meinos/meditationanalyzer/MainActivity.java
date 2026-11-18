package com.meinos.meditationanalyzer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Standalone Meditation & Acoustic Analyzer Activity (completely offline, no translation/network):
 * - Brainwave Envelope Spectrum (1-30 Hz: Delta, Theta, Schumann 7.83 Hz, Alpha)
 * - Tinnitus Analyzer (2-16 kHz)
 * - Stereo Binaural Beat Generator (delta 2.5Hz, theta 6Hz, 7.83Hz, alpha 10Hz)
 * - Compact .cfs (CFS1+ADPCM+zlib) and .tba (TBA4+ADPCM+LZ77) Recorder / Loader / Player
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 201;
    private static final int REQ_OPEN_FILE = 202;

    private LinearLayout rootContainer;
    private OscilloscopeView oscilloscopeView;
    private TextView tvSessionTimer, tvStatusBadge, tvHudLeft, tvHudRight, tvGainLabel, tvBeatVol, tvRecStatus;
    private RadioGroup rgBandMode, rgDisplayMode, rgBeatPreset, rgCarrierFreq, rgAudioSource, rgStorageFmt;
    private RadioButton rbBandBrain, rbSrcPhoneMic, rbSrcBtMic, rbSrcBeatMonitor, rbFmtCfs;
    private Button btnFreeze, btnResetPeak, btnBeatToggle, btnRec, btnPlay, btnLoad, btnSave;
    private SeekBar sbGain, sbBeatVol;
    private CheckBox cbKeepScreenOn;

    private final SignalAnalyzer analyzer = new SignalAnalyzer();
    private final SignalAnalyzer.Snapshot uiSnap = new SignalAnalyzer.Snapshot();
    private BinauralBeatGenerator beatGenerator;
    private AudioCaptureEngine captureEngine;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isFrozen = false;
    private volatile boolean running = true;
    private long sessionStartMs = System.currentTimeMillis();
    private String loadedLabel = "";

    private final Runnable uiRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refreshHudAndView();
            mainHandler.postDelayed(this, 45);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        beatGenerator = new BinauralBeatGenerator();
        captureEngine = new AudioCaptureEngine(this);

        captureEngine.setAudioCallback((pcm, len, sr) -> {
            if (!isFrozen) {
                analyzer.processPcm(pcm, len, sr);
            }
        });

        beatGenerator.setMonitorListener((monoPcm, len, sr) -> {
            if (captureEngine.getActiveSourceMode() == AudioCaptureEngine.SRC_BEAT_MONITOR
                    && !captureEngine.isPlaying()) {
                captureEngine.feedAudioSample(monoPcm, len, sr);
            }
        });

        wireControls();
        ensurePermissionsAndStart();
        mainHandler.post(uiRefreshTask);
    }

    private void bindViews() {
        rootContainer = findViewById(R.id.rootContainer);
        oscilloscopeView = findViewById(R.id.oscilloscopeView);
        tvSessionTimer = findViewById(R.id.tvSessionTimer);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvHudLeft = findViewById(R.id.tvHudLeft);
        tvHudRight = findViewById(R.id.tvHudRight);
        tvGainLabel = findViewById(R.id.tvGainLabel);
        tvBeatVol = findViewById(R.id.tvBeatVol);
        tvRecStatus = findViewById(R.id.tvRecStatus);

        rgBandMode = findViewById(R.id.rgBandMode);
        rgDisplayMode = findViewById(R.id.rgDisplayMode);
        rgBeatPreset = findViewById(R.id.rgBeatPreset);
        rgCarrierFreq = findViewById(R.id.rgCarrierFreq);
        rgAudioSource = findViewById(R.id.rgAudioSource);
        rgStorageFmt = findViewById(R.id.rgStorageFmt);

        rbBandBrain = findViewById(R.id.rbBandBrain);
        rbSrcPhoneMic = findViewById(R.id.rbSrcPhoneMic);
        rbSrcBtMic = findViewById(R.id.rbSrcBtMic);
        rbSrcBeatMonitor = findViewById(R.id.rbSrcBeatMonitor);
        rbFmtCfs = findViewById(R.id.rbFmtCfs);

        btnFreeze = findViewById(R.id.btnFreeze);
        btnResetPeak = findViewById(R.id.btnResetPeak);
        btnBeatToggle = findViewById(R.id.btnBeatToggle);
        btnRec = findViewById(R.id.btnRec);
        btnPlay = findViewById(R.id.btnPlay);
        btnLoad = findViewById(R.id.btnLoad);
        btnSave = findViewById(R.id.btnSave);

        sbGain = findViewById(R.id.sbGain);
        sbBeatVol = findViewById(R.id.sbBeatVol);
        cbKeepScreenOn = findViewById(R.id.cbKeepScreenOn);
    }

    private void wireControls() {
        analyzer.setBandMode(SignalAnalyzer.BAND_BRAIN);
        analyzer.setGain(2.0f);
        oscilloscopeView.setDisplayGain(2.0f);

        rgBandMode.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbBandBrain) analyzer.setBandMode(SignalAnalyzer.BAND_BRAIN);
            else if (id == R.id.rbBandTinnitus) analyzer.setBandMode(SignalAnalyzer.BAND_TINNITUS);
            else analyzer.setBandMode(SignalAnalyzer.BAND_FULL);
        });

        rgDisplayMode.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbModeAll3) oscilloscopeView.setDisplayMode(OscilloscopeView.MODE_ALL3);
            else if (id == R.id.rbModeDual) oscilloscopeView.setDisplayMode(OscilloscopeView.MODE_DUAL);
            else oscilloscopeView.setDisplayMode(OscilloscopeView.MODE_FFT);
        });

        sbGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float g = 1.0f + progress * 0.5f;
                analyzer.setGain(g);
                oscilloscopeView.setDisplayGain(g);
                tvGainLabel.setText(String.format(Locale.US, "Gain: x%.1f", g));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnFreeze.setOnClickListener(v -> {
            isFrozen = !isFrozen;
            btnFreeze.setText(isFrozen ? "▶" : "⏸");
        });

        btnResetPeak.setOnClickListener(v -> analyzer.resetPeaks());

        rgBeatPreset.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbBeatDelta) beatGenerator.setBeatHz(2.5);
            else if (id == R.id.rbBeatTheta) beatGenerator.setBeatHz(6.0);
            else if (id == R.id.rbBeatSchumann) beatGenerator.setBeatHz(7.83);
            else if (id == R.id.rbBeatAlpha) beatGenerator.setBeatHz(10.0);
        });

        rgCarrierFreq.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbCarrier136) beatGenerator.setCarrierHz(136.1);
            else beatGenerator.setCarrierHz(200.0);
        });

        sbBeatVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                beatGenerator.setVolume(progress / 100f);
                tvBeatVol.setText(String.format(Locale.US, "Vol: %d%%", progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnBeatToggle.setOnClickListener(v -> toggleBinauralBeat());

        rgAudioSource.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbSrcBtMic) {
                oscilloscopeView.setSourceLabel("BT MIC");
                tvStatusBadge.setText("● BT HEADSET");
                captureEngine.startSource(AudioCaptureEngine.SRC_BT_MIC);
            } else if (id == R.id.rbSrcBeatMonitor) {
                oscilloscopeView.setSourceLabel("INTERN CHECK");
                tvStatusBadge.setText("● BEAT-MONITOR");
                captureEngine.startSource(AudioCaptureEngine.SRC_BEAT_MONITOR);
                if (!beatGenerator.isRunning()) {
                    toggleBinauralBeat();
                }
            } else {
                oscilloscopeView.setSourceLabel("HANDY-MIC");
                tvStatusBadge.setText("● HANDY-MIC");
                captureEngine.startSource(AudioCaptureEngine.SRC_PHONE_MIC);
            }
        });

        btnRec.setOnClickListener(v -> toggleRecording());
        btnPlay.setOnClickListener(v -> togglePlayback());
        btnSave.setOnClickListener(v -> saveCurrentRecording());
        btnLoad.setOnClickListener(v -> openLoadDialog());

        cbKeepScreenOn.setOnCheckedChangeListener((cb, checked) -> rootContainer.setKeepScreenOn(checked));
    }

    private void toggleBinauralBeat() {
        if (beatGenerator.isRunning()) {
            beatGenerator.stop();
            btnBeatToggle.setText("🎧 BEAT STARTEN");
            btnBeatToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF322052));
            tvStatusBadge.setText(captureEngine.getActiveSourceMode() == AudioCaptureEngine.SRC_BT_MIC
                    ? "● BT HEADSET" : "● HANDY-MIC");
        } else {
            sessionStartMs = System.currentTimeMillis();
            beatGenerator.start();
            btnBeatToggle.setText("⏹ BEAT STOPPEN");
            btnBeatToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF8B1E2B));
            tvStatusBadge.setText(String.format(Locale.US, "🎧 %.2fHz AKTIV", beatGenerator.getBeatHz()));
        }
    }

    private void refreshHudAndView() {
        long sec = Math.max(0L, (System.currentTimeMillis() - sessionStartMs) / 1000L);
        tvSessionTimer.setText(String.format(Locale.US, "⏱ %02d:%02d", sec / 60, sec % 60));

        if (!isFrozen) {
            oscilloscopeView.updateFromAnalyzer(analyzer);
        }
        analyzer.copySnapshot(uiSnap);

        boolean isBrain = SignalAnalyzer.BAND_BRAIN.equals(uiSnap.bandMode);
        if (isBrain) {
            String bInfo = (uiSnap.brainBandLabel != null && !uiSnap.brainBandLabel.isEmpty())
                    ? String.format(Locale.US, "🧠 %.2f Hz (%s)", uiSnap.brainPeakHz, uiSnap.brainBandLabel)
                    : "🧠 1-30Hz Env Bereit";
            tvHudLeft.setText(String.format(Locale.US, "CH1: %.2fV | Audio: %.0f Hz | %s",
                    uiSnap.vpp, uiSnap.audioPeakHz, bInfo));
            String bLbl = (uiSnap.brainBandLabel == null || uiSnap.brainBandLabel.isEmpty()) ? "--" : uiSnap.brainBandLabel;
            tvHudRight.setText(String.format(Locale.US, "CH2 [MIC]: %.1f dBFS | θ/α(4-12Hz): %.1f dB | %s",
                    uiSnap.currentMicDb, uiSnap.currentBrainDb, bLbl));
        } else {
            String tinTxt = uiSnap.tinnitusAlert.isEmpty() ? "" : (" | 🔔 " + uiSnap.tinnitusAlert);
            tvHudLeft.setText(String.format(Locale.US, "CH1: %.2fV | Peak: %.0f Hz (%.0f dB)%s",
                    uiSnap.vpp, uiSnap.audioPeakHz, uiSnap.audioPeakDb, tinTxt));
            tvHudRight.setText(String.format(Locale.US, "CH2 [MIC]: %.1f dBFS | TIN(2-16k): %.1f dB | SNR +%.1fdB",
                    uiSnap.currentMicDb, uiSnap.currentTinDb, uiSnap.fftSnrDb));
        }

        if (captureEngine.isRecording()) {
            int n = captureEngine.getRecordingSamples();
            int sr = Math.max(1, captureEngine.getRecordingSampleRate());
            float recSec = n / (float) sr;
            float estKb = (n * 0.5f) / 1024f;
            tvRecStatus.setText(String.format(Locale.US, "⏺ REC %02d:%04.1f | ~%.0f KB (.cfs/.tba)",
                    (int) (recSec / 60f), recSec % 60f, estKb));
        }
    }

    private void toggleRecording() {
        if (captureEngine.isRecording()) {
            captureEngine.stopRecording();
            btnRec.setText("⏺ REC");
            saveCurrentRecording();
        } else {
            if (captureEngine.isPlaying()) captureEngine.stopPlayback();
            loadedLabel = "";
            captureEngine.startRecording(captureEngine.getActiveSampleRate());
            btnRec.setText("⏹ STOP");
            tvRecStatus.setText("⏺ Aufnahme läuft lokal...");
        }
    }

    private File getRecordingsDir() {
        File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "MeditationRecordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void saveCurrentRecording() {
        AudioCaptureEngine.BufferSnapshot snap = captureEngine.getRecordingCopy();
        if (snap.length <= 0) {
            Toast.makeText(this, "Keine Aufnahme zum Speichern vorhanden.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String ext = rbFmtCfs.isChecked() ? ".cfs" : ".tba";
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File outFile = new File(getRecordingsDir(), "meditation_" + stamp + ext);

        tvRecStatus.setText("⏳ Komprimiere & speichere " + outFile.getName() + "...");
        new Thread(() -> {
            try {
                CosmosAudioStorage.SaveResult res = CosmosAudioStorage.saveRecording(outFile, snap.pcm, snap.length, snap.sampleRate);
                mainHandler.post(() -> tvRecStatus.setText(String.format(
                        Locale.US,
                        "💾 Gespeichert: %s (%.1f KB, %.1fx kompakter, %s)",
                        outFile.getName(), res.storedBytes / 1024.0, res.ratio, res.format)));
            } catch (Exception e) {
                mainHandler.post(() -> tvRecStatus.setText("⚠️ Speicherfehler: " + e.getMessage()));
            }
        }).start();
    }

    private void openLoadDialog() {
        File dir = getRecordingsDir();
        File[] files = dir.listFiles((d, name) -> {
            String low = name.toLowerCase(Locale.US);
            return low.endsWith(".cfs") || low.endsWith(".tba") || low.endsWith(".wav");
        });
        List<File> list = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            list.addAll(Arrays.asList(files));
        }

        List<String> titles = new ArrayList<>();
        for (File f : list) {
            titles.add(String.format(Locale.US, "%s (%.1f KB)", f.getName(), f.length() / 1024.0));
        }
        titles.add("📁 System-Datei öffnen (.cfs / .tba / .wav)...");

        new AlertDialog.Builder(this)
                .setTitle("Aufnahme laden")
                .setItems(titles.toArray(new String[0]), (dlg, which) -> {
                    if (which == list.size()) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_OPEN_FILE);
                    } else if (which >= 0 && which < list.size()) {
                        loadLocalFile(list.get(which));
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void loadLocalFile(File file) {
        new Thread(() -> {
            try {
                CosmosAudioStorage.LoadedAudio audio = CosmosAudioStorage.loadRecording(file);
                captureEngine.setLoadedRecording(audio.pcm, audio.sampleRate);
                loadedLabel = file.getName();
                float dur = audio.pcm.length / (float) Math.max(1, audio.sampleRate);
                mainHandler.post(() -> {
                    tvRecStatus.setText(String.format(Locale.US, "📂 Geladen: %s (%.1fs @ %dHz, %.1f KB %s)",
                            file.getName(), dur, audio.sampleRate, audio.storedBytes / 1024.0, audio.format));
                    togglePlayback();
                });
            } catch (Exception e) {
                mainHandler.post(() -> tvRecStatus.setText("⚠️ Ladefehler: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final String fname = queryDisplayName(uri);
            new Thread(() -> {
                try (InputStream is = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    if (is == null) return;
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                    CosmosAudioStorage.LoadedAudio audio = CosmosAudioStorage.loadRecordingBytes(bos.toByteArray(), fname);
                    captureEngine.setLoadedRecording(audio.pcm, audio.sampleRate);
                    loadedLabel = fname;
                    float dur = audio.pcm.length / (float) Math.max(1, audio.sampleRate);
                    mainHandler.post(() -> {
                        tvRecStatus.setText(String.format(Locale.US, "📂 Geladen: %s (%.1fs @ %dHz, %s)",
                                fname, dur, audio.sampleRate, audio.format));
                        togglePlayback();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> tvRecStatus.setText("⚠️ Ladefehler: " + e.getMessage()));
                }
            }).start();
        }
    }

    private String queryDisplayName(Uri uri) {
        String name = "import.cfs";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    private void togglePlayback() {
        if (captureEngine.isPlaying()) {
            captureEngine.stopPlayback();
            btnPlay.setText("▶ PLAY");
            return;
        }
        if (captureEngine.getRecordingSamples() <= 0) {
            Toast.makeText(this, "Erst aufnehmen (⏺ REC) oder Datei laden (📂 LOAD).", Toast.LENGTH_SHORT).show();
            return;
        }
        if (captureEngine.isRecording()) {
            captureEngine.stopRecording();
            btnRec.setText("⏺ REC");
        }
        analyzer.resetPeaks();
        oscilloscopeView.setSourceLabel("PLAY: " + (loadedLabel.isEmpty() ? "REC" : loadedLabel));
        btnPlay.setText("⏹ STOP");

        captureEngine.startPlayback(new AudioCaptureEngine.PlaybackCallback() {
            @Override
            public void onProgress(float posSec, float totalSec) {
                mainHandler.post(() -> tvRecStatus.setText(
                        String.format(Locale.US, "▶ PLAY %.1fs / %.1fs (%s)", posSec, totalSec, loadedLabel)));
            }

            @Override
            public void onFinished() {
                mainHandler.post(() -> {
                    btnPlay.setText("▶ PLAY");
                    String srcTag = (captureEngine.getActiveSourceMode() == AudioCaptureEngine.SRC_BT_MIC)
                            ? "BT MIC"
                            : (captureEngine.getActiveSourceMode() == AudioCaptureEngine.SRC_BEAT_MONITOR ? "INTERN CHECK" : "HANDY-MIC");
                    oscilloscopeView.setSourceLabel(srcTag);
                    tvRecStatus.setText("✓ Wiedergabe beendet");
                });
            }
        });
    }

    private void ensurePermissionsAndStart() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            captureEngine.startSource(AudioCaptureEngine.SRC_PHONE_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                captureEngine.startSource(AudioCaptureEngine.SRC_PHONE_MIC);
            } else {
                Toast.makeText(this, "Mikrofon-Berechtigung erforderlich für Live-Analyse", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        mainHandler.removeCallbacks(uiRefreshTask);
        if (beatGenerator != null) beatGenerator.stop();
        if (captureEngine != null) captureEngine.release();
    }
}
