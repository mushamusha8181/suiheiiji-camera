package jp.mushamusha.horizontalcamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity implements
        CameraGLRenderer.Listener, CameraController.Listener, HorizonSensor.Listener, AudioEncoder.Listener {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_LUT = 1002;

    private static final int STAB_OFF = 0;
    private static final int STAB_45 = 1;
    private static final int STAB_360 = 2;

    private static final int SMOOTH_QUICK = 0;
    private static final int SMOOTH_STANDARD = 1;
    private static final int SMOOTH_SMOOTH = 2;

    private GLSurfaceView glView;
    private CameraGLRenderer renderer;
    private CameraController cameraController;
    private CameraOverlayView overlay;
    private HorizonSensor horizonSensor;
    private android.graphics.SurfaceTexture cameraInputTexture;

    private Button orientationButton;
    private Button stabButton;
    private Button fpsButton;
    private Button lutButton;
    private Button recordButton;
    private Button torchButton;
    private Button moreButton;
    private Button switchCameraButton;
    private Button galleryButton;
    private TextView statusText;
    private LinearLayout topBar;
    private LinearLayout bottomBar;

    private boolean portrait = true;
    private int stabilizationMode = STAB_45;
    private int smoothingMode = SMOOTH_STANDARD;
    private float currentRoll = 0f;
    private boolean hasRollSample = false;
    private float referenceRoll = 0f;
    private boolean referenceReady = false;
    private float smoothedApplied = 0f;
    private long lastLevelUiNs = 0L;
    private boolean smoothingReady = false;
    private float maxCropRatio = 2.2f;
    private float max45CorrectionAngle = 45f;
    private boolean referenceAtRecordStart = false;
    private boolean hardwareStabilizationEnabled = true;
    private boolean cameraMirror = false;

    private List<Integer> supportedFps = new ArrayList<>();
    private int selectedFps = 30;
    private boolean recording = false;
    private boolean stoppingRecording = false;
    private CountDownLatch recordingStopLatch;
    private String recordingFailureMessage;
    private boolean gridEnabled = true;
    private boolean levelEnabled = true;
    private boolean initialOrientationDialogShowing = false;
    private boolean defaultLutLoaded = false;
    private boolean uiLockDuringRecording = true;
    private int lowFpsWindows = 0;
    private boolean frameDropWarningShown = false;

    private boolean audioEnabled = true;
    private int preferredAudioDeviceId = -1;
    private String micLabel = "自動マイク";
    private boolean audioAutoGain = AudioEncoder.isAutoGainAvailable();
    private float audioGainDb = 0f;
    private int lowCutMode = AudioEncoder.LOW_CUT_OFF;

    private String lutLabel = "Natural";
    private String videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int bitrateQuality = 1;

    private Uri activeVideoUri;
    private ParcelFileDescriptor activePfd;
    private MuxerController muxer;
    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;

    private PowerManager powerManager;
    private boolean thermalListenerRegistered = false;
    private boolean activityResumed = false;
    private boolean glPaused = true;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = this::handleThermalStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        portrait = getPreferences(MODE_PRIVATE).getBoolean("portrait_mode", true);
        if (getPreferences(MODE_PRIVATE).getBoolean("orientation_chosen", false)) {
            setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        cameraController = new CameraController(this, this);
        horizonSensor = new HorizonSensor(this, this);
        buildUi();
        applyStabilizationTuning();
        if (!getPreferences(MODE_PRIVATE).getBoolean("intro_shown_v3", false)) showFirstLaunchGuide();
        else ensureInitialPermissions();
    }

    private void registerThermalListener() {
        if (Build.VERSION.SDK_INT < 29 || thermalListenerRegistered) return;
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) return;
        try {
            int currentStatus = powerManager.getCurrentThermalStatus();
            powerManager.addThermalStatusListener(getMainExecutor(), thermalListener);
            thermalListenerRegistered = true;
            handleThermalStatus(currentStatus);
        } catch (Throwable ignored) { }
    }

    private void unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < 29 || powerManager == null || !thermalListenerRegistered) return;
        try { powerManager.removeThermalStatusListener(thermalListener); } catch (Throwable ignored) { }
        thermalListenerRegistered = false;
        overlay.setThermalText("");
    }

    private boolean thermalAllowsRecording() {
        if (Build.VERSION.SDK_INT < 29) return true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return true;
        try { return pm.getCurrentThermalStatus() < PowerManager.THERMAL_STATUS_EMERGENCY; }
        catch (Throwable ignored) { return true; }
    }

    private void handleThermalStatus(int status) {
        runOnUiThread(() -> {
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) overlay.setThermalText("端末温度: 高");
            else if (status >= PowerManager.THERMAL_STATUS_MODERATE) overlay.setThermalText("端末温度: やや高");
            else overlay.setThermalText("");

            if (recording && status >= PowerManager.THERMAL_STATUS_EMERGENCY) {
                Toast.makeText(this, "端末温度が危険域のため録画を停止しました", Toast.LENGTH_LONG).show();
                stopRecording();
            }
        });
    }

    private void hideSystemUi() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new CameraGLRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new CameraOverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER);
        topBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        topBar.setBackgroundColor(0x55000000);
        orientationButton = chip(portrait ? "縦 9:16" : "横 16:9");
        stabButton = chip("水平 45°");
        fpsButton = chip("30 FPS");
        lutButton = chip("Natural");
        moreButton = chip("設定");
        topBar.addView(orientationButton, weight());
        topBar.addView(stabButton, weight());
        topBar.addView(fpsButton, weight());
        topBar.addView(lutButton, weight());
        topBar.addView(moreButton, weight());
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, uiTopBarHeight(), Gravity.TOP);
        root.addView(topBar, tp);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(10f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setPadding(dp(4), 0, dp(4), 0);
        statusText.setBackgroundColor(0x55000000);
        statusText.setText("準備中…");
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, uiStatusHeight(), Gravity.TOP);
        sp.topMargin = uiTopBarHeight();
        root.addView(statusText, sp);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(6), dp(4), dp(6), dp(6));
        bottomBar.setBackgroundColor(0x66000000);
        switchCameraButton = chip("反転");
        torchButton = chip("ライトOFF");
        recordButton = new Button(this);
        recordButton.setText("●");
        recordButton.setTextSize(26f);
        recordButton.setMinWidth(0);
        recordButton.setMinimumWidth(0);
        recordButton.setMinHeight(0);
        recordButton.setMinimumHeight(0);
        recordButton.setPadding(0, 0, 0, 0);
        recordButton.setTextColor(0xFFFF3B30);
        recordButton.setBackground(roundBg(0xEEFFFFFF, dp(32)));
        recordButton.setEnabled(false);
        galleryButton = chip("再生");
        bottomBar.addView(switchCameraButton, weight());
        bottomBar.addView(torchButton, weight());
        int recordSize = uiRecordButtonSize();
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(recordSize, recordSize);
        rp.setMargins(dp(7), 0, dp(7), 0);
        bottomBar.addView(recordButton, rp);
        bottomBar.addView(galleryButton, weight());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, uiBottomBarHeight(), Gravity.BOTTOM);
        root.addView(bottomBar, bp);

        setContentView(root);
        applyCompactUiSizing();

        orientationButton.setOnClickListener(v -> chooseOrientation());
        stabButton.setOnClickListener(v -> chooseStabilization());
        fpsButton.setOnClickListener(v -> chooseFps());
        lutButton.setOnClickListener(v -> chooseLut());
        moreButton.setOnClickListener(v -> showSettings());
        recordButton.setOnClickListener(v -> {
            if (stoppingRecording) return;
            if (recording) stopRecording(); else startRecording();
        });
        switchCameraButton.setOnClickListener(v -> {
            if (!recording && !stoppingRecording) {
                recordButton.setEnabled(false);
                cameraController.switchCamera();
            }
        });
        torchButton.setOnClickListener(v -> {
            if (stoppingRecording || (recording && uiLockDuringRecording)) return;
            if (!cameraController.supportsTorch()) {
                Toast.makeText(this, "このカメラではライトを使用できません", Toast.LENGTH_SHORT).show();
                return;
            }
            cameraController.setTorch(!cameraController.isTorch());
            torchButton.setText(cameraController.isTorch() ? "ライトON" : "ライトOFF");
            torchButton.setAlpha(cameraController.supportsTorch() ? 1f : 0.45f);
        });
        galleryButton.setOnClickListener(v -> {
            if (recording || stoppingRecording) {
                Toast.makeText(this, "録画停止後に再生画面を開いてください", Toast.LENGTH_SHORT).show();
                return;
            }
            openGallery();
        });
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(1), 0, dp(1), 0);
        return p;
    }

    private Button chip(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setBackground(roundBg(0x88444444, dp(12)));
        return b;
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // カメラ画面はプレビュー領域を優先。横画面ではさらに薄くする。
    private boolean landscapeUi() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    // 360dp幅以下の端末では、小型スマホ用に操作バーをさらに圧縮する。
    // 撮影方向ボタンは設定へ移し、プレビュー幅を確保する。
    private boolean smallScreenUi() {
        android.content.res.Configuration c = getResources().getConfiguration();
        int shortDp = Math.min(c.screenWidthDp, c.screenHeightDp);
        int longDp = Math.max(c.screenWidthDp, c.screenHeightDp);
        return shortDp <= 360 || longDp <= 640;
    }

    private int uiTopBarHeight() {
        // 小型端末では誤タップを避けるため上段のタップ高さを48dp確保する。
        if (smallScreenUi()) return dp(48);
        return dp(landscapeUi() ? 42 : 48);
    }
    private int uiStatusHeight() {
        // 小型端末の横画面は高さが厳しいので情報バーを省略する。
        if (smallScreenUi()) return dp(landscapeUi() ? 0 : 18);
        return dp(landscapeUi() ? 18 : 22);
    }
    private int uiBottomBarHeight() {
        if (smallScreenUi()) return dp(landscapeUi() ? 58 : 64);
        return dp(landscapeUi() ? 62 : 72);
    }
    private int uiRecordButtonSize() {
        if (smallScreenUi()) return dp(landscapeUi() ? 48 : 52);
        return dp(landscapeUi() ? 50 : 58);
    }

    private void applyCompactUiSizing() {
        boolean small = smallScreenUi();
        if (orientationButton != null) {
            // 小型端末では上段を4ボタンにして横幅を確保。方向変更は「設定」から行える。
            orientationButton.setVisibility(small ? View.GONE : View.VISIBLE);
        }
        if (topBar != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) topBar.getLayoutParams();
            if (p != null) { p.height = uiTopBarHeight(); topBar.setLayoutParams(p); }
            int vPad = small ? 0 : (landscapeUi() ? 2 : 4);
            topBar.setPadding(dp(small ? 3 : 4), dp(vPad), dp(small ? 3 : 4), dp(vPad));
        }
        if (statusText != null) {
            statusText.setVisibility(small && landscapeUi() ? View.GONE : View.VISIBLE);
            statusText.setTextSize(small ? 9f : 10f);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) statusText.getLayoutParams();
            if (p != null) {
                p.height = uiStatusHeight();
                p.topMargin = uiTopBarHeight();
                statusText.setLayoutParams(p);
            }
        }
        Button[] chips = { orientationButton, stabButton, fpsButton, lutButton, moreButton, switchCameraButton, torchButton, galleryButton };
        for (Button b : chips) if (b != null) b.setTextSize(small ? 9f : 10f);
        if (bottomBar != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            if (p != null) { p.height = uiBottomBarHeight(); bottomBar.setLayoutParams(p); }
            bottomBar.setPadding(dp(small ? 4 : 6), dp(landscapeUi() ? 2 : (small ? 3 : 4)),
                    dp(small ? 4 : 6), dp(landscapeUi() ? 2 : (small ? 4 : 6)));
        }
        if (recordButton != null) {
            int size = uiRecordButtonSize();
            recordButton.setTextSize(small ? 23f : 26f);
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) recordButton.getLayoutParams();
            if (p != null) {
                p.width = size;
                p.height = size;
                p.setMargins(dp(small ? 5 : 7), 0, dp(small ? 5 : 7), 0);
                recordButton.setLayoutParams(p);
            }
        }
        if (overlay != null) {
            overlay.setUiInsets(uiTopBarHeight() + uiStatusHeight(), uiBottomBarHeight(), small);
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyCompactUiSizing();
        hideSystemUi();
    }

    private void showFirstLaunchGuide() {
        String nearby = Build.VERSION.SDK_INT >= 31 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                ? "\n・付近のデバイス：Bluetoothマイク利用時" : "";
        new AlertDialog.Builder(this)
                .setTitle("水平維持カメラへようこそ")
                .setMessage("動画専用の水平維持カメラです。\n\n" +
                        "・45° / 360°水平維持\n" +
                        "・対応端末のみ30 / 60fps\n" +
                        "・4K / 1080p / 720p、H.264 / HEVC\n" +
                        "・3D .cube LUT、端末手ブレ補正、AF/AE/WB、ISO/シャッター\n" +
                        "・外部マイク、音量メーター、デジタル音量補正、低域カット\n\n" +
                        "初回に必要な権限をまとめて確認します。\n" +
                        "・カメラ：プレビューと動画撮影\n" +
                        "・マイク：動画の音声録音" + nearby + "\n\n" +
                        "録画した動画はMovies/水平維持カメラへ保存します。Android 10以降ではストレージ全体の権限は不要です。")
                .setCancelable(false)
                .setPositiveButton("権限を設定して開始", (d, w) -> {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("intro_shown_v3", true).apply();
                    ensureInitialPermissions();
                })
                .show();
    }

    private void ensureInitialPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        else startCameraIfReady();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        boolean cameraOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean btOk = Build.VERSION.SDK_INT < 31
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (!cameraOk) {
            new AlertDialog.Builder(this).setTitle("カメラ権限が必要です")
                    .setMessage("撮影するにはカメラ権限が必要です。設定から許可してください。")
                    .setPositiveButton("設定を開く", (d, w) -> openAppSettings())
                    .setNegativeButton("閉じる", null).show();
            return;
        }
        if (!micOk) {
            audioEnabled = false;
            micLabel = "音声OFF";
            Toast.makeText(this, "マイク権限がないため音声OFFで開始します", Toast.LENGTH_LONG).show();
        }
        if (!btOk) Toast.makeText(this, "Bluetooth権限がないためBluetoothマイクは使用しません", Toast.LENGTH_LONG).show();
        startCameraIfReady();
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    @Override public void onCameraInputReady(android.graphics.SurfaceTexture texture) {
        final android.graphics.SurfaceTexture previous = cameraInputTexture;
        cameraInputTexture = texture;
        runOnUiThread(() -> {
            if (previous != null && previous != texture) {
                // EGL context/surface recreation invalidates the old SurfaceTexture. Rebind Camera2.
                recordButton.setEnabled(false);
                if (recording && !stoppingRecording) stopRecording();
                cameraController.close();
            }
            if (!defaultLutLoaded) {
                defaultLutLoaded = true;
                loadPresetLut("Natural");
            }
            startCameraIfReady();
        });
    }

    @Override public void onRenderError(String message) {
        runOnUiThread(() -> {
            recordingFailureMessage = message;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (recording && !stoppingRecording) stopRecording();
        });
    }

    @Override public void onCropRatio(float ratio) {
        overlay.setCropRatio(ratio);
    }

    @Override public void onMeasuredFps(float measuredFps) {
        if (!recording || selectedFps < 60) {
            lowFpsWindows = 0;
            return;
        }
        if (measuredFps < selectedFps * 0.85f) lowFpsWindows++;
        else lowFpsWindows = 0;
        if (lowFpsWindows >= 2 && !frameDropWarningShown) {
            frameDropWarningShown = true;
            Toast.makeText(this, "端末負荷により実処理FPSが低下しています。解像度やLUTを下げると安定します", Toast.LENGTH_LONG).show();
        }
    }

    private void startCameraIfReady() {
        if (!activityResumed) return;
        if (cameraInputTexture == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!getPreferences(MODE_PRIVATE).getBoolean("orientation_chosen", false)) {
            showInitialOrientationChoice();
            return;
        }
        cameraController.setPortrait(portrait);
        cameraController.open(cameraInputTexture);
    }

    private void showInitialOrientationChoice() {
        if (initialOrientationDialogShowing) return;
        initialOrientationDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("最初に撮影方向を選択")
                .setMessage("撮影中はこの縦/横を固定したまま水平維持を行います。")
                .setCancelable(false)
                .setPositiveButton("縦 9:16", (d, w) -> {
                    initialOrientationDialogShowing = false;
                    portrait = true;
                    orientationButton.setText("縦 9:16");
                    getPreferences(MODE_PRIVATE).edit().putBoolean("orientation_chosen", true).putBoolean("portrait_mode", true).apply();
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    resetHorizonReference();
                    startCameraIfReady();
                })
                .setNegativeButton("横 16:9", (d, w) -> {
                    initialOrientationDialogShowing = false;
                    portrait = false;
                    orientationButton.setText("横 16:9");
                    getPreferences(MODE_PRIVATE).edit().putBoolean("orientation_chosen", true).putBoolean("portrait_mode", false).apply();
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    resetHorizonReference();
                    startCameraIfReady();
                })
                .show();
    }

    @Override public void onCapabilities(List<Integer> fpsValues, Size captureSize, int baseRotation, boolean mirror) {
        runOnUiThread(() -> {
            supportedFps = new ArrayList<>(fpsValues);
            int activeFps = cameraController.getFps();
            if (supportedFps.contains(activeFps)) selectedFps = activeFps;
            else if (!supportedFps.contains(selectedFps)) selectedFps = supportedFps.isEmpty() ? 30 : supportedFps.get(0);
            fpsButton.setText(selectedFps + " FPS");
            torchButton.setText(cameraController.isTorch() ? "ライトON" : "ライトOFF");
            torchButton.setAlpha(cameraController.supportsTorch() ? 1f : 0.45f);
            switchCameraButton.setAlpha(cameraController.canSwitchCamera() ? 1f : 0.45f);
            int outW = portrait ? captureSize.getHeight() : captureSize.getWidth();
            int outH = portrait ? captureSize.getWidth() : captureSize.getHeight();
            renderer.setOutputSize(outW, outH);
            cameraMirror = mirror;
            renderer.setCameraGeometry(baseRotation, mirror);
            renderer.setMaxCropRatio(maxCropRatio);
            recalculate45CropAngle();
            if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime) && !cameraController.supportsHevcForCurrentMode()) {
                videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
                Toast.makeText(this, "現在の解像度/FPSではHEVC非対応のためH.264に戻しました", Toast.LENGTH_SHORT).show();
            }
            setRecordingUiState(recording);
            refreshStatus();
        });
    }

    @Override public void onPreviewReady() {
        runOnUiThread(() -> {
            if (activityResumed && !recording && !stoppingRecording) recordButton.setEnabled(true);
        });
    }

    @Override public void onCameraError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override public void onCameraFatalError(String message) {
        runOnUiThread(() -> {
            recordButton.setEnabled(false);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (recording && !stoppingRecording) stopRecording();
        });
    }

    @Override public void onAfLockResult(boolean focused) {
        runOnUiThread(() -> Toast.makeText(this,
                focused ? "AFをロックしました" : "AFを確定できませんでした",
                Toast.LENGTH_SHORT).show());
    }

    @Override public void onHardwareStabilizationState(boolean active) {
        runOnUiThread(this::refreshStatus);
    }

    private void chooseOrientation() {
        if (recording || stoppingRecording) return;
        String[] items = {"縦動画 9:16", "横動画 16:9"};
        new AlertDialog.Builder(this).setTitle("撮影方向").setSingleChoiceItems(items, portrait ? 0 : 1, (d, which) -> {
            boolean requestedPortrait = which == 0;
            boolean previousPortrait = portrait;
            // CameraController may notify capabilities synchronously. Expose the requested
            // orientation to that callback, then roll back both sides if the mode is unsupported.
            portrait = requestedPortrait;
            if (!cameraController.setPortrait(requestedPortrait)) {
                portrait = previousPortrait;
                d.dismiss();
                return;
            }
            orientationButton.setText(portrait ? "縦 9:16" : "横 16:9");
            getPreferences(MODE_PRIVATE).edit().putBoolean("portrait_mode", portrait).apply();
            resetHorizonReference();
            setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            // If the previous camera open failed for the old orientation, changing orientation
            // must also retry opening instead of leaving the preview permanently closed.
            glView.postDelayed(this::startCameraIfReady, 120L);
            d.dismiss();
        }).show();
    }

    private void chooseStabilization() {
        if (recording && uiLockDuringRecording) return;
        String[] items = {
                "45°水平維持（クロップ少なめ）",
                "360°水平維持（クロップ大きめ）",
                "水平補正OFF"
        };
        int checked = stabilizationMode == STAB_45 ? 0 : stabilizationMode == STAB_360 ? 1 : 2;
        new AlertDialog.Builder(this).setTitle("水平維持モード").setSingleChoiceItems(items, checked, (d, which) -> {
            stabilizationMode = which == 0 ? STAB_45 : which == 1 ? STAB_360 : STAB_OFF;
            if (stabilizationMode == STAB_360 && maxCropRatio < 2f) {
                maxCropRatio = 2.20f;
                renderer.setMaxCropRatio(maxCropRatio);
                Toast.makeText(this, "360°水平維持では全周補正に必要なため、クロップ上限を「水平優先」にしました", Toast.LENGTH_LONG).show();
            }
            stabButton.setText(stabilizationMode == STAB_45 ? "水平 45°" : stabilizationMode == STAB_360 ? "水平 360°" : "水平 OFF");
            applyStabilizationTuning();
            updateHorizonSensorRegistration();
            resetHorizonReference();
            d.dismiss();
        }).show();
    }

    private void chooseFps() {
        if (recording || supportedFps.isEmpty()) return;
        String[] labels = new String[supportedFps.size()];
        int checked = 0;
        for (int i = 0; i < supportedFps.size(); i++) {
            labels[i] = supportedFps.get(i) + " FPS";
            if (supportedFps.get(i) == selectedFps) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("フレームレート（対応値のみ）")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    selectedFps = supportedFps.get(which);
                    fpsButton.setText(selectedFps + " FPS");
                    cameraController.setFps(selectedFps);
                    d.dismiss();
                }).show();
    }

    private void chooseLut() {
        if (recording && uiLockDuringRecording) return;
        String[] items = {"Natural", "Warm", "Cool", "Cinema", "Mono", "3D .cube LUTを読み込む", "LUTなし"};
        new AlertDialog.Builder(this).setTitle("LUT / カラー").setItems(items, (d, which) -> {
            if (which <= 4) loadPresetLut(items[which]);
            else if (which == 5) {
                if (recording) {
                    Toast.makeText(this, "外部LUTの読み込みは録画停止後に行ってください", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, REQ_LUT);
            } else {
                renderer.disableLut();
                lutLabel = "なし";
                lutButton.setText("LUTなし");
                refreshStatus();
            }
        }).show();
    }

    private void loadPresetLut(String name) {
        String file = name.toLowerCase(Locale.US) + ".cube";
        try (InputStream in = getAssets().open("luts/" + file)) {
            renderer.loadLut(in);
            lutLabel = name;
            lutButton.setText(name);
            refreshStatus();
        } catch (Exception e) {
            Toast.makeText(this, "LUT読込失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LUT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
            Toast.makeText(this, "LUTを読み込んでいます…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("ファイルを開けません");
                    CubeLut parsed = CubeLut.parse(in);
                    runOnUiThread(() -> {
                        renderer.applyLut(parsed);
                        lutLabel = "外部LUT";
                        lutButton.setText("外部LUT");
                        refreshStatus();
                        Toast.makeText(this, "外部LUTを読み込みました", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "3D .cube LUTを読み込めません: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "LoadCubeLut").start();
        }
    }

    private void showSettings() {
        if (recording && uiLockDuringRecording) return;
        String[] items = {
                "撮影方向: " + (portrait ? "縦 9:16" : "横 16:9"),
                "画質・録画",
                "水平維持・手ブレ補正",
                "フォーカス・露出・ホワイトバランス",
                "ズーム",
                "音声・マイク",
                "表示・操作",
                "水平基準を取り直す",
                "保存先: Movies/水平維持カメラ"
        };
        new AlertDialog.Builder(this).setTitle("撮影設定").setItems(items, (d, which) -> {
            if (which == 0) chooseOrientation();
            else if (which == 1) showQualitySettings();
            else if (which == 2) showStabilizationSettings();
            else if (which == 3) showProCameraSettings();
            else if (which == 4) chooseZoom();
            else if (which == 5) showAudioSettings();
            else if (which == 6) showDisplaySettings();
            else if (which == 7) { resetHorizonReference(); Toast.makeText(this, "水平基準を取り直しました", Toast.LENGTH_SHORT).show(); }
            else if (which == 8) Toast.makeText(this, "保存先は Movies/水平維持カメラ です", Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void showQualitySettings() {
        if (recording || stoppingRecording) {
            Toast.makeText(this, "解像度・ビットレート・コーデックは録画中に変更できません", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = {
                "解像度: " + resolutionLabel(cameraController.getCaptureSize()),
                "ビットレート: " + bitrateLabel(),
                "コーデック: " + (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime) ? "HEVC / H.265" : "H.264")
        };
        new AlertDialog.Builder(this).setTitle("画質・録画").setItems(items, (d, which) -> {
            if (which == 0) chooseResolution();
            else if (which == 1) chooseBitrate();
            else chooseCodec();
        }).show();
    }

    private void chooseResolution() {
        List<Integer> heights = cameraController.getSupportedResolutionHeights();
        String[] labels = new String[heights.size()];
        int checked = 0;
        for (int i = 0; i < heights.size(); i++) {
            int h = heights.get(i);
            labels[i] = h >= 2000 ? "4K / 2160p" : h >= 1000 ? "Full HD / 1080p" : h >= 700 ? "HD / 720p" : h + "p";
            if (Math.abs(cameraController.getPreferredHeight() - h) < 100) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("解像度（端末対応のみ）").setSingleChoiceItems(labels, checked, (d, which) -> {
            cameraController.setResolutionHeight(heights.get(which));
            d.dismiss();
        }).show();
    }

    private void chooseBitrate() {
        String[] labels = {"容量優先", "標準", "高画質", "最高画質"};
        new AlertDialog.Builder(this).setTitle("ビットレート").setSingleChoiceItems(labels, bitrateQuality, (d, which) -> {
            bitrateQuality = which;
            d.dismiss();
            refreshStatus();
        }).show();
    }

    private String bitrateLabel() {
        return new String[]{"容量優先", "標準", "高画質", "最高画質"}[Math.max(0, Math.min(3, bitrateQuality))];
    }

    private void chooseCodec() {
        boolean hevc = cameraController.supportsHevcForCurrentMode();
        String[] items = hevc ? new String[]{"H.264（互換性重視）", "HEVC / H.265（容量・画質重視）"} : new String[]{"H.264（この撮影モードで利用可能）"};
        int checked = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime) && hevc ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("動画コーデック").setSingleChoiceItems(items, checked, (d, which) -> {
            videoMime = which == 1 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            d.dismiss();
            refreshStatus();
        }).show();
    }

    private void showStabilizationSettings() {
        String[] items = {
                "補正の動き: " + smoothingLabel(),
                "クロップ上限: " + cropLimitLabel(),
                "端末手ブレ補正: " + hardwareStabilizationLabel(),
                "水平基準: " + (referenceAtRecordStart ? "録画開始時" : "絶対水平")
        };
        new AlertDialog.Builder(this).setTitle("水平維持・手ブレ補正").setItems(items, (d, which) -> {
            if (which == 0) chooseSmoothing();
            else if (which == 1) chooseCropLimit();
            else if (which == 2) toggleHardwareStabilization();
            else chooseReferenceMode();
        }).show();
    }

    private void chooseSmoothing() {
        String[] items = {"クイック", "標準", "なめらか"};
        new AlertDialog.Builder(this).setTitle("水平補正の追従").setSingleChoiceItems(items, smoothingMode, (d, which) -> {
            smoothingMode = which;
            smoothingReady = false;
            d.dismiss();
        }).show();
    }

    private String smoothingLabel() {
        return smoothingMode == SMOOTH_QUICK ? "クイック" : smoothingMode == SMOOTH_SMOOTH ? "なめらか" : "標準";
    }

    private void chooseCropLimit() {
        if (stabilizationMode == STAB_360) {
            Toast.makeText(this, "360°水平維持では全周補正に必要なため、クロップ上限は「水平優先」固定です", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = {"最大 1.10×（画角優先）", "最大 1.30×（バランス）", "水平維持優先（最大 2.20×）"};
        int checked = maxCropRatio < 1.2f ? 0 : maxCropRatio < 2f ? 1 : 2;
        new AlertDialog.Builder(this).setTitle("クロップ上限").setSingleChoiceItems(items, checked, (d, which) -> {
            maxCropRatio = which == 0 ? 1.10f : which == 1 ? 1.30f : 2.20f;
            renderer.setMaxCropRatio(maxCropRatio);
            recalculate45CropAngle();
            smoothingReady = false;
            d.dismiss();
        }).show();
    }

    private String cropLimitLabel() {
        return maxCropRatio >= 2f ? "水平優先" : String.format(Locale.US, "%.2f×", maxCropRatio);
    }


    private String hardwareStabilizationLabel() {
        if (!cameraController.supportsHardwareStabilization()) return "非対応";
        if (!hardwareStabilizationEnabled) return "OFF";
        return cameraController.isHardwareStabilizationActive() ? "ON（有効）" : "ON（端末確認中/無効）";
    }

    private void toggleHardwareStabilization() {
        if (!cameraController.supportsHardwareStabilization()) {
            Toast.makeText(this, "このカメラでは端末手ブレ補正を利用できません", Toast.LENGTH_SHORT).show();
            return;
        }
        hardwareStabilizationEnabled = !hardwareStabilizationEnabled;
        applyStabilizationTuning();
        Toast.makeText(this, "端末手ブレ補正を" + (hardwareStabilizationEnabled ? "ON" : "OFF") + "にしました", Toast.LENGTH_SHORT).show();
    }

    private void applyStabilizationTuning() {
        recalculate45CropAngle();
        cameraController.setHardwareStabilization(hardwareStabilizationEnabled);
        if (stabilizationMode == STAB_OFF) {
            smoothedApplied = 0f;
            smoothingReady = false;
            renderer.setStabilizationAngle(0f);
        }
        // Extra crop belongs to horizon rotation only. Device stabilization controls its own FoV.
        renderer.setCropBoost(stabilizationMode == STAB_360 ? 1.04f : 1.0f);
    }

    private void chooseReferenceMode() {
        String[] items = {"絶対水平（重力基準）", "録画開始時の傾きを基準"};
        new AlertDialog.Builder(this).setTitle("水平基準").setSingleChoiceItems(items, referenceAtRecordStart ? 1 : 0, (d, which) -> {
            referenceAtRecordStart = which == 1;
            resetHorizonReference();
            d.dismiss();
        }).show();
    }

    private void showProCameraSettings() {
        String[] items = {
                "AF LOCK: " + (cameraController.supportsAfLock() ? onOff(cameraController.isAfLock()) : "非対応"),
                "AE LOCK: " + autoLockLabel(cameraController.supportsAeLock(), cameraController.isAeLock(), cameraController.isAeLockActive()),
                "WB LOCK: " + autoLockLabel(cameraController.supportsAwbLock(), cameraController.isAwbLock(), cameraController.isAwbLockActive()),
                "露出補正",
                "ISO / シャッター: " + (cameraController.supportsManualExposure() ? (cameraController.isManualExposure() ? manualExposureLabel() : "AUTO") : "非対応"),
                "ホワイトバランス"
        };
        new AlertDialog.Builder(this).setTitle("フォーカス・露出・WB")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (!cameraController.supportsAfLock()) Toast.makeText(this, "このカメラはAF LOCK非対応です", Toast.LENGTH_SHORT).show();
                        else cameraController.setAfLock(!cameraController.isAfLock());
                    } else if (which == 1) {
                        if (!cameraController.supportsAeLock()) Toast.makeText(this, "このカメラはAE LOCK非対応です", Toast.LENGTH_SHORT).show();
                        else cameraController.setAeLock(!cameraController.isAeLock());
                    } else if (which == 2) {
                        if (!cameraController.supportsAwbLock()) Toast.makeText(this, "このカメラはWB LOCK非対応です", Toast.LENGTH_SHORT).show();
                        else cameraController.setAwbLock(!cameraController.isAwbLock());
                    } else if (which == 3) showExposureDialog();
                    else if (which == 4) {
                        if (!cameraController.supportsManualExposure()) Toast.makeText(this, "このカメラはマニュアル露出非対応です", Toast.LENGTH_SHORT).show();
                        else chooseManualExposure();
                    } else chooseWhiteBalance();
                }).show();
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private static String autoLockLabel(boolean supported, boolean requested, boolean active) {
        if (!supported) return "非対応";
        if (!requested) return "OFF";
        return active ? "ON（有効）" : "ON（端末確認中/無効）";
    }

    private void showExposureDialog() {
        Range<Integer> range = cameraController.getExposureRange();
        int min = range.getLower(), max = range.getUpper();
        if (max <= min) {
            Toast.makeText(this, "この端末では露出補正を変更できません", Toast.LENGTH_SHORT).show();
            return;
        }
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(-min);
        int pad = dp(smallScreenUi() ? 12 : 24);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad, pad, pad);
        box.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("露出補正").setView(box)
                .setPositiveButton("適用", (d, w) -> cameraController.setExposureCompensation(min + seek.getProgress()))
                .setNegativeButton("キャンセル", null).show();
    }

    private void chooseManualExposure() {
        int f = Math.max(1, selectedFps);
        int[] denominators = {f, f * 2, f * 4, f * 8};
        String[] items = {
                "AUTO",
                "1/" + denominators[0] + " 秒",
                "1/" + denominators[1] + " 秒（180°シャッター目安）",
                "1/" + denominators[2] + " 秒",
                "1/" + denominators[3] + " 秒"
        };
        new AlertDialog.Builder(this).setTitle("ISO / シャッター").setItems(items, (d, which) -> {
            if (which == 0) {
                cameraController.setManualExposure(false, cameraController.getManualIso(), cameraController.getManualExposureNs());
            } else {
                long ns = 1_000_000_000L / denominators[which - 1];
                chooseIso(ns);
            }
        }).show();
    }

    private void chooseIso(long exposureNs) {
        Range<Integer> range = cameraController.getIsoRange();
        int min = range.getLower(), max = range.getUpper();
        if (max <= min) {
            Toast.makeText(this, "この端末ではマニュアルISOを利用できません", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(smallScreenUi() ? 12 : 24);
        box.setPadding(pad, pad, pad, pad);
        TextView value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setTextSize(smallScreenUi() ? 14f : 16f);
        SeekBar seek = new SeekBar(this);
        seek.setMax(1000);
        int initial = (int) ((cameraController.getManualIso() - min) * 1000f / Math.max(1, max - min));
        seek.setProgress(Math.max(0, Math.min(1000, initial)));
        Runnable update = () -> {
            int iso = min + Math.round((max - min) * seek.getProgress() / 1000f);
            value.setText("ISO " + iso);
        };
        update.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { update.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        box.addView(value, new LinearLayout.LayoutParams(-1, -2));
        box.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("ISOを設定").setView(box)
                .setPositiveButton("適用", (d, w) -> {
                    int iso = min + Math.round((max - min) * seek.getProgress() / 1000f);
                    cameraController.setManualExposure(true, iso, exposureNs);
                })
                .setNegativeButton("キャンセル", null).show();
    }

    private String manualExposureLabel() {
        long ns = cameraController.getManualExposureNs();
        long denom = ns <= 0 ? 0 : Math.round(1_000_000_000.0 / ns);
        return "ISO " + cameraController.getManualIso() + " / 1/" + denom;
    }

    private void chooseWhiteBalance() {
        int[] candidates = {
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
                CaptureRequest.CONTROL_AWB_MODE_SHADE
        };
        String[] names = {"AUTO", "晴天", "曇天", "電球", "蛍光灯", "日陰"};
        List<Integer> supported = cameraController.getSupportedAwbModes();
        List<Integer> modes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < candidates.length; i++) {
            if (supported.contains(candidates[i])) { modes.add(candidates[i]); labels.add(names[i]); }
        }
        int checked = Math.max(0, modes.indexOf(cameraController.getAwbMode()));
        new AlertDialog.Builder(this).setTitle("ホワイトバランス").setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
            cameraController.setAwbMode(modes.get(which));
            d.dismiss();
        }).show();
    }

    private void chooseZoom() {
        Range<Float> range = cameraController.getZoomRange();
        List<Float> values = new ArrayList<>();
        if (range.getLower() <= 0.51f) values.add(0.5f);
        if (range.getLower() <= 1f && range.getUpper() >= 1f) values.add(1f);
        if (range.getUpper() >= 1.99f) values.add(2f);
        if (values.isEmpty()) values.add(range.getLower());
        String[] labels = new String[values.size()];
        int checked = 0;
        for (int i = 0; i < values.size(); i++) {
            labels[i] = String.format(Locale.US, "%.1f×", values.get(i));
            if (Math.abs(values.get(i) - cameraController.getZoomRatio()) < 0.15f) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("ズーム（端末対応範囲）").setSingleChoiceItems(labels, checked, (d, which) -> {
            cameraController.setZoomRatio(values.get(which));
            d.dismiss();
            refreshStatus();
        }).show();
    }

    private void showAudioSettings() {
        if (recording || stoppingRecording) {
            Toast.makeText(this, "マイク設定は次の録画から反映されるため、録画停止後に変更してください", Toast.LENGTH_SHORT).show();
            return;
        }
        String gain = audioAutoGain ? "AUTO (AGC)" : String.format(Locale.US, "%+.0f dB", audioGainDb);
        String lowCut = lowCutMode == AudioEncoder.LOW_CUT_220HZ ? "220 Hz" : lowCutMode == AudioEncoder.LOW_CUT_120HZ ? "120 Hz" : "OFF";
        String[] items = {
                "マイク: " + micLabel,
                "デジタル音量補正: " + gain,
                "低域カット: " + lowCut
        };
        new AlertDialog.Builder(this).setTitle("音声・マイク").setItems(items, (d, which) -> {
            if (which == 0) chooseMicrophone();
            else if (which == 1) chooseAudioGain();
            else chooseLowCut();
        }).show();
    }

    private void chooseMicrophone() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices;
        try { devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS); }
        catch (SecurityException e) { devices = new AudioDeviceInfo[0]; }
        List<AudioDeviceInfo> usable = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add("自動（Androidに任せる）");
        labels.add("音声OFF");
        for (AudioDeviceInfo info : devices) {
            if (!info.isSource()) continue;
            if (isBluetooth(info) && !hasBluetoothPermission()) continue;
            usable.add(info);
            labels.add(deviceTypeName(info.getType()) + " - " + info.getProductName());
        }
        new AlertDialog.Builder(this).setTitle("録音マイク").setItems(labels.toArray(new String[0]), (d, which) -> {
            if (which == 0) {
                audioEnabled = true;
                preferredAudioDeviceId = -1;
                micLabel = "自動マイク";
            } else if (which == 1) {
                audioEnabled = false;
                preferredAudioDeviceId = -1;
                micLabel = "音声OFF";
            } else {
                AudioDeviceInfo info = usable.get(which - 2);
                audioEnabled = true;
                preferredAudioDeviceId = info.getId();
                micLabel = String.valueOf(info.getProductName());
            }
            refreshStatus();
        }).show();
    }

    private void chooseAudioGain() {
        final boolean agc = AudioEncoder.isAutoGainAvailable();
        final String[] items = agc
                ? new String[]{"AUTO（端末AGC）", "-12 dB", "-6 dB", "0 dB", "+3 dB", "+6 dB"}
                : new String[]{"-12 dB", "-6 dB", "0 dB", "+3 dB", "+6 dB"};
        new AlertDialog.Builder(this).setTitle("デジタル音量補正")
                .setMessage("プラス補正は録音後のPCMを増幅します。入力段で既に音割れした音は直せません。")
                .setItems(items, (d, which) -> {
                    if (agc && which == 0) {
                        audioAutoGain = true;
                        return;
                    }
                    audioAutoGain = false;
                    int index = agc ? which - 1 : which;
                    audioGainDb = new float[]{-12f, -6f, 0f, 3f, 6f}[Math.max(0, Math.min(4, index))];
                }).show();
    }

    private void chooseLowCut() {
        String[] items = {"OFF", "120 Hz（軽め）", "220 Hz（強め）"};
        new AlertDialog.Builder(this).setTitle("低域カット").setMessage("風だけを判別する機能ではなく、低い周波数をカットします。")
                .setSingleChoiceItems(items, lowCutMode, (d, which) -> {
                    lowCutMode = which;
                    d.dismiss();
                }).show();
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USBヘッドセット";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有線";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth LE";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内蔵";
            default: return "入力";
        }
    }

    private static boolean isBluetooth(AudioDeviceInfo info) {
        int type = info.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private AudioDeviceInfo resolvePreferredAudioDevice() {
        if (preferredAudioDeviceId < 0) return null;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getId() == preferredAudioDeviceId && (!isBluetooth(d) || hasBluetoothPermission())) return d;
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    private void showDisplaySettings() {
        String[] items = {
                "グリッド: " + onOff(gridEnabled),
                "水準器: " + onOff(levelEnabled),
                "録画中UIロック: " + onOff(uiLockDuringRecording),
                "画面消灯防止: ON"
        };
        new AlertDialog.Builder(this).setTitle("表示・操作").setItems(items, (d, which) -> {
            if (which == 0) { gridEnabled = !gridEnabled; overlay.setGrid(gridEnabled); }
            else if (which == 1) {
                levelEnabled = !levelEnabled;
                overlay.setLevel(levelEnabled);
                updateHorizonSensorRegistration();
            }
            else if (which == 2) {
                uiLockDuringRecording = !uiLockDuringRecording;
                if (recording || stoppingRecording) setRecordingUiState(recording);
            }
            else Toast.makeText(this, "撮影画面では常に画面消灯を防止します", Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void resetHorizonReference() {
        referenceReady = false;
        smoothingReady = false;
    }

    @Override public void onRoll(float rollDegrees) {
        currentRoll = rollDegrees;
        hasRollSample = true;
        if (!referenceReady) {
            referenceRoll = referenceAtRecordStart && recording ? currentRoll : HorizonSensor.snapReference(currentRoll, portrait);
            referenceReady = true;
        }
        float delta = HorizonSensor.normalize180(currentRoll - referenceRoll);

        // When horizontal correction is OFF the sensor may still be active for the level UI.
        // Do not run smoothing/crop/GPU correction calculations in that case.
        if (stabilizationMode != STAB_OFF) {
            float desired = stabilizationMode == STAB_45
                    ? Math.max(-max45CorrectionAngle, Math.min(max45CorrectionAngle, delta)) : delta;

            float alpha = smoothingMode == SMOOTH_QUICK ? 0.42f : smoothingMode == SMOOTH_SMOOTH ? 0.08f : 0.18f;
            if (!smoothingReady) {
                smoothedApplied = desired;
                smoothingReady = true;
            } else {
                float diff = HorizonSensor.normalize180(desired - smoothedApplied);
                smoothedApplied = HorizonSensor.normalize180(smoothedApplied + diff * alpha);
            }
            renderer.setStabilizationAngle(cameraMirror ? smoothedApplied : -smoothedApplied);
        }

        if (levelEnabled) {
            long nowNs = System.nanoTime();
            if (nowNs - lastLevelUiNs >= 33_000_000L) {
                lastLevelUiNs = nowNs;
                float levelValue = delta;
                runOnUiThread(() -> overlay.setRoll(levelValue));
            }
        }
    }

    private void recalculate45CropAngle() {
        if (maxCropRatio >= 2f) {
            max45CorrectionAngle = 45f;
            return;
        }
        float low = 0f, high = 45f;
        for (int i = 0; i < 18; i++) {
            float mid = (low + high) * 0.5f;
            if (requiredCrop(mid) <= maxCropRatio) low = mid;
            else high = mid;
        }
        max45CorrectionAngle = Math.max(0f, Math.min(45f, low));
    }

    private float requiredCrop(float angle) {
        Size s = cameraController.getCaptureSize();
        float outW = portrait ? s.getHeight() : s.getWidth();
        float outH = portrait ? s.getWidth() : s.getHeight();
        float r = outW / Math.max(1f, outH);
        double t = Math.toRadians(angle);
        float c = Math.abs((float) Math.cos(t));
        float sin = Math.abs((float) Math.sin(t));
        float boost = stabilizationMode == STAB_360 ? 1.04f : 1.0f;
        return Math.max(c + sin / r, sin * r + c) * boost;
    }

    @Override public void onAudioLevel(float normalized, boolean clipping) {
        runOnUiThread(() -> overlay.setAudioLevel(normalized, clipping));
    }

    @Override public void onAudioRoute(AudioDeviceInfo requested, AudioDeviceInfo actual) {
        runOnUiThread(() -> {
            if (actual != null) {
                micLabel = String.valueOf(actual.getProductName());
                refreshStatus();
            }
            if (requested != null && (actual == null || requested.getId() != actual.getId())) {
                String actualName = actual == null ? "不明" : String.valueOf(actual.getProductName());
                Toast.makeText(this, "指定マイクへ切り替わらず、実際の入力は「" + actualName + "」です", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onAudioError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message + "。映像録画は継続します", Toast.LENGTH_LONG).show());
    }

    private void startRecording() {
        if (recording || stoppingRecording) return;
        if (!cameraController.isPreviewActive()) {
            Toast.makeText(this, "カメラの準備が完了していません", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ensureInitialPermissions();
            return;
        }
        if (audioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureInitialPermissions();
            return;
        }
        if (!thermalAllowsRecording()) {
            Toast.makeText(this, "端末温度が危険域のため、冷えてから録画してください", Toast.LENGTH_LONG).show();
            return;
        }
        lowFpsWindows = 0;
        frameDropWarningShown = false;
        recordingFailureMessage = null;
        if (referenceAtRecordStart) {
            // If recording is started before the first sensor sample arrives, do not lock the
            // reference to the default 0 degrees. The first real sample becomes the reference.
            if (hasRollSample) {
                referenceRoll = currentRoll;
                referenceReady = true;
            } else {
                referenceReady = false;
            }
            smoothingReady = false;
        }
        try {
            Size s = cameraController.getCaptureSize();
            int outW = portrait ? s.getHeight() : s.getWidth();
            int outH = portrait ? s.getWidth() : s.getHeight();
            if (!CodecUtil.supportsAvc(outW, outH, selectedFps)) {
                throw new IllegalStateException("この解像度/FPSをH.264でエンコードできません");
            }
            if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime) && !CodecUtil.supportsHevc(outW, outH, selectedFps)) {
                videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
            }
            int bitrate = calculateVideoBitrate(outW, outH, selectedFps);

            ContentValues v = new ContentValues();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            v.put(MediaStore.Video.Media.DISPLAY_NAME, "Horizontal_" + stamp + ".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/水平維持カメラ");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            activeVideoUri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (activeVideoUri == null) throw new IllegalStateException("保存先を作れません");
            activePfd = cr.openFileDescriptor(activeVideoUri, "rw");
            if (activePfd == null) throw new IllegalStateException("保存ファイルを開けません");

            boolean withAudio = audioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            muxer = new MuxerController(activePfd.getFileDescriptor(), withAudio ? 2 : 1);
            videoEncoder = new VideoEncoder(outW, outH, selectedFps, videoMime, bitrate, muxer);

            if (withAudio) {
                AudioDeviceInfo requestedMic = resolvePreferredAudioDevice();
                if (preferredAudioDeviceId >= 0 && requestedMic == null) {
                    Toast.makeText(this, "選択した外部マイクが見つからないため、自動入力で録音します", Toast.LENGTH_LONG).show();
                }
                audioEncoder = new AudioEncoder(muxer, requestedMic, audioAutoGain, audioGainDb, lowCutMode, this);
                try {
                    audioEncoder.start();
                } catch (Throwable audioError) {
                    // AudioEncoder.start() already releases partially-created resources on failure.
                    audioEncoder = null;
                    Throwable finalAudioError = audioError;
                    if (requestedMic != null) {
                        try {
                            AudioEncoder fallbackAudio = new AudioEncoder(muxer, null, audioAutoGain, audioGainDb, lowCutMode, this);
                            fallbackAudio.start();
                            audioEncoder = fallbackAudio;
                            Toast.makeText(this, "外部マイクを開始できないため、自動マイクへ切り替えました", Toast.LENGTH_LONG).show();
                        } catch (Throwable fallbackError) {
                            finalAudioError = fallbackError;
                        }
                    }
                    if (audioEncoder == null) {
                        withAudio = false;
                        muxer.setExpectedTracks(1);
                        Toast.makeText(this, "音声を開始できないため、今回は映像のみ録画します: " + finalAudioError.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }

            renderer.setOutputSize(outW, outH);
            renderer.startRecording(videoEncoder, selectedFps >= 60);
            recording = true;
            registerThermalListener();
            overlay.setRecording(true);
            overlay.setEstimatedRemainingSec(estimateRemainingSeconds(bitrate, withAudio));
            recordButton.setText("■");
            recordButton.setTextColor(Color.RED);
            setRecordingUiState(true);
            refreshStatus();
        } catch (Throwable e) {
            cleanupFailedRecording();
            Toast.makeText(this, "録画を開始できません: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int calculateVideoBitrate(int width, int height, int fps) {
        double[] bpp = {0.065, 0.105, 0.17, 0.26};
        double value = width * (double) height * Math.max(24, fps) * bpp[Math.max(0, Math.min(3, bitrateQuality))];
        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime)) value *= 0.65;
        int requested = (int) Math.max(3_000_000, Math.min(120_000_000, value));
        return CodecUtil.clampBitrate(videoMime, width, height, fps, requested);
    }

    private long estimateRemainingSeconds(int videoBitrate, boolean withAudio) {
        try {
            StatFs stat = new StatFs(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath());
            long bytes = stat.getAvailableBytes();
            long bitsPerSec = videoBitrate + (withAudio ? 160_000L : 0L);
            return (long) ((bytes * 8.0 / Math.max(1L, bitsPerSec)) * 0.90);
        } catch (Throwable ignored) { return -1L; }
    }

    private void setRecordingUiState(boolean isRecording) {
        if (stoppingRecording) {
            orientationButton.setEnabled(false);
            fpsButton.setEnabled(false);
            switchCameraButton.setEnabled(false);
            torchButton.setEnabled(false);
            galleryButton.setEnabled(false);
            stabButton.setEnabled(false);
            lutButton.setEnabled(false);
            moreButton.setEnabled(false);
            return;
        }
        boolean structuralEnabled = !isRecording;
        orientationButton.setEnabled(structuralEnabled);
        fpsButton.setEnabled(structuralEnabled && supportedFps.size() > 1);
        switchCameraButton.setEnabled(structuralEnabled && cameraController.canSwitchCamera());
        galleryButton.setEnabled(structuralEnabled);

        // Torch, LUT, stabilization and pro controls are safe live changes when UI lock is OFF.
        boolean liveEnabled = !isRecording || !uiLockDuringRecording;
        torchButton.setEnabled(liveEnabled && cameraController.supportsTorch());
        stabButton.setEnabled(liveEnabled);
        lutButton.setEnabled(liveEnabled);
        moreButton.setEnabled(liveEnabled);
        recordButton.setEnabled(isRecording || cameraController.isPreviewActive());
    }

    private void stopRecording() {
        stopRecordingInternal();
    }

    private CountDownLatch stopRecordingInternal() {
        if (stoppingRecording) return recordingStopLatch;
        if (!recording) return null;

        recording = false;
        stoppingRecording = true;
        unregisterThermalListener();
        if (referenceAtRecordStart) resetHorizonReference();
        overlay.setRecording(false);
        recordButton.setText("■");
        recordButton.setTextColor(0xFF888888);
        recordButton.setEnabled(false);
        setRecordingUiState(false);

        final AudioEncoder audioToStop = audioEncoder;
        final MuxerController muxerToStop = muxer;
        final Uri uriToFinalize = activeVideoUri;
        final ParcelFileDescriptor pfdToClose = activePfd;
        final String failureAtStop = recordingFailureMessage;

        audioEncoder = null;
        videoEncoder = null;
        muxer = null;
        activeVideoUri = null;
        activePfd = null;
        recordingFailureMessage = null;

        final CountDownLatch encodersDone = new CountDownLatch(audioToStop != null ? 2 : 1);
        final CountDownLatch allDone = new CountDownLatch(1);
        final AtomicReference<String> audioStopWarning = new AtomicReference<>();
        recordingStopLatch = allDone;

        if (audioToStop != null) {
            new Thread(() -> {
                try { audioStopWarning.set(audioToStop.stopAndRelease()); }
                finally { encodersDone.countDown(); }
            }, "StopAudioEncoder").start();
        }

        renderer.stopRecording(encodersDone::countDown);

        new Thread(() -> {
            String failure = failureAtStop;
            try {
                if (!encodersDone.await(6, TimeUnit.SECONDS) && failure == null) {
                    failure = "録画エンコーダの終了がタイムアウトしました";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (failure == null) failure = "録画終了処理が中断されました";
            }
            String audioWarning = audioStopWarning.get();
            if (failure == null && audioToStop != null && !audioToStop.wasStopClean()) {
                failure = "音声エンコーダを安全に終了できませんでした";
            }

            String videoStopError = renderer.getLastStopError();
            if (failure == null && videoStopError != null) failure = videoStopError;

            boolean hasVideo = false;
            if (muxerToStop != null) {
                hasVideo = muxerToStop.hasVideoSamples();
                try { muxerToStop.stop(); } catch (Throwable ignored) { }
                if (failure == null && muxerToStop.getFailureMessage() != null) failure = muxerToStop.getFailureMessage();
            }
            try { if (pfdToClose != null) pfdToClose.close(); } catch (Throwable ignored) { }

            final boolean saved = failure == null && hasVideo && uriToFinalize != null;
            if (uriToFinalize != null) {
                try {
                    if (saved) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Video.Media.IS_PENDING, 0);
                        getContentResolver().update(uriToFinalize, done, null, null);
                    } else {
                        getContentResolver().delete(uriToFinalize, null, null);
                    }
                } catch (Throwable storageError) {
                    failure = failure == null ? "保存処理に失敗しました: " + storageError.getMessage() : failure;
                    try { getContentResolver().delete(uriToFinalize, null, null); } catch (Throwable ignored) { }
                }
            }

            final String finalFailure = failure;
            final String finalAudioWarning = audioWarning;
            allDone.countDown();
            runOnUiThread(() -> {
                stoppingRecording = false;
                recordingStopLatch = null;
                recordButton.setEnabled(true);
                recordButton.setText("●");
                recordButton.setTextColor(0xFFFF3B30);
                setRecordingUiState(false);
                overlay.setEstimatedRemainingSec(-1L);
                if (saved && finalFailure == null) {
                    String message = finalAudioWarning == null
                            ? "Movies/水平維持カメラ に保存しました"
                            : "動画は保存しました。" + finalAudioWarning;
                    Toast.makeText(this, message, finalAudioWarning == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                } else {
                    String reason = finalFailure != null ? finalFailure : "有効な映像フレームを保存できませんでした";
                    Toast.makeText(this, "録画ファイルを保存しませんでした: " + reason, Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        }, "FinalizeRecording").start();

        return allDone;
    }

    private void cleanupFailedRecording() {
        try { if (audioEncoder != null) audioEncoder.stopAndRelease(); } catch (Throwable ignored) { }
        try { if (videoEncoder != null) videoEncoder.release(); } catch (Throwable ignored) { }
        try { if (muxer != null) muxer.stop(); } catch (Throwable ignored) { }
        try { if (activePfd != null) activePfd.close(); } catch (Throwable ignored) { }
        if (activeVideoUri != null) {
            try { getContentResolver().delete(activeVideoUri, null, null); } catch (Throwable ignored) { }
        }
        audioEncoder = null;
        videoEncoder = null;
        muxer = null;
        activePfd = null;
        activeVideoUri = null;
        recording = false;
        stoppingRecording = false;
        unregisterThermalListener();
        if (referenceAtRecordStart) resetHorizonReference();
        recordingStopLatch = null;
        recordButton.setEnabled(true);
        recordButton.setText("●");
        recordButton.setTextColor(0xFFFF3B30);
        overlay.setRecording(false);
        overlay.setEstimatedRemainingSec(-1L);
        setRecordingUiState(false);
    }

    private void refreshStatus() {
        Size s = cameraController.getCaptureSize();
        String codec = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime) ? "HEVC" : "H.264";
        String zoom = String.format(Locale.US, "%.1f×", cameraController.getZoomRatio());
        String mic = audioEnabled ? (preferredAudioDeviceId >= 0 ? "外部MIC" : "MIC") : "音声OFF";
        String stab = hardwareStabilizationEnabled && cameraController.supportsHardwareStabilization()
                ? (cameraController.isHardwareStabilizationActive() ? "  HW補正" : "") : "";
        String base = resolutionLabel(s) + "  " + selectedFps + "fps  " + zoom + "  " + codec + "  " + mic + stab;
        statusText.setText(recording ? "REC • " + base : stoppingRecording ? "保存中…  " + base : base);
    }

    private static String resolutionLabel(Size s) {
        int h = Math.min(s.getWidth(), s.getHeight());
        if (h >= 2000) return "4K";
        if (h >= 1000) return "1080p";
        if (h >= 700) return "720p";
        return s.getWidth() + "×" + s.getHeight();
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            i.setType("video/*");
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "動画アプリを開けません", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateHorizonSensorRegistration() {
        boolean needed = activityResumed && (levelEnabled || stabilizationMode != STAB_OFF);
        if (needed) horizonSensor.start(); else horizonSensor.stop();
    }

    private void pauseGlWhenRecordingFinalized(CountDownLatch stopLatch) {
        if (stopLatch == null) {
            if (!activityResumed && !glPaused) { glView.onPause(); glPaused = true; }
            return;
        }
        new Thread(() -> {
            try { stopLatch.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            runOnUiThread(() -> {
                if (!activityResumed && !glPaused) {
                    glView.onPause();
                    glPaused = true;
                }
            });
        }, "PauseGlAfterRecording").start();
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        hideSystemUi();
        if (glPaused) {
            glView.onResume();
            glPaused = false;
        }
        updateHorizonSensorRegistration();
        glView.postDelayed(this::startCameraIfReady, 120);
    }

    @Override protected void onPause() {
        activityResumed = false;
        updateHorizonSensorRegistration();
        CountDownLatch stopLatch = recording ? stopRecordingInternal() : recordingStopLatch;
        cameraController.stopThread();
        unregisterThermalListener();
        pauseGlWhenRecordingFinalized(stopLatch);
        super.onPause();
    }

    @Override protected void onDestroy() {
        unregisterThermalListener();
        super.onDestroy();
    }
}
