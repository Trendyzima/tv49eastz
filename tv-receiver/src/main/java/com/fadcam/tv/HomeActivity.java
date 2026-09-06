package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * TV 49 East entry point.
 *
 * The social-first surface is the native ModernSocialActivity, which is the Android
 * implementation of the Testagram social experience. It talks to the shared social
 * backend directly; it does not embed testagram.site in a WebView.
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
            try {
                Intent fallback = new Intent(this, XSocialActivity.class);
                fallback.setData(getIntent().getData());
                startActivity(fallback);
            } catch (Throwable ignoredAgain) {
                // Keep the launcher recoverable if both social surfaces fail to start.
            }
        }
        finish();
    }
}
