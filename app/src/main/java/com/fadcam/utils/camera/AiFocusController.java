package com.fadcam.utils.camera;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.motion.domain.detector.EfficientDetLite1Detector;

/**
 * Applies AI-selected subject regions to an already-running Camera2 session.
 * The controller is deliberately fail-safe: unsupported AF/metering or a transient
 * camera error leaves the existing repeating request untouched.
 */
public final class AiFocusController {
    private static final String TAG = "AiFocusController";
    private static final long UPDATE_COOLDOWN_MS = 180L;
    private static final float MIN_MOVE = 0.025f;

    private long lastUpdateMs;
    private float lastX = -1f;
    private float lastY = -1f;

    public boolean apply(@Nullable CameraCaptureSession session,
                         @Nullable CaptureRequest.Builder builder,
                         @Nullable CameraCharacteristics characteristics,
                         @Nullable EfficientDetLite1Detector.DetectionResult target,
                         @Nullable Handler callbackHandler) {
        if (session == null || builder == null || characteristics == null || target == null) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastUpdateMs < UPDATE_COOLDOWN_MS) return false;
        if (lastX >= 0f && Math.abs(target.centerX - lastX) < MIN_MOVE
                && Math.abs(target.centerY - lastY) < MIN_MOVE) return false;

        try {
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            boolean continuous = false;
            if (afModes != null) {
                for (int mode : afModes) {
                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                        continuous = true;
                        break;
                    }
                }
            }
            if (!continuous) return false;

            Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null || active.width() <= 0 || active.height() <= 0) return false;

            MeteringRectangle region = Camera2FocusMapper.toMeteringRectangle(
                    active, target.centerX, target.centerY, target.width, target.height, 800);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{region});
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(builder.build(), null, callbackHandler);

            // Return to continuous AF immediately after the one-shot lock is requested.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            session.setRepeatingRequest(builder.build(), null, callbackHandler);

            lastUpdateMs = now;
            lastX = target.centerX;
            lastY = target.centerY;
            return true;
        } catch (Throwable t) {
            FLog.w(TAG, "AI focus handoff rejected safely: " + t.getClass().getSimpleName());
            return false;
        }
    }

    public void reset() {
        lastUpdateMs = 0L;
        lastX = -1f;
        lastY = -1f;
    }
}
