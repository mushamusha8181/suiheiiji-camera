package jp.mushamusha.horizontalcamera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Provides continuous device roll for horizon correction.
 *
 * Prefers the game rotation vector because it fuses gyro + accelerometer without magnetic-north
 * disturbances. Falls back to the regular rotation vector, then gravity/accelerometer.
 * When the camera points almost straight up/down the physical horizon is undefined, so the last
 * stable roll is held instead of letting the value jump wildly.
 */
public final class HorizonSensor implements SensorEventListener {
    public interface Listener { void onRoll(float rollDegrees); }

    private final SensorManager manager;
    private final Sensor sensor;
    private final boolean rotationVector;
    private final boolean fallbackAccel;
    private final Listener listener;
    private final float[] gravity = new float[3];
    private final float[] rotation = new float[9];
    private boolean initialized;
    private boolean stableRollReady;
    private float lastStableRoll;
    private boolean started;

    public HorizonSensor(Context context, Listener listener) {
        this.manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor selected = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        boolean rv = selected != null;
        if (selected == null) {
            selected = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            rv = selected != null;
        }
        boolean accelFallback = false;
        if (selected == null) selected = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (selected == null) {
            selected = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            accelFallback = true;
        }
        sensor = selected;
        rotationVector = rv;
        fallbackAccel = accelFallback;
        this.listener = listener;
    }

    public synchronized void start() {
        if (started || sensor == null) return;
        started = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public synchronized void stop() {
        if (!started) return;
        manager.unregisterListener(this);
        started = false;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float gx;
        float gy;
        float gz;

        if (rotationVector) {
            try {
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                // Third row is the world-up axis expressed in device coordinates.
                gx = rotation[6];
                gy = rotation[7];
                gz = rotation[8];
            } catch (Throwable ignored) {
                return;
            }
        } else {
            if (fallbackAccel) {
                final float alpha = 0.86f;
                if (!initialized) {
                    System.arraycopy(event.values, 0, gravity, 0, 3);
                    initialized = true;
                } else {
                    for (int i = 0; i < 3; i++) gravity[i] = alpha * gravity[i] + (1f - alpha) * event.values[i];
                }
            } else {
                System.arraycopy(event.values, 0, gravity, 0, 3);
            }
            gx = gravity[0];
            gy = gravity[1];
            gz = gravity[2];
        }

        // The roll around the camera optical axis is determined by gravity projected onto the
        // screen plane. Near a straight-up/down camera pose that projection approaches zero and
        // the horizon is physically undefined, so freeze the last stable value.
        float planar = (float) Math.sqrt(gx * gx + gy * gy);
        float magnitude = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        float normalizedPlanar = magnitude > 1e-5f ? planar / magnitude : 0f;
        if (normalizedPlanar < 0.12f && stableRollReady) {
            listener.onRoll(lastStableRoll);
            return;
        }

        float deg = (float) Math.toDegrees(Math.atan2(-gx, gy));
        deg = normalize180(deg);
        lastStableRoll = deg;
        stableRollReady = true;
        listener.onRoll(deg);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    public static float normalize180(float v) {
        while (v > 180f) v -= 360f;
        while (v < -180f) v += 360f;
        return v;
    }

    public static float snapReference(float currentRoll, boolean portrait) {
        float step = 180f;
        float base = portrait ? 0f : 90f;
        float n = Math.round((currentRoll - base) / step);
        return normalize180(base + n * step);
    }
}
