package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * TV 49 East entry point.
 *
 * Social is now the default experience. The old mode picker is intentionally
 * removed from the launch surface; its destinations live in the Social drawer
 * and are reachable from the horizontal navigation gestures.
 */
public final class HomeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent intent = new Intent(this, ModernSocialActivity.class);
            intent.setData(getIntent().getData());
            if (getIntent().getExtras() != null) intent.putExtras(getIntent());
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
            // Keep the launcher recoverable if the social surface cannot start.
        }
        finish();
    }
}
