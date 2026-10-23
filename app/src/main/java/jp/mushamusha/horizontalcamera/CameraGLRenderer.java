package jp.mushamusha.horizontalcamera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.Surface;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class CameraGLRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    public interface Listener {
        void onCameraInputReady(SurfaceTexture texture);
        void onRenderError(String message);
        void onCropRatio(float ratio);
        void onMeasuredFps(float fps);
    }

    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
    };

    private static final String VS =
            "#version 300 es\n" +
            "layout(location=0) in vec2 aPos;\n" +
            "layout(location=1) in vec2 aTex;\n" +
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "out vec2 vTex;\n" +
            "void main(){ gl_Position=uMvp*vec4(aPos,0.0,1.0); vTex=(uTexMatrix*vec4(aTex,0.0,1.0)).xy; }\n";

    private static final String FS =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES sCamera;\n" +
            "uniform sampler3D sLut;\n" +
            "uniform int uUseLut;\n" +
            "uniform vec3 uLutDomainMin;\n" +
            "uniform vec3 uLutDomainMax;\n" +
            "in vec2 vTex;\n" +
            "out vec4 frag;\n" +
            "void main(){ vec3 c=texture(sCamera,vTex).rgb; if(uUseLut==1){ vec3 span=max(uLutDomainMax-uLutDomainMin,vec3(0.00001)); vec3 uv=clamp((c-uLutDomainMin)/span,0.0,1.0); c=texture(sLut,uv).rgb; } frag=vec4(c,1.0); }\n";

    private final GLSurfaceView view;
    private final Listener listener;
    private final FloatBuffer quad;
    private final float[] stMatrix = new float[16];
    private final float[] extraTex = new float[16];
    private final float[] combinedTex = new float[16];
    private final float[] mvp = new float[16];
    private final float[] tmp1 = new float[16];
    private final float[] tmp2 = new float[16];
    private final float[] projAspect = new float[16];
    private final float[] projAspectInv = new float[16];
    private final float[] rotationMatrix = new float[16];
    private final float[] zoomMatrix = new float[16];
    private final int[] previewViewport = new int[4];
    private SurfaceTexture cameraTexture;
    private final AtomicBoolean cameraFrameAvailable = new AtomicBoolean(false);
    private boolean haveCameraFrame = false;
    private int oesTexture;
    private int vbo;
    private int program;
    private int lutTexture;
    private int mvpLoc, texMatrixLoc, cameraSamplerLoc, lutSamplerLoc, useLutLoc, lutDomainMinLoc, lutDomainMaxLoc;
    private int surfaceW = 1, surfaceH = 1;
    private volatile int outputW = 1080, outputH = 1920;
    private volatile int cameraBaseRotation = 90;
    private volatile boolean mirror = false;
    private volatile float stabilizationAngle = 0f;
    private volatile float cropBoost = 1f;
    private volatile float maxCropRatio = 2.2f;
    private float lastReportedCrop = -1f;
    private long lastCropReportNs = 0L;
    private long recordingBaseTimestampNs = -1L;
    private long lastEncodedTimestampNs = -1L;
    private volatile VideoEncoder encoder;
    private volatile boolean recording;
    private boolean encoderFailureReported;
    private volatile String lastStopError;
    private CubeLut pendingLut;
    private CubeLut currentLut;
    private volatile boolean lutEnabled;
    private long fpsWindowStartNs;
    private int fpsWindowFrames;
    private volatile boolean measureRecordingFps;
    private final float[] lutDomainMin = new float[]{0f, 0f, 0f};
    private final float[] lutDomainMax = new float[]{1f, 1f, 1f};

    public CameraGLRenderer(GLSurfaceView view, Listener listener) {
        this.view = view;
        this.listener = listener;
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).flip();
        Matrix.setIdentityM(stMatrix, 0);
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            if (cameraTexture != null) {
                try { cameraTexture.release(); } catch (Throwable ignored) { }
                cameraTexture = null;
            }
            cameraFrameAvailable.set(false);
            haveCameraFrame = false;
            program = link(VS, FS);
            mvpLoc = GLES30.glGetUniformLocation(program, "uMvp");
            texMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix");
            cameraSamplerLoc = GLES30.glGetUniformLocation(program, "sCamera");
            lutSamplerLoc = GLES30.glGetUniformLocation(program, "sLut");
            useLutLoc = GLES30.glGetUniformLocation(program, "uUseLut");
            lutDomainMinLoc = GLES30.glGetUniformLocation(program, "uLutDomainMin");
            lutDomainMaxLoc = GLES30.glGetUniformLocation(program, "uLutDomainMax");
            lutTexture = 0;
            if (lutEnabled && currentLut != null) pendingLut = currentLut;
            int[] buffers = new int[1];
            GLES30.glGenBuffers(1, buffers, 0);
            vbo = buffers[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            quad.position(0);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD.length * 4, quad, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            oesTexture = createExternalTexture();
            cameraTexture = new SurfaceTexture(oesTexture);
            cameraTexture.setOnFrameAvailableListener(this);
            listener.onCameraInputReady(cameraTexture);
        } catch (Throwable e) {
            listener.onRenderError("OpenGL初期化に失敗しました: " + e.getMessage());
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        if (cameraTexture == null) return;
        boolean newCameraFrame = cameraFrameAvailable.getAndSet(false);
        if (newCameraFrame) {
            try {
                cameraTexture.updateTexImage();
                cameraTexture.getTransformMatrix(stMatrix);
                haveCameraFrame = true;
                if (recording && measureRecordingFps) updateMeasuredFps();
            } catch (RuntimeException ignored) {
                return;
            }
        }
        if (pendingLut != null) {
            uploadLut(pendingLut);
            pendingLut = null;
        }
        if (!haveCameraFrame) return;
        buildMatrices();

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        int[] vp = previewViewport();
        GLES30.glViewport(vp[0], vp[1], vp[2], vp[3]);
        drawFrame();

        VideoEncoder e = encoder;
        if (recording && e != null && newCameraFrame) {
            try {
                if (!e.ensureEglSurface()) throw new IllegalStateException("録画用EGL Surfaceを作成できません");
                EGLDisplay display = EGL14.eglGetCurrentDisplay();
                EGLContext context = EGL14.eglGetCurrentContext();
                EGLSurface preview = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                if (!e.makeEncoderCurrent()) throw new IllegalStateException("録画用EGL Contextへ切り替えできません");
                try {
                    GLES30.glViewport(0, 0, outputW, outputH);
                    GLES30.glClearColor(0f, 0f, 0f, 1f);
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                    drawFrame();
                    long ts = cameraTexture.getTimestamp();
                    if (ts <= 0) ts = System.nanoTime();
                    if (recordingBaseTimestampNs < 0L) recordingBaseTimestampNs = ts;
                    long presentation = Math.max(0L, ts - recordingBaseTimestampNs);
                    if (lastEncodedTimestampNs >= 0L && presentation <= lastEncodedTimestampNs) presentation = lastEncodedTimestampNs + 1L;
                    lastEncodedTimestampNs = presentation;
                    e.setPresentationTime(presentation);
                    if (!e.swapBuffers()) throw new IllegalStateException("録画フレームをエンコーダへ送れません");
                } finally {
                    e.restorePreview(display, preview, context);
                }
                e.drain(false);
            } catch (Throwable failure) {
                recording = false;
                if (!encoderFailureReported) {
                    encoderFailureReported = true;
                    view.post(() -> listener.onRenderError("録画処理エラー: " + failure.getMessage()));
                }
            }
        }
    }

    private void updateMeasuredFps() {
        long now = System.nanoTime();
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = now;
        fpsWindowFrames++;
        long elapsed = now - fpsWindowStartNs;
        if (elapsed >= 1_000_000_000L) {
            float measured = fpsWindowFrames * 1_000_000_000f / elapsed;
            fpsWindowStartNs = now;
            fpsWindowFrames = 0;
            view.post(() -> listener.onMeasuredFps(measured));
        }
    }

    private void buildMatrices() {
        Matrix.setIdentityM(extraTex, 0);
        Matrix.translateM(extraTex, 0, 0.5f, 0.5f, 0f);
        if (mirror) Matrix.scaleM(extraTex, 0, -1f, 1f, 1f);
        Matrix.rotateM(extraTex, 0, cameraBaseRotation, 0f, 0f, 1f);
        Matrix.translateM(extraTex, 0, -0.5f, -0.5f, 0f);
        Matrix.multiplyMM(combinedTex, 0, stMatrix, 0, extraTex, 0);

        float r = outputW / (float) outputH;
        float theta = (float) Math.toRadians(stabilizationAngle);
        float c = Math.abs((float) Math.cos(theta));
        float s = Math.abs((float) Math.sin(theta));
        float zoom = Math.max(c + s / r, s * r + c);
        zoom = Math.max(1f, Math.min(maxCropRatio, zoom * cropBoost));
        long cropNowNs = System.nanoTime();
        if (Math.abs(zoom - lastReportedCrop) >= 0.01f && cropNowNs - lastCropReportNs >= 100_000_000L) {
            lastReportedCrop = zoom;
            lastCropReportNs = cropNowNs;
            final float report = zoom;
            view.post(() -> listener.onCropRatio(report));
        }

        Matrix.setIdentityM(projAspect, 0);
        Matrix.setIdentityM(projAspectInv, 0);
        Matrix.setIdentityM(rotationMatrix, 0);
        Matrix.setIdentityM(zoomMatrix, 0);
        Matrix.scaleM(projAspect, 0, r, 1f, 1f);
        Matrix.scaleM(projAspectInv, 0, 1f / r, 1f, 1f);
        Matrix.rotateM(rotationMatrix, 0, stabilizationAngle, 0f, 0f, 1f);
        Matrix.scaleM(zoomMatrix, 0, zoom, zoom, 1f);
        Matrix.multiplyMM(tmp1, 0, rotationMatrix, 0, projAspect, 0);
        Matrix.multiplyMM(tmp2, 0, projAspectInv, 0, tmp1, 0);
        Matrix.multiplyMM(mvp, 0, zoomMatrix, 0, tmp2, 0);
    }

    private int[] previewViewport() {
        float target = outputW / (float) outputH;
        float screen = surfaceW / (float) surfaceH;
        if (screen > target) {
            int w = Math.round(surfaceH * target);
            previewViewport[0] = (surfaceW - w) / 2;
            previewViewport[1] = 0;
            previewViewport[2] = w;
            previewViewport[3] = surfaceH;
        } else {
            int h = Math.round(surfaceW / target);
            previewViewport[0] = 0;
            previewViewport[1] = (surfaceH - h) / 2;
            previewViewport[2] = surfaceW;
            previewViewport[3] = h;
        }
        return previewViewport;
    }

    private void drawFrame() {
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, combinedTex, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glUniform1i(cameraSamplerLoc, 0);
        if (lutTexture != 0 && lutEnabled) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture);
            GLES30.glUniform1i(lutSamplerLoc, 1);
            GLES30.glUniform3fv(lutDomainMinLoc, 1, lutDomainMin, 0);
            GLES30.glUniform3fv(lutDomainMaxLoc, 1, lutDomainMax, 0);
            GLES30.glUniform1i(useLutLoc, 1);
        } else {
            GLES30.glUniform1i(useLutLoc, 0);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    public void setCameraGeometry(int sensorRotationForOutput, boolean mirror) {
        this.cameraBaseRotation = sensorRotationForOutput;
        this.mirror = mirror;
        view.requestRender();
    }

    public void setOutputSize(int width, int height) {
        outputW = width;
        outputH = height;
        view.requestRender();
    }

    public void setStabilizationAngle(float degrees) {
        // Camera frames already drive rendering at 30/60fps. Avoid extra sensor-rate GL passes.
        stabilizationAngle = degrees;
    }

    public void setCropBoost(float value) {
        cropBoost = Math.max(1f, Math.min(1.20f, value));
        view.requestRender();
    }

    public void setMaxCropRatio(float value) {
        maxCropRatio = Math.max(1.05f, Math.min(2.2f, value));
        view.requestRender();
    }

    public void startRecording(VideoEncoder encoder, boolean measureFps) {
        this.encoder = encoder;
        this.measureRecordingFps = measureFps;
        this.recordingBaseTimestampNs = -1L;
        this.lastEncodedTimestampNs = -1L;
        this.fpsWindowStartNs = 0L;
        this.fpsWindowFrames = 0;
        this.encoderFailureReported = false;
        this.lastStopError = null;
        this.recording = true;
        view.requestRender();
    }

    public void stopRecording(Runnable onStopped) {
        this.recording = false;
        this.measureRecordingFps = false;
        this.recordingBaseTimestampNs = -1L;
        this.lastEncodedTimestampNs = -1L;
        final VideoEncoder e = this.encoder;
        this.encoder = null;
        view.queueEvent(() -> {
            if (e != null) {
                try {
                    e.drain(true);
                    if (!e.hasOutputEos()) lastStopError = "動画エンコーダの終了を確認できませんでした";
                } catch (Throwable failure) {
                    lastStopError = "動画エンコーダの終了処理に失敗しました: " + failure.getMessage();
                } finally {
                    try { e.release(); } catch (Throwable ignored) { }
                }
            }
            if (onStopped != null) onStopped.run();
        });
    }

    public String getLastStopError() { return lastStopError; }

    public void loadLut(InputStream input) throws IOException {
        applyLut(CubeLut.parse(input));
    }

    public void applyLut(CubeLut lut) {
        if (lut == null) return;
        view.queueEvent(() -> {
            currentLut = lut;
            pendingLut = lut;
            lutEnabled = true;
        });
        view.requestRender();
    }

    public void disableLut() {
        view.queueEvent(() -> lutEnabled = false);
        view.requestRender();
    }

    private void uploadLut(CubeLut lut) {
        System.arraycopy(lut.domainMin, 0, lutDomainMin, 0, 3);
        System.arraycopy(lut.domainMax, 0, lutDomainMax, 0, 3);
        if (lutTexture == 0) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            lutTexture = ids[0];
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
        lut.data.position(0);
        ByteBuffer bytes = ByteBuffer.allocateDirect(lut.size * lut.size * lut.size * 3);
        while (lut.data.hasRemaining()) {
            float v = Math.max(0f, Math.min(1f, lut.data.get()));
            bytes.put((byte) Math.round(v * 255f));
        }
        bytes.flip();
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
                lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
    }

    @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        cameraFrameAvailable.set(true);
        view.requestRender();
    }

    private static int createExternalTexture() {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            GLES30.glDeleteShader(v);
            GLES30.glDeleteShader(f);
            throw new RuntimeException(log);
        }
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String source) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, source);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new RuntimeException(log);
        }
        return s;
    }
}
