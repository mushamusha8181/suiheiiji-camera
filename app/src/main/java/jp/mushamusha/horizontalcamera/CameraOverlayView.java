package jp.mushamusha.horizontalcamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

public final class CameraOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean grid = true;
    private boolean level = true;
    private boolean recording = false;
    private float roll = 0f;
    private float cropRatio = 1f;
    private float audioLevel = 0f;
    private boolean audioClipping = false;
    private long recordingStartedAt = 0L;
    private long estimatedRemainingSec = -1L;
    private String thermalText = "";
    private float topUiInset = 0f;
    private float bottomUiInset = 0f;
    private boolean compactUi = false;
    private boolean recordingTickScheduled = false;
    private final Runnable recordingTicker = new Runnable() {
        @Override public void run() {
            recordingTickScheduled = false;
            if (!recording) return;
            invalidate();
            scheduleRecordingTick();
        }
    };

    public CameraOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public void setGrid(boolean value) { grid = value; invalidate(); }
    public void setLevel(boolean value) { level = value; invalidate(); }
    public void setRoll(float value) { roll = value; invalidate(); }
    public void setCropRatio(float value) { cropRatio = Math.max(1f, value); invalidate(); }
    public void setAudioLevel(float value, boolean clipping) { audioLevel = Math.max(0f, Math.min(1f, value)); audioClipping = clipping; invalidate(); }
    public void setEstimatedRemainingSec(long seconds) { estimatedRemainingSec = seconds; invalidate(); }
    public void setThermalText(String text) { thermalText = text == null ? "" : text; invalidate(); }
    public void setUiInsets(int topPx, int bottomPx, boolean compact) {
        topUiInset = Math.max(0, topPx);
        bottomUiInset = Math.max(0, bottomPx);
        compactUi = compact;
        invalidate();
    }
    public void setRecording(boolean value) {
        recording = value;
        recordingStartedAt = value ? SystemClock.elapsedRealtime() : 0L;
        if (!value) {
            audioLevel = 0f;
            audioClipping = false;
            removeCallbacks(recordingTicker);
            recordingTickScheduled = false;
        } else {
            scheduleRecordingTick();
        }
        invalidate();
    }

    private void scheduleRecordingTick() {
        if (!recording || recordingTickScheduled) return;
        recordingTickScheduled = true;
        postDelayed(recordingTicker, 500L);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x66FFFFFF);
        if (grid) {
            c.drawLine(w / 3f, 0, w / 3f, h, paint);
            c.drawLine(w * 2f / 3f, 0, w * 2f / 3f, h, paint);
            c.drawLine(0, h / 3f, w, h / 3f, paint);
            c.drawLine(0, h * 2f / 3f, w, h * 2f / 3f, paint);
        }
        if (level) {
            float cx = w / 2f, cy = h / 2f;
            c.save();
            c.rotate(-roll, cx, cy);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Math.abs(HorizonSensor.normalize180(roll)) < 1.0f ? 0xFF7CFF8B : 0xDDFFFFFF);
            float levelSpan = dp(compactUi ? 46 : 58);
            c.drawLine(cx - levelSpan, cy, cx - dp(12), cy, paint);
            c.drawLine(cx + dp(12), cy, cx + levelSpan, cy, paint);
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(compactUi ? 11 : 12));
            paint.setColor(0xEEFFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.format(java.util.Locale.US, "%.1f°", roll), cx, cy + dp(compactUi ? 24 : 28), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(0xDDFFFFFF);
        float infoTop = topUiInset + dp(compactUi ? 11 : 14);
        c.drawText(String.format(java.util.Locale.US, "×%.2f", cropRatio), w - dp(10), infoTop, paint);
        if (!thermalText.isEmpty()) {
            paint.setColor(0xFFFFC14D);
            c.drawText(thermalText.replace("端末温度: ", "温度 "), w - dp(10), infoTop + dp(compactUi ? 14 : 16), paint);
        }

        if (recording) {
            float recY = topUiInset + dp(compactUi ? 16 : 18);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFF3B30);
            c.drawCircle(dp(18), recY - dp(4), dp(compactUi ? 4 : 5), paint);
            long sec = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L;
            String t = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(dp(compactUi ? 12 : 14));
            paint.setColor(0xFFFFFFFF);
            c.drawText(t, dp(28), recY, paint);
            if (estimatedRemainingSec >= 0) {
                long remain = Math.max(0, estimatedRemainingSec - sec);
                paint.setTextSize(dp(compactUi ? 9 : 11));
                c.drawText("残り約 " + (remain / 60) + "分", dp(12), recY + dp(compactUi ? 14 : 18), paint);
            }

            float usableTop = topUiInset + dp(10);
            float usableBottom = h - bottomUiInset - dp(10);
            float requestedMeterH = dp(compactUi ? 68 : 90);
            float meterH = Math.min(requestedMeterH, Math.max(dp(40), usableBottom - usableTop));
            float meterX = w - dp(compactUi ? 14 : 18);
            float meterTop = (usableTop + usableBottom - meterH) / 2f;
            paint.setColor(0x44000000);
            c.drawRoundRect(new RectF(meterX - dp(6), meterTop, meterX + dp(6), meterTop + meterH), dp(4), dp(4), paint);
            float fill = meterH * audioLevel;
            paint.setColor(audioClipping ? 0xFFFF3B30 : 0xFF7CFF8B);
            c.drawRoundRect(new RectF(meterX - dp(4), meterTop + meterH - fill, meterX + dp(4), meterTop + meterH), dp(3), dp(3), paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x33FFFFFF);
        float m = dp(12);
        c.drawRoundRect(new RectF(m, m, w - m, h - m), dp(10), dp(10), paint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
