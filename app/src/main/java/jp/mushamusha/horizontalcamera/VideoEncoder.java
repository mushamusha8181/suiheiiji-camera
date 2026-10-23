package jp.mushamusha.horizontalcamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class VideoEncoder {
    private final MediaCodec codec;
    private final Surface inputSurface;
    private final MuxerController muxer;
    private int trackIndex = -1;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface encoderEglSurface = EGL14.EGL_NO_SURFACE;
    private boolean eosSignaled = false;
    private boolean outputEosSeen = false;

    public VideoEncoder(int width, int height, int fps, String mime, int bitrate, MuxerController muxer) throws IOException {
        this.muxer = muxer;
        MediaCodec createdCodec = null;
        Surface createdSurface = null;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            String encoderName = CodecUtil.findEncoderName(mime, width, height, fps);
            if (encoderName == null) throw new IOException("No surface encoder supports the selected recording format");
            createdCodec = MediaCodec.createByCodecName(encoderName);
            createdCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdSurface = createdCodec.createInputSurface();
            createdCodec.start();
        } catch (Throwable e) {
            try { if (createdSurface != null) createdSurface.release(); } catch (Throwable ignored) { }
            try { if (createdCodec != null) createdCodec.release(); } catch (Throwable ignored) { }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Video encoder init failed: " + e.getMessage(), e);
        }
        codec = createdCodec;
        inputSurface = createdSurface;
    }

    /** Must be called on the GLSurfaceView render thread while its EGL context is current. */
    public boolean ensureEglSurface() {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) return true;
        EGLDisplay display = EGL14.eglGetCurrentDisplay();
        EGLContext shared = EGL14.eglGetCurrentContext();
        if (display == EGL14.EGL_NO_DISPLAY || shared == EGL14.EGL_NO_CONTEXT) return false;
        final int EGL_RECORDABLE_ANDROID = 0x3142;
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] < 1) return false;
        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        EGLContext context = EGL14.eglCreateContext(display, configs[0], shared, ctxAttrs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) return false;
        int[] surfAttrs = {EGL14.EGL_NONE};
        EGLSurface surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, surfAttrs, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context);
            return false;
        }
        eglDisplay = display;
        eglContext = context;
        encoderEglSurface = surface;
        return true;
    }

    public boolean makeEncoderCurrent() {
        return encoderEglSurface != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext);
    }

    public void setPresentationTime(long nanos) {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, nanos);
    }

    public boolean swapBuffers() {
        return encoderEglSurface != EGL14.EGL_NO_SURFACE && EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface);
    }

    public void restorePreview(EGLDisplay display, EGLSurface preview, EGLContext context) {
        EGL14.eglMakeCurrent(display, preview, preview, context);
    }

    public void drain(boolean endOfStream) {
        if (endOfStream && !eosSignaled) {
            try { codec.signalEndOfInputStream(); } catch (Throwable ignored) { }
            eosSignaled = true;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idleCount = 0;
        while (true) {
            int status = codec.dequeueOutputBuffer(info, endOfStream ? 10_000 : 0);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
                if (++idleCount > 200) break;
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (trackIndex < 0) trackIndex = muxer.addTrack(codec.getOutputFormat());
            } else if (status >= 0) {
                ByteBuffer out = codec.getOutputBuffer(status);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (out != null && info.size > 0 && trackIndex >= 0) muxer.writeSampleData(trackIndex, out, info);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (eos) outputEosSeen = true;
                codec.releaseOutputBuffer(status, false);
                if (eos) break;
            }
        }
    }

    public boolean hasOutputEos() { return outputEosSeen; }

    public void release() {
        try { if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEglSurface); } catch (Throwable ignored) { }
        try { if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext); } catch (Throwable ignored) { }
        encoderEglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        try { codec.stop(); } catch (Throwable ignored) { }
        try { codec.release(); } catch (Throwable ignored) { }
        try { inputSurface.release(); } catch (Throwable ignored) { }
    }
}
