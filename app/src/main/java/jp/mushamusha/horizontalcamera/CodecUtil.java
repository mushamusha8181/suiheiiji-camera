package jp.mushamusha.horizontalcamera;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CodecUtil {
    private CodecUtil() { }

    private static final class VideoMode {
        final String encoderName;
        final Range<Integer> bitrateRange;

        VideoMode(String encoderName, Range<Integer> bitrateRange) {
            this.encoderName = encoderName;
            this.bitrateRange = bitrateRange;
        }
    }

    private static final Map<String, VideoMode> VIDEO_MODE_CACHE = new ConcurrentHashMap<>();
    private static volatile MediaCodecInfo[] codecInfos;

    private static MediaCodecInfo[] codecInfos() {
        MediaCodecInfo[] cached = codecInfos;
        if (cached != null) return cached;
        synchronized (CodecUtil.class) {
            if (codecInfos != null) return codecInfos;
            try {
                MediaCodecInfo[] loaded = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
                codecInfos = loaded;
                return loaded;
            } catch (Throwable ignored) {
                // A transient vendor/codec-list failure must not become a permanent "unsupported" result.
                return new MediaCodecInfo[0];
            }
        }
    }

    private static String key(String mime, int width, int height, int fps) {
        return String.format(Locale.US, "%s|%dx%d@%d", mime, width, height, fps);
    }

    private static VideoMode findVideoMode(String mime, int width, int height, int fps) {
        String key = key(mime, width, height, fps);
        VideoMode cached = VIDEO_MODE_CACHE.get(key);
        if (cached != null) return cached;
        for (MediaCodecInfo info : codecInfos()) {
            try {
                if (!info.isEncoder() || !supportsType(info, mime)) continue;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                if (!supportsSurfaceInput(caps)) continue;
                MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                if (vc == null || !vc.areSizeAndRateSupported(width, height, fps)) continue;
                VideoMode mode = new VideoMode(info.getName(), vc.getBitrateRange());
                VIDEO_MODE_CACHE.put(key, mode);
                return mode;
            } catch (Throwable ignored) {
                // Broken vendor metadata must not hide the next valid encoder.
            }
        }
        return null;
    }

    public static boolean supportsVideo(String mime, int width, int height, int fps) {
        return findVideoMode(mime, width, height, fps) != null;
    }

    public static String findEncoderName(String mime, int width, int height, int fps) {
        VideoMode mode = findVideoMode(mime, width, height, fps);
        return mode == null ? null : mode.encoderName;
    }

    private static boolean supportsType(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (mime.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static boolean supportsSurfaceInput(MediaCodecInfo.CodecCapabilities caps) {
        if (caps == null || caps.colorFormats == null) return false;
        for (int format : caps.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
        }
        return false;
    }

    public static boolean supportsAvc(int width, int height, int fps) {
        return supportsVideo(MediaFormat.MIMETYPE_VIDEO_AVC, width, height, fps);
    }

    public static boolean supportsHevc(int width, int height, int fps) {
        return supportsVideo(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height, fps);
    }

    public static int clampBitrate(String mime, int width, int height, int fps, int requested) {
        int fallback = Math.max(2_000_000, Math.min(120_000_000, requested));
        VideoMode mode = findVideoMode(mime, width, height, fps);
        if (mode == null || mode.bitrateRange == null) return fallback;
        return Math.max(mode.bitrateRange.getLower(), Math.min(mode.bitrateRange.getUpper(), requested));
    }
}
