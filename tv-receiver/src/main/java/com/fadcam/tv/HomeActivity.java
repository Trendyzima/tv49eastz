package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** TV 49 East launcher: the native social/TV experience shell is the single entry point. */
public final class HomeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent intent = new Intent(this, SocialParityActivity.class);
            intent.setData(getIntent().getData());
            if (getIntent().getExtras() != null) intent.putExtras(getIntent());
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
            try {
                Intent fallback = new Intent(this, ModernSocialActivity.class);
                fallback.setData(getIntent().getData());
                fallback.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(fallback);
            } catch (Throwable ignoredAgain) {
                try { startActivity(new Intent(this, MainActivity.class)); } catch (Throwable ignoredLast) { }
            }
        }
        finish();
    }
}
