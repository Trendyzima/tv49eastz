package com.fadcam;

import android.app.Application;

/**
 * Minimal process bootstrap for FadCam.
 *
 * Application startup must remain side-effect free: it must not initialize Room,
 * register database observers, inspect running services, or start/notify the
 * recording service. Those operations belong to explicit feature/service flows.
 *
 * This is intentionally conservative because Application.onCreate() runs before
 * the launcher activity and any uncaught startup failure prevents the app from
 * opening at all.
 */
public class FadCamApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FLog.d("FadCamApplication", "Minimal application bootstrap completed");
    }
}
