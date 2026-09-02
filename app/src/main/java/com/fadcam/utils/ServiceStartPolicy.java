package com.fadcam.utils;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.camera.AdaptiveCameraProfile;

/**
 * Central policy for recording-action service start mode.
 * Start actions use foreground start; control/query actions use normal startService.
 *
 * <p>The single-camera recording path also performs a best-effort hardware
 * capability preflight immediately before the service starts. This keeps the
 * existing Camera2/MediaRecorder engine intact while preventing impossible
 * resolution/FPS/codec combinations from being selected on a device.</p>
 */
public final class ServiceStartPolicy {
    private static final String TAG = "ServiceStartPolicy";

    private ServiceStartPolicy() {}

    public static void startRecordingAction(@NonNull Context context, @NonNull Intent intent) {
        final String action = intent.getAction();

        // Do not preflight screen recording or Dual PiP through the single-camera
        // selector. Dual PiP has its own capability/combination validator.
        if (Constants.INTENT_ACTION_START_RECORDING.equals(action)) {
            try {
                SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
                CameraType cameraType = prefs.getCameraSelection();
                if (cameraType == null) cameraType = CameraType.BACK;
                AdaptiveCameraProfile.Report report =
                        AdaptiveCameraProfile.applyBestAvailableProfile(context, cameraType);
                if (report != null && report.selected != null) {
                    FLog.i(TAG, "Adaptive preflight selected " + report.selected);
                }
            } catch (Throwable t) {
                // Capability discovery is an optimization, never a recording gate.
                FLog.w(TAG, "Adaptive camera preflight failed; continuing with saved profile", t);
            }
        }

        final boolean foreground = isForegroundStartAction(action);
        FLog.d(TAG, "dispatch action=" + action + ", mode=" + (foreground ? "foreground" : "service"));
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static boolean isForegroundStartAction(String action) {
        if (action == null) return false;
        return Constants.INTENT_ACTION_START_RECORDING.equals(action)
                || Constants.INTENT_ACTION_START_DUAL_RECORDING.equals(action)
                || Constants.INTENT_ACTION_START_SCREEN_RECORDING.equals(action)
                || Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY.equals(action);
    }
}
