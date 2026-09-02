package com.fadcam.utils.camera;

import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;

import androidx.annotation.NonNull;

/** Converts normalized AI coordinates into safe Camera2 sensor metering regions. */
public final class Camera2FocusMapper {
    private Camera2FocusMapper() {}

    /**
     * Maps a normalized target box (0..1) into active-array coordinates.
     * The returned region is clamped, non-empty and weighted for AF/AE handoff.
     */
    @NonNull
    public static MeteringRectangle toMeteringRectangle(
            @NonNull Rect activeArray,
            float centerX,
            float centerY,
            float width,
            float height,
            int weight) {
        float cx = clamp01(centerX);
        float cy = clamp01(centerY);
        float w = clamp(width, 0.04f, 0.60f);
        float h = clamp(height, 0.04f, 0.60f);

        int left = activeArray.left + Math.round((cx - w * 0.5f) * activeArray.width());
        int top = activeArray.top + Math.round((cy - h * 0.5f) * activeArray.height());
        int right = activeArray.left + Math.round((cx + w * 0.5f) * activeArray.width());
        int bottom = activeArray.top + Math.round((cy + h * 0.5f) * activeArray.height());

        int minSize = Math.max(2, Math.min(activeArray.width(), activeArray.height()) / 100);
        left = clamp(left, activeArray.left, activeArray.right - minSize);
        top = clamp(top, activeArray.top, activeArray.bottom - minSize);
        right = clamp(right, left + minSize, activeArray.right);
        bottom = clamp(bottom, top + minSize, activeArray.bottom);

        int safeWeight = clamp(weight, MeteringRectangle.METERING_WEIGHT_MIN,
                MeteringRectangle.METERING_WEIGHT_MAX);
        return new MeteringRectangle(left, top, right - left, bottom - top, safeWeight);
    }

    private static float clamp01(float v) { return clamp(v, 0f, 1f); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
