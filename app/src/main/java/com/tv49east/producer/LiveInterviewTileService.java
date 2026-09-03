package com.fadcam.services;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.fadcam.FLog;
import com.fadcam.LiveProducerActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.streaming.RemoteStreamService;

/** Dedicated Quick Settings entry point for a TV 49 East live producer session. */
public final class LiveInterviewTileService extends TileService {
    private static final String TAG = "LiveInterviewTile";
    private static final String PREF_LIVE_INTERVIEW = "fadcam_live_interview_active";
    private BroadcastReceiver stateReceiver;

    @Override public void onStartListening() {
        super.onStartListening();
        registerStateReceiver();
        refreshTile();
    }

    @Override public void onStopListening() {
        unregisterStateReceiver();
        super.onStopListening();
    }

    @Override public void onDestroy() {
        unregisterStateReceiver();
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        boolean active = prefs.sharedPreferences.getBoolean(PREF_LIVE_INTERVIEW, false)
                && prefs.isRecordingInProgress();
        try {
            if (active) {
                stopProducerSession();
            } else {
                Intent intent = new Intent(this, LiveProducerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    PendingIntent pi = PendingIntent.getActivity(this, 4901, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    startActivityAndCollapse(pi);
                } else {
                    startActivityAndCollapse(intent);
                }
            }
        } catch (RuntimeException e) {
            FLog.e(TAG, "Unable to launch live producer action", e);
        }
    }

    private void stopProducerSession() {
        try {
            Intent stopDual = new Intent(this, DualCameraRecordingService.class)
                    .setAction(com.fadcam.Constants.INTENT_ACTION_STOP_DUAL_RECORDING);
            startService(stopDual);
        } catch (Exception e) {
            FLog.w(TAG, "Unable to stop dual producer service", e);
        }
        try { stopService(new Intent(this, RemoteStreamService.class)); }
        catch (Exception e) { FLog.w(TAG, "Unable to stop producer stream service", e); }
        prefsClear();
        refreshTile();
    }

    private void prefsClear() {
        SharedPreferencesManager.getInstance(this).sharedPreferences.edit()
                .remove(PREF_LIVE_INTERVIEW)
                .remove("fadcam_producer_video_uri")
                .apply();
    }

    private void registerStateReceiver() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { refreshTile(); }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(com.fadcam.Constants.BROADCAST_ON_RECORDING_STARTED);
        filter.addAction(com.fadcam.Constants.BROADCAST_ON_RECORDING_STOPPED);
        filter.addAction(com.fadcam.Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
        filter.addAction(com.fadcam.Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }

    private void unregisterStateReceiver() {
        if (stateReceiver == null) return;
        try { unregisterReceiver(stateReceiver); } catch (IllegalArgumentException ignored) { }
        stateReceiver = null;
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        boolean active = prefs.sharedPreferences.getBoolean(PREF_LIVE_INTERVIEW, false)
                && prefs.isRecordingInProgress();
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(active ? "End TV 49 Interview" : "TV 49 Producer");
        tile.setIcon(Icon.createWithResource(this,
                active ? R.drawable.ic_qs_tile_stop : R.drawable.ic_qs_tile_videocam_dual));
        tile.updateTile();
    }
}
