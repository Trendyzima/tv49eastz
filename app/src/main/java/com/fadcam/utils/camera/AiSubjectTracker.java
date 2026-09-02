package com.fadcam.utils.camera;

import android.content.Context;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.motion.domain.detector.EfficientDetLite1Detector;

import java.util.List;

/**
 * Lightweight AI target tracker built on the repository's existing EfficientDet-Lite1
 * detector. Recording FPS is unaffected: inference is explicitly throttled and target
 * changes use hysteresis so autofocus does not hunt between nearby objects.
 */
public final class AiSubjectTracker {
    private static final String TAG = "AiSubjectTracker";
    private static final long DEFAULT_INTERVAL_MS = 120L; // ~8.3 AI decisions/sec
    private static final long TARGET_HOLD_MS = 500L;
    private static final float MIN_TARGET_CONFIDENCE = 0.35f;
    private static final float MIN_CENTER_DELTA = 0.025f;

    private final EfficientDetLite1Detector detector;
    private final long inferenceIntervalMs;
    private long lastInferenceMs;
    private long targetUntilMs;
    private EfficientDetLite1Detector.DetectionResult target;

    public AiSubjectTracker(@NonNull Context context) {
        this(context, DEFAULT_INTERVAL_MS);
    }

    public AiSubjectTracker(@NonNull Context context, long inferenceIntervalMs) {
        detector = new EfficientDetLite1Detector(context.getApplicationContext());
        this.inferenceIntervalMs = Math.max(80L, inferenceIntervalMs);
    }

    public boolean isAvailable() {
        return detector.isAvailable();
    }

    /**
     * Analyze at most once per interval. The supplied Image is never retained after return.
     * Caller owns the Image and must close it.
     */
    @Nullable
    public synchronized EfficientDetLite1Detector.DetectionResult analyze(@Nullable Image image) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (image == null || !detector.isAvailable()) return target;
        if (now - lastInferenceMs < inferenceIntervalMs) return target;
        lastInferenceMs = now;

        try {
            List<EfficientDetLite1Detector.DetectionResult> detections = detector.detect(image);
            EfficientDetLite1Detector.DetectionResult candidate = chooseTarget(detections);
            if (candidate == null) {
                if (now >= targetUntilMs) target = null;
                return target;
            }

            if (candidate.confidence < MIN_TARGET_CONFIDENCE) return target;
            if (target == null) {
                target = candidate;
                targetUntilMs = now + TARGET_HOLD_MS;
                return target;
            }

            float dx = Math.abs(candidate.centerX - target.centerX);
            float dy = Math.abs(candidate.centerY - target.centerY);
            boolean sameTargetRegion = dx <= MIN_CENTER_DELTA && dy <= MIN_CENTER_DELTA;
            if (sameTargetRegion || candidate.confidence >= target.confidence + 0.12f
                    || now >= targetUntilMs) {
                target = candidate;
                targetUntilMs = now + TARGET_HOLD_MS;
            }
            return target;
        } catch (Throwable t) {
            FLog.w(TAG, "AI frame skipped safely: " + t.getClass().getSimpleName());
            return target;
        }
    }

    @Nullable
    private EfficientDetLite1Detector.DetectionResult chooseTarget(
            List<EfficientDetLite1Detector.DetectionResult> detections) {
        if (detections == null || detections.isEmpty()) return null;
        EfficientDetLite1Detector.DetectionResult bestPerson = null;
        EfficientDetLite1Detector.DetectionResult best = null;
        for (EfficientDetLite1Detector.DetectionResult d : detections) {
            if (d == null) continue;
            if (best == null || score(d) > score(best)) best = d;
            if ("PERSON".equals(d.coarseType)
                    && (bestPerson == null || score(d) > score(bestPerson))) {
                bestPerson = d;
            }
        }
        // Prefer a person when one is confidently present; otherwise track the
        // strongest/most stable subject already exposed by the existing detector.
        return bestPerson != null && bestPerson.confidence >= MIN_TARGET_CONFIDENCE
                ? bestPerson : best;
    }

    private float score(EfficientDetLite1Detector.DetectionResult d) {
        float area = Math.max(0f, d.width * d.height);
        return d.confidence * (1f + Math.min(1f, area));
    }

    public synchronized void reset() {
        target = null;
        targetUntilMs = 0L;
        lastInferenceMs = 0L;
    }
}
