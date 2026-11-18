package com.meinos.meditationanalyzer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles Phone Mic (44.1 kHz quiet room capture), optional Bluetooth Headset Mic (SCO),
 * session PCM buffering for .cfs/.tba export, and playback of loaded recordings.
 */
public class AudioCaptureEngine {

    public static final int SRC_PHONE_MIC = 0;
    public static final int SRC_BT_MIC = 1;
    public static final int SRC_BEAT_MONITOR = 2;

    public static final int PHONE_SAMPLE_RATE = 44100;
    public static final int BT_SAMPLE_RATE = 16000;

    public interface AudioCallback {
        void onAudioChunk(short[] pcm, int length, int sampleRate);
    }

    public interface PlaybackCallback {
        void onProgress(float posSec, float totalSec);
        void onFinished();
    }

    private final Context appContext;
    private final AudioManager audioManager;
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean playing = new AtomicBoolean(false);

    private volatile int activeSourceMode = SRC_PHONE_MIC;
    private volatile int activeSampleRate = PHONE_SAMPLE_RATE;
    private volatile AudioCallback callback;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private Thread playbackThread;

    // Primitive doubling buffer for recorded mono audio (capped at ~15 min @ 44.1 kHz)
    private final Object recLock = new Object();
    private short[] recBuffer = new short[65536];
    private int recLength = 0;
    private int recSampleRate = PHONE_SAMPLE_RATE;
    private static final int MAX_REC_SAMPLES = 44100 * 60 * 15;

    public AudioCaptureEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setAudioCallback(AudioCallback cb) {
        this.callback = cb;
    }

    public int getActiveSourceMode() {
        return activeSourceMode;
    }

    public int getActiveSampleRate() {
        return activeSampleRate;
    }

    public void setActiveSampleRate(int sr) {
        this.activeSampleRate = sr;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public boolean isPlaying() {
        return playing.get();
    }

    public synchronized void startSource(int sourceMode) {
        stopCapture();
        this.activeSourceMode = sourceMode;
        if (sourceMode == SRC_BEAT_MONITOR) {
            this.activeSampleRate = BinauralBeatGenerator.SAMPLE_RATE;
            return; // Fed externally from BinauralBeatGenerator monitor listener
        }
        int sr = (sourceMode == SRC_BT_MIC) ? BT_SAMPLE_RATE : PHONE_SAMPLE_RATE;
        this.activeSampleRate = sr;
        if (sourceMode == SRC_BT_MIC) {
            enableBluetoothSco(true);
        } else {
            enableBluetoothSco(false);
        }
        startMicThread(sourceMode, sr);
    }

    private void enableBluetoothSco(boolean enable) {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enable) {
                    for (AudioDeviceInfo dev : audioManager.getAvailableCommunicationDevices()) {
                        if (dev.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                            audioManager.setCommunicationDevice(dev);
                            break;
                        }
                    }
                } else {
                    audioManager.clearCommunicationDevice();
                }
            }
            if (enable) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            } else {
                if (audioManager.isBluetoothScoOn()) {
                    audioManager.setBluetoothScoOn(false);
                    audioManager.stopBluetoothSco();
                }
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ignored) {}
    }

    private void startMicThread(int sourceMode, int sr) {
        int minBytes = AudioRecord.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufBytes = Math.max(4096, minBytes * 2);
        int audioSrc = (sourceMode == SRC_BT_MIC)
                ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.VOICE_RECOGNITION;

        try {
            AudioRecord rec = new AudioRecord(
                    audioSrc,
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes
            );
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sr,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufBytes
                );
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return;
            }
            this.audioRecord = rec;
            this.capturing.set(true);
            rec.startRecording();

            final AudioRecord activeRec = rec;
            final int readSamples = Math.max(512, sr / 30);
            captureThread = new Thread(() -> {
                short[] chunk = new short[readSamples];
                while (capturing.get()) {
                    int n = activeRec.read(chunk, 0, chunk.length);
                    if (n > 0) {
                        if (playing.get()) continue; // Don't overwrite oscilloscope during file playback
                        feedAudioSample(chunk, n, sr);
                    }
                }
            }, "MeditationMicCapture");
            captureThread.start();
        } catch (SecurityException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Called by either mic capture or Beat-Monitor source. */
    public void feedAudioSample(short[] pcm, int length, int sr) {
        if (pcm == null || length <= 0) return;
        if (recording.get()) {
            synchronized (recLock) {
                if (recLength == 0) {
                    recSampleRate = sr;
                }
                if (recLength + length <= MAX_REC_SAMPLES) {
                    if (recLength + length > recBuffer.length) {
                        int newCap = Math.max(recBuffer.length * 2, recLength + length);
                        recBuffer = Arrays.copyOf(recBuffer, Math.min(MAX_REC_SAMPLES, newCap));
                    }
                    int copyLen = Math.min(length, recBuffer.length - recLength);
                    System.arraycopy(pcm, 0, recBuffer, recLength, copyLen);
                    recLength += copyLen;
                }
            }
        }
        AudioCallback cb = this.callback;
        if (cb != null) {
            cb.onAudioChunk(pcm, length, sr);
        }
    }

    public synchronized void stopCapture() {
        capturing.set(false);
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (captureThread != null) {
            try {
                captureThread.join(250);
            } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        enableBluetoothSco(false);
    }

    public void startRecording(int currentSr) {
        synchronized (recLock) {
            recBuffer = new short[65536];
            recLength = 0;
            recSampleRate = currentSr > 0 ? currentSr : activeSampleRate;
        }
        recording.set(true);
    }

    public void stopRecording() {
        recording.set(false);
    }

    public void setLoadedRecording(short[] pcm, int sr) {
        synchronized (recLock) {
            recBuffer = pcm != null ? pcm : new short[0];
            recLength = recBuffer.length;
            recSampleRate = sr > 0 ? sr : PHONE_SAMPLE_RATE;
        }
    }

    public static class BufferSnapshot {
        public short[] pcm;
        public int length;
        public int sampleRate;
    }

    public BufferSnapshot getRecordingCopy() {
        synchronized (recLock) {
            BufferSnapshot snap = new BufferSnapshot();
            snap.pcm = Arrays.copyOf(recBuffer, recLength);
            snap.length = recLength;
            snap.sampleRate = recSampleRate;
            return snap;
        }
    }

    public int getRecordingSamples() {
        synchronized (recLock) {
            return recLength;
        }
    }

    public int getRecordingSampleRate() {
        synchronized (recLock) {
            return recSampleRate;
        }
    }

    @SuppressWarnings("deprecation")
    public static AudioTrack createStreamAudioTrack(int sampleRate, int channelMask, int bufferBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        }
        return new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM
        );
    }

    public synchronized void startPlayback(PlaybackCallback pcb) {
        stopPlayback();
        final BufferSnapshot snap = getRecordingCopy();
        if (snap.length <= 0) {
            if (pcb != null) pcb.onFinished();
            return;
        }
        playing.set(true);
        playbackThread = new Thread(() -> {
            AudioTrack track = null;
            try {
                int minB = AudioTrack.getMinBufferSize(
                        snap.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );
                int chunkLen = Math.max(512, snap.sampleRate / 25); // 40ms frames
                track = createStreamAudioTrack(snap.sampleRate, AudioFormat.CHANNEL_OUT_MONO, Math.max(minB, chunkLen * 4));
                track.play();

                float totalSec = snap.length / (float) Math.max(1, snap.sampleRate);
                int pos = 0;
                short[] slice = new short[chunkLen];
                while (playing.get() && pos < snap.length) {
                    int n = Math.min(chunkLen, snap.length - pos);
                    System.arraycopy(snap.pcm, pos, slice, 0, n);
                    track.write(slice, 0, n);
                    AudioCallback cb = this.callback;
                    if (cb != null) {
                        cb.onAudioChunk(slice, n, snap.sampleRate);
                    }
                    if (pcb != null) {
                        pcb.onProgress(pos / (float) snap.sampleRate, totalSec);
                    }
                    pos += n;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                playing.set(false);
                if (track != null) {
                    try {
                        track.stop();
                        track.release();
                    } catch (Exception ignored) {}
                }
                if (pcb != null) pcb.onFinished();
            }
        }, "MeditationPlayback");
        playbackThread.start();
    }

    public synchronized void stopPlayback() {
        playing.set(false);
        if (playbackThread != null) {
            try {
                playbackThread.join(300);
            } catch (InterruptedException ignored) {}
            playbackThread = null;
        }
    }

    public void release() {
        stopRecording();
        stopPlayback();
        stopCapture();
    }
}
