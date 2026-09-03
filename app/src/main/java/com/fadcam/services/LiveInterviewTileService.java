package com.fadcam.services;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.RecordingStartActivity;
import com.fadcam.RecordingStopActivity;
import com.fadcam.SharedPreferencesManager;

/**
 * Dedicated Quick Settings entry point for a TV 49 East live interview.
 * It starts the existing foreground-safe RecordingStartActivity, which starts
 * the LAN HLS server before the dual-camera PiP service.
 */
public final class LiveInterviewTileService extends TileService {
    private static final String TAG = "LiveInterviewTile";
    private static final String PREF_LIVE_INTERVIEW = "fadcam_live_interview_active";

    @Override public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }

    @Override public void onClick() {
        super.onClick();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        boolean active = prefs.sharedPreferences.getBoolean(PREF_LIVE_INTERVIEW, false)
                && prefs.isRecordingInProgress();
        Intent intent = new Intent(this, active
                ? RecordingStopActivity.class
                : RecordingStartActivity.class);
        if (!active) {
            intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                    RecordingStartActivity.CAMERA_MODE_INTERVIEW);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this, 4901, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (RuntimeException e) {
            FLog.e(TAG, "Unable to launch live interview action", e);
        }
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        boolean active = prefs.sharedPreferences.getBoolean(PREF_LIVE_INTERVIEW, false)
                && prefs.isRecordingInProgress();
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(active ? "End TV 49 Interview" : "Live Interview • TV 49 East");
        tile.setIcon(Icon.createWithResource(this,
                active ? R.drawable.ic_qs_tile_stop : R.drawable.ic_qs_tile_videocam_dual));
        tile.updateTile();
    }
}
