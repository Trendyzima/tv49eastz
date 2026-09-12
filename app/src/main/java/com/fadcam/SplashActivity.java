package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.fadcam.ui.WatchMainActivity;
import com.fadcam.utils.RuntimeCompat;

/**
 * Dedicated launcher splash activity.
 * Keeps the launcher path lightweight and routes exactly once after the splash frame is shown.
 */
public class SplashActivity extends Activity {
    private static final long SPLASH_DELAY_MS = 800L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean routed;

    private final Runnable routeRunnable = new Runnable() {
        @Override
        public void run() {
            if (routed || isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                return;
            }
            routed = true;

            try {
                Class<?> destination = RuntimeCompat.shouldUseWatchUi(SplashActivity.this)
                        ? WatchMainActivity.class
                        : MainActivity.class;
                Intent intent = new Intent(SplashActivity.this, destination);
                startActivity(intent);
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                finish();
            } catch (Throwable t) {
                // Never let a routing/compatibility failure take down the launcher process.
                FLog.e("SplashActivity", "Failed to route from splash", t);
                try {
                    Intent fallback = new Intent(SplashActivity.this, MainActivity.class);
                    startActivity(fallback);
                    finish();
                } catch (Throwable fallbackFailure) {
                    FLog.e("SplashActivity", "Fallback launch also failed", fallbackFailure);
                    routed = false;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_splash);
        } catch (Throwable t) {
            FLog.e("SplashActivity", "Splash layout failed to inflate", t);
            // Keep a valid blank window rather than crashing before routing.
        }
        mainHandler.postDelayed(routeRunnable, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(routeRunnable);
        super.onDestroy();
    }
}
