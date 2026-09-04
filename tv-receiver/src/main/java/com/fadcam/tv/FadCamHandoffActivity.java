package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/** Signature-protected bridge from the FadCam publisher into the receiver UI. */
public final class FadCamHandoffActivity extends Activity {
    private static final int LOCAL_NETWORK_REQUEST = 4902;
    private Intent pendingPlayback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent() == null ? null : getIntent().getData();
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(this, uri);
        if (!result.accepted) {
            finish();
            return;
        }

        Uri playbackUri = Uri.parse("tv49east://channel").buildUpon()
                .appendQueryParameter("url", result.streamUrl)
                .appendQueryParameter("name", result.name)
                .appendQueryParameter("owner", result.owner)
                .appendQueryParameter("id", "fadcam-local")
                .build();
        pendingPlayback = new Intent(Intent.ACTION_VIEW, playbackUri);
        pendingPlayback.setClass(this, FadCamDirectStreamActivity.class);
        pendingPlayback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (needsLocalNetworkPermission()) {
            requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, LOCAL_NETWORK_REQUEST);
        } else {
            launchPlayback();
        }
    }

    private boolean needsLocalNetworkPermission() {
        return Build.VERSION.SDK_INT >= 37
                && getApplicationInfo().targetSdkVersion >= 37
                && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED;
    }

    private void launchPlayback() {
        Intent playback = pendingPlayback;
        pendingPlayback = null;
        if (playback != null) startActivity(playback);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCAL_NETWORK_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchPlayback();
        } else {
            pendingPlayback = null;
            finish();
        }
    }
}
