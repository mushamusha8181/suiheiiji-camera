package jp.mushamusha.horizontalcamera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/** Thread-safe MP4 muxer coordinator for one video track and optional audio track. */
public final class MuxerController {
    private static final int MAX_PENDING_BYTES = 12 * 1024 * 1024;

    private static final class PendingSample {
        final int track;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        PendingSample(int track, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.track = track;
            this.data = data;
            this.info = info;
        }
    }

    private final MediaMuxer muxer;
    private int expectedTracks;
    private int tracksAdded = 0;
    private boolean started = false;
    private boolean stopped = false;
    private final Deque<PendingSample> pending = new ArrayDeque<>();
    private int pendingBytes = 0;
    private int videoTrack = -1;
    private long videoSamplesWritten = 0L;
    private String failureMessage;

    public MuxerController(FileDescriptor fd, int expectedTracks) throws IOException {
        this.muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.expectedTracks = Math.max(1, expectedTracks);
    }

    public synchronized int addTrack(MediaFormat format) {
        if (stopped) return -1;
        if (started) throw new IllegalStateException("Muxer already started");
        int index = muxer.addTrack(format);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("video/")) videoTrack = index;
        tracksAdded++;
        startIfReadyLocked();
        return index;
    }

    /** Used when optional audio setup fails after the muxer was created for two tracks. */
    public synchronized void setExpectedTracks(int count) {
        if (stopped || started) return;
        expectedTracks = Math.max(1, count);
        startIfReadyLocked();
    }

    private void startIfReadyLocked() {
        if (started || stopped || tracksAdded < expectedTracks) return;
        muxer.start();
        started = true;
        for (PendingSample sample : pending) writeStartedLocked(sample.track, sample.data, sample.info);
        pending.clear();
        pendingBytes = 0;
        notifyAll();
    }

    public synchronized void writeSampleData(int track, ByteBuffer data, MediaCodec.BufferInfo info) {
        if (failureMessage != null) throw new IllegalStateException(failureMessage);
        if (stopped || track < 0 || data == null || info.size <= 0) return;
        if (started) {
            writeStartedLocked(track, data, info);
            return;
        }

        // MediaCodec can emit encoded samples before the other track's output format arrives.
        // Never silently discard the beginning of a clip. If the other track takes so long that
        // the bounded startup buffer would overflow, mark the recording as failed; the caller
        // will discard that MP4 instead of presenting an A/V-truncated file as successful.
        if (pendingBytes + info.size > MAX_PENDING_BYTES) {
            if (failureMessage == null) {
                failureMessage = "音声/映像トラックの初期化が遅すぎるため、録画開始データを保持できません";
            }
            return;
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(info.size);
        int oldPos = data.position();
        int oldLimit = data.limit();
        data.position(info.offset);
        data.limit(info.offset + info.size);
        copy.put(data);
        copy.flip();
        data.limit(oldLimit);
        data.position(Math.min(oldPos, oldLimit));

        MediaCodec.BufferInfo cloned = new MediaCodec.BufferInfo();
        cloned.set(0, info.size, info.presentationTimeUs, info.flags);
        pending.addLast(new PendingSample(track, copy, cloned));
        pendingBytes += info.size;
    }

    private void writeStartedLocked(int track, ByteBuffer data, MediaCodec.BufferInfo info) {
        try {
            data.position(info.offset);
            data.limit(info.offset + info.size);
            muxer.writeSampleData(track, data, info);
            if (track == videoTrack) videoSamplesWritten++;
        } catch (IllegalStateException e) {
            if (failureMessage == null) failureMessage = "MP4への書き込みに失敗しました: " + e.getMessage();
            throw new IllegalStateException(failureMessage, e);
        }
    }

    public synchronized boolean hasVideoSamples() {
        return videoSamplesWritten > 0L;
    }

    public synchronized String getFailureMessage() {
        return failureMessage;
    }

    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        pending.clear();
        pendingBytes = 0;
        try { if (started) muxer.stop(); }
        catch (Throwable e) { if (failureMessage == null) failureMessage = "MP4の終了処理に失敗しました: " + e.getMessage(); }
        try { muxer.release(); }
        catch (Throwable e) { if (failureMessage == null) failureMessage = "MP4を解放できません: " + e.getMessage(); }
    }
}
