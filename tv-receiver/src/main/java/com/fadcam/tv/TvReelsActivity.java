package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Backward-compatible deep-link shim.
 *
 * The legacy screen used to own a second IPTV playback implementation. Keeping two
 * player stacks made fixes easy to miss and allowed old tv49east://channel links to
 * bypass the hardened surface. This activity now forwards those links to the single
 * production playback surface and immediately finishes.
 */
public final class TvReelsActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);

        Intent next = new Intent(this, TvReelsActivityHardened.class);
        next.setAction(getIntent().getAction());
        next.setData(getIntent().getData());
        next.putExtras(getIntent());
        startActivity(next);
        finish();
    }
}
