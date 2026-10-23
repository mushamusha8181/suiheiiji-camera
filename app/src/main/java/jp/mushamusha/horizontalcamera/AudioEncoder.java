package jp.mushamusha.horizontalcamera;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioEncoder {
    public interface Listener {
        void onAudioLevel(float normalized, boolean clipping);
        void onAudioRoute(AudioDeviceInfo requested, AudioDeviceInfo actual);
        void onAudioError(String message);
    }

    public static final int LOW_CUT_OFF = 0;
    public static final int LOW_CUT_120HZ = 1;
    public static final int LOW_CUT_220HZ = 2;

    private static final int DEFAULT_SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 1;
    private final MuxerController muxer;
    private final AudioDeviceInfo preferredDevice;
    private final boolean autoGain;
    private final Listener listener;
    private final int sampleRate;
    private final float digitalGainLinear;
    private final float highPassAlpha;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch workerFinished = new CountDownLatch(1);
    private volatile MediaCodec codec;
    private volatile AudioRecord record;
    private AutomaticGainControl agc;
    private Thread thread;
    private int trackIndex = -1;
    private long totalSamples = 0L;
    private long lastMeterNs = 0L;
    private float hpPrevX = 0f;
    private float hpPrevY = 0f;
    private boolean routeReported;
    private volatile boolean lastStopClean = true;

    public AudioEncoder(MuxerController muxer, AudioDeviceInfo preferredDevice,
                        boolean autoGain, float digitalGainDb, int lowCutMode,
                        Listener listener) {
        this.muxer = muxer;
        this.preferredDevice = preferredDevice;
        this.autoGain = autoGain;
        this.listener = listener;
        this.sampleRate = chooseSampleRate(preferredDevice);
        this.digitalGainLinear = autoGain ? 1f : (float) Math.pow(10.0, digitalGainDb / 20.0);
        float cutoff = lowCutMode == LOW_CUT_220HZ ? 220f : lowCutMode == LOW_CUT_120HZ ? 120f : 0f;
        if (cutoff > 0f) {
            float rc = 1f / (2f * (float) Math.PI * cutoff);
            float dt = 1f / this.sampleRate;
            this.highPassAlpha = rc / (rc + dt);
        } else {
            this.highPassAlpha = 0f;
        }
    }

    private static int chooseSampleRate(AudioDeviceInfo device) {
        if (device == null) return DEFAULT_SAMPLE_RATE;
        try {
            int[] rates = device.getSampleRates();
            if (rates == null || rates.length == 0) return DEFAULT_SAMPLE_RATE;
            for (int rate : rates) if (rate == 48_000) return rate;
            for (int rate : rates) if (rate == 44_100) return rate;
            for (int rate : rates) if (rate >= 8_000 && rate <= 192_000) return rate;
        } catch (Throwable ignored) { }
        return DEFAULT_SAMPLE_RATE;
    }

    public static boolean isAutoGainAvailable() {
        try { return AutomaticGainControl.isAvailable(); }
        catch (Throwable ignored) { return false; }
    }

    public void start() throws IOException {
        lastStopClean = true;
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("AudioRecord buffer size unsupported: " + sampleRate + "Hz");
            int buffer = Math.max(min * 2, 8192);
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(buffer)
                    .build();
            if (preferredDevice != null) {
                try { record.setPreferredDevice(preferredDevice); } catch (Throwable ignored) { }
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("AudioRecord init failed");

            if (autoGain && isAutoGainAvailable()) {
                try {
                    agc = AutomaticGainControl.create(record.getAudioSessionId());
                    if (agc != null) agc.setEnabled(true);
                } catch (Throwable ignored) { }
            }

            running.set(true);
            record.startRecording();
            thread = new Thread(this::loop, "AudioEncoder");
            thread.start();
        } catch (Throwable failure) {
            running.set(false);
            releaseResources();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("音声エンコーダの開始に失敗しました", failure);
        }
    }

    private void loop() {
        byte[] pcm = new byte[4096];
        try {
            while (running.get()) {
                int n = record.read(pcm, 0, pcm.length);
                if (n > 0) {
                    if (!routeReported && listener != null) {
                        routeReported = true;
                        AudioDeviceInfo actual = null;
                        try { actual = record.getRoutedDevice(); } catch (Throwable ignored) { }
                        listener.onAudioRoute(preferredDevice, actual);
                    }
                    processPcm(pcm, n);
                    queuePcm(pcm, n, false);
                } else if (n < 0 && !running.get()) {
                    break;
                } else if (n < 0) {
                    throw new IOException("AudioRecord read error: " + n);
                }
                drain(false);
            }
            queuePcm(new byte[0], 0, true);
            drain(true);
        } catch (Throwable e) {
            running.set(false);
            if (trackIndex < 0) muxer.setExpectedTracks(1);
            if (listener != null) listener.onAudioError("音声録音を継続できません: " + e.getMessage());
        } finally {
            workerFinished.countDown();
        }
    }

    private void processPcm(byte[] pcm, int length) {
        long now = System.nanoTime();
        boolean measure = listener != null && now - lastMeterNs > 80_000_000L;
        boolean filter = highPassAlpha > 0f;
        boolean gainNeeded = Math.abs(digitalGainLinear - 1f) > 0.0001f;
        if (!filter && !gainNeeded && !measure) return;

        double sumSq = 0.0;
        int peak = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int raw = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            float y = raw;
            if (filter) {
                y = highPassAlpha * (hpPrevY + raw - hpPrevX);
                hpPrevX = raw;
                hpPrevY = y;
            }
            int v = gainNeeded ? Math.round(y * digitalGainLinear) : Math.round(y);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            if (filter || gainNeeded) {
                pcm[i] = (byte) (v & 0xFF);
                pcm[i + 1] = (byte) ((v >> 8) & 0xFF);
            }
            if (measure) {
                int a = Math.abs(v);
                peak = Math.max(peak, a);
                sumSq += (double) v * v;
                samples++;
            }
        }
        if (measure && samples > 0) {
            lastMeterNs = now;
            double rms = Math.sqrt(sumSq / samples);
            float normalized = (float) Math.min(1.0, rms / 16384.0);
            listener.onAudioLevel(normalized, peak >= 32300);
        }
    }

    /** Queues PCM in codec-sized chunks so unusual encoders cannot overflow their input buffer. */
    private void queuePcm(byte[] pcm, int length, boolean eos) throws IOException {
        if (eos) {
            queueOneInput(pcm, 0, 0, true);
            return;
        }
        int offset = 0;
        while (offset < length) {
            int index = dequeueInputIndex(false);
            if (index < 0) return;
            ByteBuffer in = codec.getInputBuffer(index);
            if (in == null) {
                codec.queueInputBuffer(index, 0, 0, totalSamples * 1_000_000L / sampleRate, 0);
                continue;
            }
            in.clear();
            int chunk = Math.min(in.remaining(), length - offset);
            chunk &= ~1; // PCM16: never split a sample.
            if (chunk <= 0) throw new IOException("AAC encoder input buffer is too small");
            in.put(pcm, offset, chunk);
            long pts = totalSamples * 1_000_000L / sampleRate;
            codec.queueInputBuffer(index, 0, chunk, pts, 0);
            totalSamples += chunk / 2L;
            offset += chunk;
        }
    }

    private void queueOneInput(byte[] pcm, int offset, int length, boolean eos) throws IOException {
        int index = dequeueInputIndex(true);
        if (index < 0) throw new IOException("AAC encoder did not accept EOS");
        ByteBuffer in = codec.getInputBuffer(index);
        if (in == null) throw new IOException("AAC encoder input buffer unavailable");
        in.clear();
        if (length > 0) in.put(pcm, offset, length);
        long pts = totalSamples * 1_000_000L / sampleRate;
        codec.queueInputBuffer(index, 0, length, pts, eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        totalSamples += length / 2L;
    }

    private int dequeueInputIndex(boolean eos) throws IOException {
        int attempts = 0;
        while (attempts++ < 150) {
            MediaCodec currentCodec = codec;
            if (currentCodec == null) throw new IOException("AAC encoder was released");
            int index = currentCodec.dequeueInputBuffer(10_000);
            if (index >= 0) return index;
            if (!running.get() && !eos) return -1;
            // Free encoded output while waiting for another input buffer. Without this, some
            // vendor codecs can stall with output queued and the old implementation silently
            // dropped the corresponding PCM block after the timeout.
            if ((attempts % 5) == 0) drain(false);
        }
        if (eos) return -1;
        throw new IOException("AAC encoder input stalled");
    }

    private void drain(boolean untilEos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idleCount = 0;
        while (true) {
            int status = codec.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                if (++idleCount > 50) return;
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (trackIndex < 0) trackIndex = muxer.addTrack(codec.getOutputFormat());
            } else if (status >= 0) {
                ByteBuffer out = codec.getOutputBuffer(status);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (out != null && info.size > 0 && trackIndex >= 0) muxer.writeSampleData(trackIndex, out, info);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(status, false);
                if (eos) return;
            }
        }
    }

    /** Stops the worker with a bounded shutdown. Returns null on success, otherwise a warning. */
    public String stopAndRelease() {
        running.set(false);
        try { if (record != null) record.stop(); } catch (Throwable ignored) { }
        boolean interrupted = false;
        boolean finished = thread == null;
        if (!finished) {
            try { finished = workerFinished.await(2500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }

        // Some vendor AudioRecord implementations do not unblock read() on stop().
        // Releasing the recorder is a last-resort wake-up, then give the worker a short bounded grace period.
        if (!finished && record != null) {
            try { record.release(); } catch (Throwable ignored) { }
            try { thread.interrupt(); } catch (Throwable ignored) { }
            try { finished = workerFinished.await(1000, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }

        String warning = null;
        if (!finished) {
            warning = "音声エンコーダの終了に時間がかかったため強制終了しました";
            // Force codec calls in the worker to fail fast instead of allowing an unbounded stop hang.
            try { if (codec != null) codec.stop(); } catch (Throwable ignored) { }
            try { if (codec != null) codec.release(); } catch (Throwable ignored) { }
            try { finished = workerFinished.await(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { interrupted = true; }
            codec = null;
        }
        lastStopClean = finished;

        releaseResources();
        thread = null;
        if (interrupted) Thread.currentThread().interrupt();
        return warning;
    }

    public boolean wasStopClean() { return lastStopClean; }

    private void releaseResources() {
        try { if (agc != null) agc.release(); } catch (Throwable ignored) { }
        try { if (record != null) record.release(); } catch (Throwable ignored) { }
        try { if (codec != null) codec.stop(); } catch (Throwable ignored) { }
        try { if (codec != null) codec.release(); } catch (Throwable ignored) { }
        agc = null;
        record = null;
        codec = null;
    }
}
