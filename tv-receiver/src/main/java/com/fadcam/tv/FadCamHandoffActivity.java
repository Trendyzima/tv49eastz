package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

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

        Uri playbackUri = Uri.parse("tv49east://channel").buildUpon()
                .appendQueryParameter("url", result.streamUrl)
                .appendQueryParameter("name", result.name)
                .appendQueryParameter("owner", result.owner)
                .appendQueryParameter("id", "fadcam-local")
                .build();
        Intent playback = new Intent(Intent.ACTION_VIEW, playbackUri);
        playback.setClass(this, FadCamDirectStreamActivity.class);
        playback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(playback);
        finish();
    }
}
