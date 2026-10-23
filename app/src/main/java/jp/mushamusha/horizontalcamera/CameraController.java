package jp.mushamusha.horizontalcamera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraController {
    public interface Listener {
        void onCapabilities(List<Integer> fpsValues, Size captureSize, int baseRotation, boolean mirror);
        void onPreviewReady();
        void onCameraError(String message);
        void onAfLockResult(boolean focused);
        void onHardwareStabilizationState(boolean active);
        void onCameraFatalError(String message);
    }

    private final CameraManager manager;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private volatile CameraDevice camera;
    private volatile CameraCaptureSession session;
    private SurfaceTexture texture;
    private Surface cameraSurface;
    private CameraCharacteristics chars;
    private String cameraId;
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int fps = 30;
    private boolean portrait = true;
    private int preferredHeight = 1080;
    private Size captureSize = new Size(1920, 1080);
    private boolean torch;
    private int exposureComp = 0;
    private volatile boolean opening = false;
    private volatile int cameraGeneration = 0;
    private volatile int sessionGeneration = 0;
    private volatile boolean reconfiguring = false;
    private final AtomicBoolean repeatUpdatePending = new AtomicBoolean(false);

    private boolean afLock;
    private boolean aeLock;
    private boolean awbLock;
    private boolean hardwareStabilization = true;
    private boolean hardwareStabilizationActive;
    private boolean aeLockActive;
    private boolean awbLockActive;
    private boolean afLockReported;
    private int afLockFramesRemaining = 0;
    private int awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    private boolean manualExposure;
    private int manualIso = 100;
    private long manualExposureNs = 16_666_667L;
    private float zoomRatio = 1f;

    public CameraController(Context context, Listener listener) {
        this.listener = listener;
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void startThread() {
        if (thread != null) return;
        thread = new HandlerThread("Camera2Thread");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void stopThread() {
        close();
        HandlerThread oldThread = thread;
        thread = null;
        handler = null;
        if (oldThread != null) oldThread.quitSafely();
    }

    public boolean setPortrait(boolean portrait) {
        if (this.portrait == portrait) return true;
        boolean oldPortrait = this.portrait;
        int oldFps = fps;
        this.portrait = portrait;
        if (chars == null) return true;
        List<Integer> fpsValues = getSupportedCommonFps();
        if (fpsValues.isEmpty()) {
            this.portrait = oldPortrait;
            listener.onCameraError("この向きで利用できる動画エンコーダ設定がありません");
            return false;
        }
        if (!fpsValues.contains(fps)) fps = fpsValues.get(0);
        Size old = captureSize;
        Size next = chooseSizeForFps(fps);
        if (next == null) {
            this.portrait = oldPortrait;
            fps = oldFps;
            listener.onCameraError("この向きで利用できる撮影解像度がありません");
            return false;
        }
        captureSize = next;
        boolean sizeChanged = old == null || !old.equals(captureSize);
        if (sizeChanged) reconfiguring = true;
        if (sizeChanged && texture != null) texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
        notifyCapabilities();
        if (sizeChanged) recreateSession();
        else updateRepeatingRequest();
        return true;
    }

    public boolean isPreviewActive() { return camera != null && session != null && !reconfiguring; }
    public Size getCaptureSize() { return captureSize; }
    public int getFps() { return fps; }
    public int getPreferredHeight() { return preferredHeight; }
    public boolean isFront() { return lensFacing == CameraCharacteristics.LENS_FACING_FRONT; }
    public boolean isTorch() { return torch; }
    public boolean isAfLock() { return afLock; }
    public boolean isAeLock() { return aeLock; }
    public boolean isAwbLock() { return awbLock; }
    public boolean isHardwareStabilizationActive() { return hardwareStabilizationActive; }
    public boolean isAeLockActive() { return aeLockActive; }
    public boolean isAwbLockActive() { return awbLockActive; }
    public boolean isManualExposure() { return manualExposure; }
    public int getManualIso() { return manualIso; }
    public long getManualExposureNs() { return manualExposureNs; }
    public float getZoomRatio() { return zoomRatio; }

    @SuppressLint("MissingPermission")
    public void open(SurfaceTexture surfaceTexture) {
        startThread();
        if (opening || camera != null) return;
        this.texture = surfaceTexture;
        try {
            cameraId = chooseCameraId(lensFacing);
            if (cameraId == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No matching camera");
            chars = manager.getCameraCharacteristics(cameraId);
            Integer actualFacing = chars.get(CameraCharacteristics.LENS_FACING);
            if (actualFacing != null) lensFacing = actualFacing;
            sanitizeSettingsForCurrentCamera();
            List<Integer> fpsValues = getSupportedCommonFps();
            if (fpsValues.isEmpty()) throw new IllegalStateException("このカメラに対応するH.264撮影モードがありません");
            if (!fpsValues.contains(fps)) fps = fpsValues.get(0);
            Size chosen = chooseSizeForFps(fps);
            if (chosen == null) throw new IllegalStateException("対応する撮影解像度を選べません");
            captureSize = chosen;
            texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
            if (cameraSurface != null) cameraSurface.release();
            cameraSurface = new Surface(texture);
            opening = true;
            final int generation = ++cameraGeneration;
            manager.openCamera(cameraId, createStateCallback(generation), handler);
        } catch (Throwable e) {
            opening = false;
            listener.onCameraError("カメラを開けません: " + e.getMessage());
        }
    }

    private String chooseCameraId(int facing) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (f != null && f == facing) return id;
        }
        return fallback;
    }

    private CameraDevice.StateCallback createStateCallback(final int generation) {
        return new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice cameraDevice) {
                if (generation != cameraGeneration || !opening) {
                    try { cameraDevice.close(); } catch (Throwable ignored) { }
                    return;
                }
                opening = false;
                camera = cameraDevice;
                notifyCapabilities();
                createSession();
            }

            @Override public void onDisconnected(CameraDevice cameraDevice) {
                try { cameraDevice.close(); } catch (Throwable ignored) { }
                if (generation == cameraGeneration) {
                    handleFatalDeviceClosure();
                    listener.onCameraFatalError("カメラが切断されました");
                }
            }

            @Override public void onError(CameraDevice cameraDevice, int error) {
                try { cameraDevice.close(); } catch (Throwable ignored) { }
                if (generation == cameraGeneration) {
                    handleFatalDeviceClosure();
                    listener.onCameraFatalError("Camera2 error: " + error);
                }
            }
        };
    }

    private void handleFatalDeviceClosure() {
        opening = false;
        reconfiguring = false;
        sessionGeneration++;
        CameraCaptureSession oldSession = session;
        session = null;
        if (oldSession != null) {
            try { oldSession.close(); } catch (Throwable ignored) { }
        }
        camera = null;
        chars = null;
        hardwareStabilizationActive = false;
        afLockFramesRemaining = 0;
        aeLockActive = false;
        awbLockActive = false;
        repeatUpdatePending.set(false);
        stateMonitorFramesRemaining = 0;
        captureStateCallbackAttached = false;
    }

    private void sanitizeSettingsForCurrentCamera() {
        if (chars == null) return;
        if (!supportsAfLock()) {
            afLock = false;
            afLockReported = false;
        }
        if (!supportsAeLock()) {
            aeLock = false;
            aeLockActive = false;
        }
        if (!supportsAwbLock()) {
            awbLock = false;
            awbLockActive = false;
        }
        if (!supportsManualExposure()) {
            manualExposure = false;
        }
        List<Integer> wbModes = getSupportedAwbModes();
        if (!wbModes.contains(awbMode)) awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        if (!supportsHardwareStabilization()) {
            hardwareStabilizationActive = false;
        }
    }

    private void notifyCapabilities() {
        if (chars == null) return;
        listener.onCapabilities(getSupportedCommonFps(), captureSize, getBaseRotation(), isFront());
    }

    /**
     * The app deliberately exposes only regular 30/60fps camera streams.
     * Constrained-high-speed (120fps etc.) is not exposed because the GL horizon/LUT pipeline
     * cannot guarantee that every high-speed frame reaches the encoder on all devices.
     */
    public List<Integer> getSupportedCommonFps() {
        if (chars == null) return Collections.singletonList(30);
        List<Integer> result = new ArrayList<>();
        for (int candidate : new int[]{30, 60}) {
            Size s = findNormalSizeNearHeight(preferredHeight, candidate);
            if (s != null) result.add(candidate);
        }
        if (result.isEmpty()) {
            Size fallback = findFallbackSize(preferredHeight);
            if (fallback != null) result.add(30);
        }
        Collections.sort(result);
        return result;
    }

    public List<Integer> getSupportedResolutionHeights() {
        if (chars == null) return Arrays.asList(2160, 1080, 720);
        List<Integer> out = new ArrayList<>();
        for (int h : new int[]{2160, 1080, 720}) {
            if (findNormalSizeNearHeight(h, 30) != null) out.add(h);
        }
        if (out.isEmpty()) out.add(Math.min(captureSize.getWidth(), captureSize.getHeight()));
        return out;
    }

    public void setResolutionHeight(int height) {
        if (preferredHeight == height && chars != null) return;
        int oldPreferred = preferredHeight;
        int oldFps = fps;
        preferredHeight = height;
        if (chars == null) return;
        List<Integer> fpsValues = getSupportedCommonFps();
        if (fpsValues.isEmpty()) {
            preferredHeight = oldPreferred;
            listener.onCameraError("この解像度で利用できる動画エンコーダ設定がありません");
            return;
        }
        if (!fpsValues.contains(fps)) fps = fpsValues.get(0);
        Size old = captureSize;
        Size next = chooseSizeForFps(fps);
        if (next == null) {
            preferredHeight = oldPreferred;
            fps = oldFps;
            listener.onCameraError("この解像度を利用できません");
            return;
        }
        captureSize = next;
        boolean sizeChanged = old == null || !old.equals(captureSize);
        if (sizeChanged) reconfiguring = true;
        if (sizeChanged && texture != null) texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
        notifyCapabilities();
        if (sizeChanged) recreateSession();
        else updateRepeatingRequest();
    }

    private Range<Integer> findRegularRange(int target) {
        if (chars == null) return null;
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= target && r.getUpper() >= target) {
                if (best == null) best = r;
                else if (r.getLower() == target && r.getUpper() == target) best = r;
                else if ((r.getUpper() - r.getLower()) < (best.getUpper() - best.getLower())) best = r;
            }
        }
        return best;
    }

    private boolean supportsRegularAtSize(Size size, int targetFps) {
        Range<Integer> range = findRegularRange(targetFps);
        if (range == null || chars == null) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return false;
        try {
            long minDuration = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
            if (minDuration > 0L) {
                double maxFps = 1_000_000_000.0 / minDuration;
                return maxFps + 0.5 >= targetFps;
            }
        } catch (Throwable ignored) { }
        return targetFps <= range.getUpper();
    }

    private Size chooseSizeForFps(int targetFps) {
        if (chars == null) return new Size(1920, 1080);
        Size normal = findNormalSizeNearHeight(preferredHeight, targetFps);
        if (normal != null) return normal;
        if (targetFps == 30) return findFallbackSize(preferredHeight);
        return null;
    }

    private Size findFallbackSize(int targetHeight) {
        if (chars == null) return null;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] normal = map.getOutputSizes(SurfaceTexture.class);
        if (normal == null || normal.length == 0) return null;
        List<Size> candidates = new ArrayList<>();
        for (Size s : normal) {
            if (isSixteenNine(s) && longSide(s) <= 4096 && supportsRegularAtSize(s, 30) && isAvcEncodable(s, 30)) candidates.add(s);
        }
        if (candidates.isEmpty()) {
            for (Size s : normal) if (supportsRegularAtSize(s, 30) && isAvcEncodable(s, 30)) candidates.add(s);
        }
        return closestHeight(candidates, targetHeight);
    }

    private Size findNormalSizeNearHeight(int targetHeight, int targetFps) {
        if (chars == null) return null;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] normal = map.getOutputSizes(SurfaceTexture.class);
        if (normal == null) return null;
        List<Size> candidates = new ArrayList<>();
        for (Size s : normal) {
            if (!isSixteenNine(s)) continue;
            if (longSide(s) > 4096) continue;
            if (Math.abs(shortSide(s) - targetHeight) > Math.max(180, targetHeight / 5)) continue;
            if (supportsRegularAtSize(s, targetFps) && isAvcEncodable(s, targetFps)) candidates.add(s);
        }
        return closestHeight(candidates, targetHeight);
    }

    private boolean isAvcEncodable(Size s, int targetFps) {
        int outW = portrait ? s.getHeight() : s.getWidth();
        int outH = portrait ? s.getWidth() : s.getHeight();
        return CodecUtil.supportsAvc(outW, outH, targetFps);
    }

    private static boolean isSixteenNine(Size s) {
        float ratio = longSide(s) / (float) shortSide(s);
        return Math.abs(ratio - 16f / 9f) < 0.035f;
    }

    private static int longSide(Size s) { return Math.max(s.getWidth(), s.getHeight()); }
    private static int shortSide(Size s) { return Math.min(s.getWidth(), s.getHeight()); }

    private static Size closestHeight(List<Size> sizes, int targetHeight) {
        if (sizes == null || sizes.isEmpty()) return null;
        return Collections.min(sizes, Comparator.comparingInt(s -> Math.abs(shortSide(s) - targetHeight)));
    }

    public void setFps(int newFps) {
        if (chars == null) { fps = newFps; return; }
        if (!getSupportedCommonFps().contains(newFps)) return;
        if (newFps == fps) return;
        Size old = captureSize;
        fps = newFps;
        Size next = chooseSizeForFps(fps);
        if (next != null) captureSize = next;
        boolean sizeChanged = old == null || !old.equals(captureSize);
        if (sizeChanged) reconfiguring = true;
        if (sizeChanged && texture != null) texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
        notifyCapabilities();
        if (sizeChanged) recreateSession();
        else updateRepeatingRequest();
    }

    public void switchCamera() {
        int targetFacing = lensFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        try {
            if (!hasCameraFacing(targetFacing)) {
                listener.onCameraError("切り替え先のカメラがありません");
                return;
            }
        } catch (CameraAccessException e) {
            listener.onCameraError("カメラ一覧を取得できません: " + e.getMessage());
            return;
        }
        lensFacing = targetFacing;
        torch = false;
        zoomRatio = 1f;
        afLock = false;
        closeCameraOnly();
        if (texture != null) open(texture);
    }

    private boolean hasCameraFacing(int facing) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == facing) return true;
        }
        return false;
    }

    public boolean canSwitchCamera() {
        int targetFacing = lensFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        try { return hasCameraFacing(targetFacing); }
        catch (Throwable ignored) { return false; }
    }

    public boolean supportsTorch() {
        return chars != null && !isFront() && Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
    }

    public void setTorch(boolean enabled) {
        if (enabled && !supportsTorch()) return;
        if (torch == enabled) return;
        torch = enabled;
        updateRepeatingRequest();
    }
    public void setExposureCompensation(int value) {
        exposureComp = value;
        manualExposure = false;
        aeLock = false;
        aeLockActive = false;
        updateRepeatingRequest();
    }

    public Range<Integer> getExposureRange() {
        if (chars == null) return new Range<>(0, 0);
        Range<Integer> r = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        return r != null ? r : new Range<>(0, 0);
    }

    public Range<Integer> getIsoRange() {
        if (chars == null) return new Range<>(50, 3200);
        Range<Integer> r = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        return r != null ? r : new Range<>(50, 3200);
    }

    public Range<Long> getExposureTimeRange() {
        if (chars == null) return new Range<>(100_000L, 1_000_000_000L);
        Range<Long> r = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        return r != null ? r : new Range<>(100_000L, 1_000_000_000L);
    }

    public void setManualExposure(boolean enabled, int iso, long exposureNs) {
        if (enabled && !supportsManualExposure()) return;
        manualExposure = enabled;
        if (enabled) {
            aeLock = false;
            aeLockActive = false;
        }
        Range<Integer> ir = getIsoRange();
        Range<Long> er = getExposureTimeRange();
        manualIso = Math.max(ir.getLower(), Math.min(ir.getUpper(), iso));
        long frameNs = Math.max(1L, 1_000_000_000L / Math.max(1, fps));
        long maxForFps = Math.min(er.getUpper(), frameNs);
        manualExposureNs = Math.max(er.getLower(), Math.min(maxForFps, exposureNs));
        updateRepeatingRequest();
    }

    public boolean supportsAfLock() {
        if (chars == null) return false;
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO || mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) return true;
        }
        return false;
    }

    public boolean supportsAeLock() {
        return chars != null && Boolean.TRUE.equals(chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
    }

    public boolean supportsAwbLock() {
        return chars != null && Boolean.TRUE.equals(chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
    }

    public boolean supportsManualExposure() {
        if (chars == null) return false;
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean manual = false;
        if (caps != null) for (int cap : caps) if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manual = true;
        return manual && chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null
                && chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null;
    }

    public void setAfLock(boolean locked) {
        if (locked && !supportsAfLock()) return;
        if (afLock == locked) return;
        afLock = locked;
        afLockReported = false;
        if (!locked) afLockFramesRemaining = 0;
        Handler currentHandler = handler;
        if (currentHandler == null) return;
        final boolean requestedLock = locked;
        currentHandler.post(() -> {
            // A task queued on a camera thread from before pause/resume must never touch the
            // newly-opened session that may now be using a different HandlerThread.
            if (handler != currentHandler) return;
            // Ignore a queued AF operation if the user changed the switch again before it ran.
            if (afLock != requestedLock) return;
            CameraCaptureSession current = session;
            if (current == null || camera == null) return;
            if (requestedLock) triggerAfLock(current);
            else cancelAfLock(current);
        });
    }
    public void setAeLock(boolean locked) {
        if (locked && !supportsAeLock()) return;
        if (aeLock == locked) return;
        aeLock = locked;
        if (!locked) aeLockActive = false;
        updateRepeatingRequest();
    }
    public void setAwbLock(boolean locked) {
        if (locked && !supportsAwbLock()) return;
        if (awbLock == locked) return;
        awbLock = locked;
        if (!locked) awbLockActive = false;
        updateRepeatingRequest();
    }
    public void setHardwareStabilization(boolean enabled) {
        if (hardwareStabilization == enabled) return;
        hardwareStabilization = enabled;
        if (!enabled && hardwareStabilizationActive) {
            hardwareStabilizationActive = false;
            listener.onHardwareStabilizationState(false);
        }
        updateRepeatingRequest();
    }

    public boolean supportsHardwareStabilization() {
        if (chars == null) return false;
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (modes != null) {
            for (int m : modes) {
                if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) return true;
                if (Build.VERSION.SDK_INT >= 33 && m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) return true;
            }
        }
        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois != null) for (int m : ois) if (m == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) return true;
        return false;
    }

    public void setAwbMode(int mode) {
        if (awbMode == mode && !awbLock) return;
        awbMode = mode;
        awbLock = false;
        awbLockActive = false;
        updateRepeatingRequest();
    }
    public int getAwbMode() { return awbMode; }

    public List<Integer> getSupportedAwbModes() {
        if (chars == null) return Collections.singletonList(CaptureRequest.CONTROL_AWB_MODE_AUTO);
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        List<Integer> out = new ArrayList<>();
        if (modes != null) for (int m : modes) out.add(m);
        return out;
    }

    public Range<Float> getZoomRange() {
        if (chars == null) return new Range<>(1f, 1f);
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return r;
        }
        Float max = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return new Range<>(1f, max == null ? 1f : Math.max(1f, max));
    }

    public void setZoomRatio(float ratio) {
        Range<Float> range = getZoomRange();
        float next = Math.max(range.getLower(), Math.min(range.getUpper(), ratio));
        if (Math.abs(next - zoomRatio) < 0.001f) return;
        zoomRatio = next;
        updateRepeatingRequest();
    }

    public boolean supportsHevcForCurrentMode() {
        int outW = portrait ? captureSize.getHeight() : captureSize.getWidth();
        int outH = portrait ? captureSize.getWidth() : captureSize.getHeight();
        return CodecUtil.supportsHevc(outW, outH, fps);
    }

    private int getBaseRotation() {
        if (chars == null) return portrait ? 90 : 0;
        Integer sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int so = sensor == null ? 90 : sensor;
        int target = portrait ? 0 : 90;
        if (isFront()) return (so + target) % 360;
        return (so - target + 360) % 360;
    }

    private void updateRepeatingRequest() {
        Handler currentHandler = handler;
        if (currentHandler == null) return;
        if (!repeatUpdatePending.compareAndSet(false, true)) return;
        boolean posted = currentHandler.post(() -> {
            if (handler != currentHandler) {
                repeatUpdatePending.set(false);
                return;
            }
            repeatUpdatePending.set(false);
            CameraCaptureSession current = session;
            if (current == null || camera == null || cameraSurface == null || chars == null) return;
            try {
                if (afLock && !afLockReported) {
                    // triggerAfLock builds and installs the latest repeating request itself.
                    triggerAfLock(current);
                } else {
                    CaptureRequest.Builder b = buildRepeatingBuilder();
                    armStateMonitoring();
                    current.setRepeatingRequest(b.build(), captureStateCallback, currentHandler);
                }
            } catch (Throwable e) {
                listener.onCameraError("カメラ設定を反映できません: " + e.getMessage());
            }
        });
        if (!posted) repeatUpdatePending.set(false);
    }

    private void recreateSession() {
        final Handler currentHandler = handler;
        if (currentHandler == null) return;
        final int generation = ++sessionGeneration;
        reconfiguring = true;
        boolean posted = currentHandler.post(() -> {
            if (handler != currentHandler || generation != sessionGeneration) return;
            CameraCaptureSession old = session;
            session = null;
            if (old != null) {
                try { old.stopRepeating(); } catch (Throwable ignored) { }
                try { old.abortCaptures(); } catch (Throwable ignored) { }
                try { old.close(); } catch (Throwable ignored) { }
            }
            createSession(generation);
        });
        if (!posted && generation == sessionGeneration) reconfiguring = false;
    }

    private void createSession() {
        createSession(++sessionGeneration);
    }

    private void createSession(final int generation) {
        CameraDevice currentCamera = camera;
        final Handler callbackHandler = handler;
        if (currentCamera == null || cameraSurface == null || chars == null || callbackHandler == null) {
            if (generation == sessionGeneration) reconfiguring = false;
            return;
        }
        try {
            currentCamera.createCaptureSession(Collections.singletonList(cameraSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (handler != callbackHandler || generation != sessionGeneration || camera == null || camera != currentCamera) {
                        try { s.close(); } catch (Throwable ignored) { }
                        return;
                    }
                    session = s;
                    reconfiguring = false;
                    afLockReported = false;
                    try {
                        CaptureRequest.Builder b = buildRepeatingBuilder();
                        armStateMonitoring();
                        s.setRepeatingRequest(b.build(), captureStateCallback, callbackHandler);
                        if (afLock) triggerAfLock(s);
                        listener.onPreviewReady();
                    } catch (Throwable e) {
                        if (session == s) session = null;
                        try { s.close(); } catch (Throwable ignored) { }
                        listener.onCameraError("プレビュー開始エラー: " + e.getMessage());
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    try { s.close(); } catch (Throwable ignored) { }
                    if (handler == callbackHandler && generation == sessionGeneration) {
                        reconfiguring = false;
                        listener.onCameraError("撮影セッションの構成に失敗しました");
                    }
                }
            }, callbackHandler);
        } catch (Throwable e) {
            if (handler == callbackHandler && generation == sessionGeneration) {
                reconfiguring = false;
                listener.onCameraError("撮影セッションを開始できません: " + e.getMessage());
            }
        }
    }

    private CaptureRequest.Builder buildRepeatingBuilder() throws CameraAccessException {
        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(cameraSurface);
        applyCommon(b);
        Range<Integer> range = findRegularRange(fps);
        if (!manualExposure && range != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        return b;
    }

    private void triggerAfLock(CameraCaptureSession s) {
        if (camera == null || !afLock) return;
        try {
            CaptureRequest.Builder trigger = buildRepeatingBuilder();
            trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            afLockFramesRemaining = 120;
            armStateMonitoring();
            s.capture(trigger.build(), captureStateCallback, handler);

            // START persists in the AF state machine. Return the repeating request to IDLE while
            // observing results until FOCUSED_LOCKED / NOT_FOCUSED_LOCKED is reported.
            CaptureRequest.Builder hold = buildRepeatingBuilder();
            s.setRepeatingRequest(hold.build(), captureStateCallback, handler);
        } catch (Throwable e) {
            afLock = false;
            afLockReported = true;
            afLockFramesRemaining = 0;
            listener.onAfLockResult(false);
            try {
                CaptureRequest.Builder normal = buildRepeatingBuilder();
                s.setRepeatingRequest(normal.build(), captureStateCallback, handler);
            } catch (Throwable ignored) { }
        }
    }

    private void cancelAfLock(CameraCaptureSession s) {
        if (camera == null) return;
        afLockFramesRemaining = 0;
        try {
            CaptureRequest.Builder cancel = buildRepeatingBuilder();
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            armStateMonitoring();
            s.capture(cancel.build(), captureStateCallback, handler);
            CaptureRequest.Builder normal = buildRepeatingBuilder();
            s.setRepeatingRequest(normal.build(), captureStateCallback, handler);
        } catch (Throwable e) {
            listener.onCameraError("AF LOCKを解除できません: " + e.getMessage());
        }
    }

    private int captureStateFrameCounter = 0;
    private int stateMonitorFramesRemaining = 0;
    private boolean captureStateCallbackAttached = false;

    private void armStateMonitoring() {
        captureStateFrameCounter = 0;
        // Do not monitor forever, but give slow vendor pipelines enough time to report AE/WB/HW
        // stabilization state. The callback is detached early as soon as requested states settle.
        stateMonitorFramesRemaining = 90;
        captureStateCallbackAttached = true;
    }

    private boolean monitoredStatesSettled() {
        boolean hwSettled = !hardwareStabilization || !supportsHardwareStabilization() || hardwareStabilizationActive;
        boolean aeSettled = !aeLock || aeLockActive;
        boolean awbSettled = !awbLock || awbLockActive;
        return hwSettled && aeSettled && awbSettled;
    }

    private void detachStateMonitoring(CameraCaptureSession currentSession) {
        if (!captureStateCallbackAttached || currentSession == null || currentSession != session || camera == null) return;
        captureStateCallbackAttached = false;
        try {
            CaptureRequest.Builder b = buildRepeatingBuilder();
            currentSession.setRepeatingRequest(b.build(), null, handler);
        } catch (Throwable ignored) {
            // A later setting change/session recreation will arm monitoring again. Avoid looping
            // on a vendor-specific failure to detach the callback.
        }
    }

    private final CameraCaptureSession.CaptureCallback captureStateCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            // Only inspect metadata for a short window after a request/session change. Repeating
            // callbacks still arrive from Camera2, but once the state has settled we avoid doing
            // CaptureResult lookups forever at 30/60 fps. AF lock remains observed every frame
            // until it reaches a terminal lock state.
            boolean afPending = afLock && !afLockReported;
            boolean periodicStateCheck = false;
            if (stateMonitorFramesRemaining > 0) {
                stateMonitorFramesRemaining--;
                periodicStateCheck = (++captureStateFrameCounter % 5) == 0;
            }
            if (periodicStateCheck) {
                if (hardwareStabilization) updateStabilizationResult(result);
                if (aeLock || awbLock) updateAutoLockResult(result);
                if (!afPending && monitoredStatesSettled()) stateMonitorFramesRemaining = 0;
            }
            if (!afPending) {
                if (stateMonitorFramesRemaining <= 0) detachStateMonitoring(session);
                return;
            }
            if (afLockFramesRemaining > 0 && --afLockFramesRemaining == 0) {
                afLock = false;
                afLockReported = true;
                listener.onAfLockResult(false);
                // A timed-out START must not leave the UI claiming AF LOCK is ON. Return the
                // camera to its normal continuous/auto AF state.
                cancelAfLock(session);
                return;
            }
            Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
            if (state == null) return;
            if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                afLockReported = true;
                listener.onAfLockResult(true);
                if (monitoredStatesSettled()) stateMonitorFramesRemaining = 0;
                if (stateMonitorFramesRemaining <= 0) detachStateMonitoring(session);
            } else if (state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                afLockReported = true;
                listener.onAfLockResult(false);
                if (monitoredStatesSettled()) stateMonitorFramesRemaining = 0;
                if (stateMonitorFramesRemaining <= 0) detachStateMonitoring(session);
            }
        }
    };

    private void updateAutoLockResult(TotalCaptureResult result) {
        Boolean ae = result.get(CaptureResult.CONTROL_AE_LOCK);
        Boolean awb = result.get(CaptureResult.CONTROL_AWB_LOCK);
        boolean newAe = Boolean.TRUE.equals(ae);
        boolean newAwb = Boolean.TRUE.equals(awb);
        if (newAe != aeLockActive || newAwb != awbLockActive) {
            aeLockActive = newAe;
            awbLockActive = newAwb;
        }
    }

    private void updateStabilizationResult(TotalCaptureResult result) {
        boolean active = false;
        Integer video = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
        if (video != null && video != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) active = true;
        Integer ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        if (ois != null && ois == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) active = true;
        if (active != hardwareStabilizationActive) {
            hardwareStabilizationActive = active;
            listener.onHardwareStabilizationState(active);
        }
    }

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        boolean continuousVideo = false;
        boolean autoAf = false;
        if (afModes != null) {
            for (int mode : afModes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) continuousVideo = true;
                if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO) autoAf = true;
            }
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, continuousVideo ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO :
                (autoAf ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF));

        if (manualExposure && supportsManualExposure()) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            Range<Integer> isoRange = getIsoRange();
            Range<Long> exposureRange = getExposureTimeRange();
            long frameNs = Math.max(1L, 1_000_000_000L / Math.max(1, fps));
            int iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), manualIso));
            long maxExposureForFrame = Math.min(exposureRange.getUpper(), frameNs);
            long exposure = Math.max(exposureRange.getLower(), Math.min(maxExposureForFrame, manualExposureNs));
            manualIso = iso;
            manualExposureNs = exposure;
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            Range<Integer> er = getExposureRange();
            int ev = Math.max(er.getLower(), Math.min(er.getUpper(), exposureComp));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
            if (Boolean.TRUE.equals(chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))) {
                b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock);
            }
        }

        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
        if (awbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO &&
                Boolean.TRUE.equals(chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))) {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock);
        }

        applyHardwareStabilization(b);

        Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (torch && Boolean.TRUE.equals(flash) && !isFront()) b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);

        Range<Float> zr = getZoomRange();
        float z = Math.max(zr.getLower(), Math.min(zr.getUpper(), zoomRatio));
        if (Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
        } else if (z > 1f) {
            Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null) {
                int w = Math.round(active.width() / z);
                int h = Math.round(active.height() / z);
                int left = active.left + (active.width() - w) / 2;
                int top = active.top + (active.height() - h) / 2;
                b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + w, top + h));
            }
        }
    }

    private void applyHardwareStabilization(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        int[] oisAvailable = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (oisAvailable != null) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        if (!hardwareStabilization) return;

        int[] modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        boolean hasPreview = false;
        boolean hasVideo = false;
        if (modes != null) {
            for (int m : modes) {
                if (Build.VERSION.SDK_INT >= 33 && m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) hasPreview = true;
                if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) hasVideo = true;
            }
        }
        if (hasPreview) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
            return;
        }
        if (hasVideo) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            return;
        }
        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois != null) {
            for (int m : ois) {
                if (m == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                    return;
                }
            }
        }
    }

    public void close() {
        closeCameraOnly();
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
    }

    private void closeCameraOnly() {
        opening = false;
        reconfiguring = false;
        cameraGeneration++;
        sessionGeneration++;
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) { }
            session = null;
        }
        if (camera != null) {
            try { camera.close(); } catch (Throwable ignored) { }
            camera = null;
        }
        chars = null;
        hardwareStabilizationActive = false;
        afLockFramesRemaining = 0;
        aeLockActive = false;
        awbLockActive = false;
        repeatUpdatePending.set(false);
        stateMonitorFramesRemaining = 0;
        captureStateCallbackAttached = false;
    }
}
