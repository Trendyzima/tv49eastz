package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;

/** Signature-protected bridge from the FadCam publisher into the receiver UI. */
public final class FadCamHandoffActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent() == null ? null : getIntent().getData();
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(this, uri);
        if (!result.accepted) {
            finish();
            return;
        }
        Intent playback = new Intent(this, MainActivity.class);
        playback.putExtra("tv49east_verified_fadcam_url", result.streamUrl);
        playback.putExtra("tv49east_verified_fadcam_name", result.name);
        playback.putExtra("tv49east_verified_fadcam_owner", result.owner);
        playback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(playback);
        finish();
    }
}
