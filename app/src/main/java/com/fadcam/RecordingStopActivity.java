package com.fadcam;

import com.fadcam.FLog;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.streaming.RemoteStreamService;
import com.fadcam.utils.ServiceUtils;

public class RecordingStopActivity extends Activity {
    private static final String TAG = "RecordingStopActivity";
    private static final String PREF_LIVE_INTERVIEW = "fadcam_live_interview_active";
    private static final String PREF_PREVIOUS_STREAMING_MODE = "fadcam_interview_previous_streaming_mode";
    private static final long STREAM_SHUTDOWN_GRACE_MS = 1500L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            SharedPreferencesManager sp = SharedPreferencesManager.getInstance(this);
            boolean liveInterview = sp.sharedPreferences.getBoolean(PREF_LIVE_INTERVIEW, false);
            boolean dualRunning = ServiceUtils.isServiceRunning(this, DualCameraRecordingService.class)
                    || (sp.getCameraSelection() != null && sp.getCameraSelection().isDual() && sp.isRecordingInProgress());
            Intent stopIntent = dualRunning
                    ? new Intent(this, DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING)
                    : new Intent(this, RecordingService.class).setAction(Constants.INTENT_ACTION_STOP_RECORDING);

            startService(stopIntent);

            if (liveInterview) {
                int previousMode = sp.sharedPreferences.getInt(PREF_PREVIOUS_STREAMING_MODE, 0);
                getSharedPreferences("FadCamCloudPrefs", MODE_PRIVATE).edit()
                        .putInt("streaming_mode", previousMode).apply();
                sp.sharedPreferences.edit()
                        .remove(PREF_LIVE_INTERVIEW)
                        .remove(PREF_PREVIOUS_STREAMING_MODE)
                        .apply();

                // Let the dual pipeline drain/finalize its last fMP4 fragment before
                // RemoteStreamService clears the live fragment buffer.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { stopService(new Intent(RecordingStopActivity.this, RemoteStreamService.class)); }
                    catch (Exception e) { FLog.w(TAG, "Unable to stop interview LAN stream service", e); }
                }, STREAM_SHUTDOWN_GRACE_MS);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error stopping recording via shortcut", e);
            Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
        } finally {
            moveTaskToBack(true);
            finish();
        }
    }

    @Override protected void onStop() {
        super.onStop();
        finish();
    }

    @Override protected void onPause() {
        super.onPause();
        moveTaskToBack(true);
    }
}
