package com.fadcam;

import com.fadcam.FLog;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.streaming.RemoteStreamService;

public class RecordingStartActivity extends Activity {
    private static final String TAG = "RecordingStartActivity";
    public static final String EXTRA_SHORTCUT_CAMERA_MODE = "shortcut_camera_mode";
    public static final String EXTRA_PRODUCER_VIDEO_URI = "producer_video_uri";
    public static final String CAMERA_MODE_BACK = "back";
    public static final String CAMERA_MODE_FRONT = "front";
    public static final String CAMERA_MODE_CURRENT = "current";
    public static final String CAMERA_MODE_DUAL = "dual";
    /** Starts dual-camera PiP plus the local FadCam HLS server for TV 49 East. */
    public static final String CAMERA_MODE_INTERVIEW = "live_interview";
    /** Starts live producer mode: local video full-screen + live camera commentary in PiP. */
    public static final String CAMERA_MODE_INTERVIEW_VIDEO = "live_interview_video";
    private static final String PREF_LIVE_INTERVIEW = "fadcam_live_interview_active";
    private static final String PREF_PREVIOUS_STREAMING_MODE = "fadcam_interview_previous_streaming_mode";
    private static final String PREF_PRODUCER_VIDEO_URI = "fadcam_producer_video_uri";
    private static final long STREAM_SERVER_WARMUP_MS = 1200L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            if (sharedPreferencesManager.isRecordingInProgress()) {
                Utils.showQuickToast(this, R.string.video_recording_started);
                finish();
                return;
            }

            String mode = getIntent() != null
                    ? getIntent().getStringExtra(EXTRA_SHORTCUT_CAMERA_MODE)
                    : null;
            if (mode == null) mode = CAMERA_MODE_BACK;

            if (CAMERA_MODE_FRONT.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.FRONT.name()).apply();
            } else if (CAMERA_MODE_BACK.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.BACK.name()).apply();
            } else if (CAMERA_MODE_DUAL.equals(mode)
                    || CAMERA_MODE_INTERVIEW.equals(mode)
                    || CAMERA_MODE_INTERVIEW_VIDEO.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.DUAL_PIP.name()).apply();
            }

            CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
            boolean producerVideo = CAMERA_MODE_INTERVIEW_VIDEO.equals(mode);
            boolean liveInterview = CAMERA_MODE_INTERVIEW.equals(mode) || producerVideo;
            boolean shouldStartDual = CAMERA_MODE_DUAL.equals(mode) || liveInterview
                    || (CAMERA_MODE_CURRENT.equals(mode)
                    && selectedCamera != null && selectedCamera.isDual());

            if (liveInterview) {
                android.content.SharedPreferences cloudPrefs = getSharedPreferences("FadCamCloudPrefs", MODE_PRIVATE);
                int previousMode = cloudPrefs.getInt("streaming_mode", 0);
                String producerUri = getIntent() != null
                        ? getIntent().getStringExtra(EXTRA_PRODUCER_VIDEO_URI) : null;
                sharedPreferencesManager.sharedPreferences.edit()
                        .putBoolean(PREF_LIVE_INTERVIEW, true)
                        .putInt(PREF_PREVIOUS_STREAMING_MODE, previousMode)
                        .putString(PREF_PRODUCER_VIDEO_URI, producerUri == null ? "" : producerUri)
                        .apply();
                cloudPrefs.edit().putInt("streaming_mode", 0).apply();

                startRemoteStreamService();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> startDualServiceSafely(producerVideo), STREAM_SERVER_WARMUP_MS);
            } else if (shouldStartDual) {
                startDualServiceSafely(false);
            } else {
                Intent startIntent = new Intent(this, RecordingService.class);
                startIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);
                startServiceCompat(startIntent);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error starting recording via shortcut", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
        } finally {
            moveTaskToBack(true);
            finish();
        }
    }

    private void startRemoteStreamService() {
        Intent intent = new Intent(this, RemoteStreamService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent);
            } else {
                startService(intent);
            }
            FLog.i(TAG, "Live interview: FadCam LAN streaming service requested");
        } catch (RuntimeException e) {
            FLog.e(TAG, "Live interview: unable to start LAN streaming service", e);
            SharedPreferencesManager.getInstance(this).sharedPreferences.edit()
                    .putBoolean(PREF_LIVE_INTERVIEW, false).apply();
            throw e;
        }
    }

    private void startDualServiceSafely(boolean producerVideo) {
        try {
            Intent intent = new Intent(this, DualCameraRecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
            if (producerVideo) {
                String uri = SharedPreferencesManager.getInstance(this).sharedPreferences
                        .getString(PREF_PRODUCER_VIDEO_URI, "");
                if (uri != null && !uri.isEmpty()) {
                    intent.putExtra(DualCameraRecordingService.EXTRA_PRODUCER_VIDEO_URI, uri);
                }
            }
            startServiceCompat(intent);
            FLog.i(TAG, producerVideo
                    ? "Live producer: dual PiP camera service requested with program video"
                    : "Live interview: dual PiP camera service requested");
        } catch (RuntimeException e) {
            FLog.e(TAG, "Live interview: unable to start dual camera service", e);
            SharedPreferencesManager.getInstance(this).sharedPreferences.edit()
                    .putBoolean(PREF_LIVE_INTERVIEW, false).apply();
            try { stopService(new Intent(this, RemoteStreamService.class)); } catch (Exception ignored) { }
        }
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
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
