package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

/** Backward-compatible deep-link shim for the receiver. */
public final class TvReelsActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);

        Uri data = getIntent() == null ? null : getIntent().getData();
        String stream = data == null ? null : data.getQueryParameter("url");
        Intent next;
        if (stream != null && !stream.trim().isEmpty()) {
            next = new Intent(this, FadCamDirectStreamActivity.class);
            next.setAction(getIntent().getAction());
            next.setData(data);
        } else {
            next = new Intent(this, TvReelsActivityHardened.class);
            next.setAction(getIntent().getAction());
            next.setData(data);
            if (getIntent().getExtras() != null) next.putExtras(getIntent());
        }
        startActivity(next);
        finish();
    }
}
