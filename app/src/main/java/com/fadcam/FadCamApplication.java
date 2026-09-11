package com.fadcam;

import android.app.Application;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * Application lifecycle hooks.
 *
 * This class must remain side-effect-light. In particular, application
 * foreground/background callbacks must never start a service: Android may
 * reject those starts during lifecycle transitions and terminate the process.
 * RecordingService is started only by explicit recording entry points.
 */
public class FadCamApplication extends Application implements LifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    private void registerSelfHealingScanObserver() {
        try {
            final android.content.Context app = getApplicationContext();
            androidx.room.RoomDatabase db =
                    com.fadcam.data.VideoIndexDatabase.getInstance(app);
            db.getInvalidationTracker().addObserver(
                    new androidx.room.InvalidationTracker.Observer(
                            new String[]{"video_index"}) {
                        @Override
                        public void onInvalidated(
                                @androidx.annotation.NonNull java.util.Set<String> tables) {
                            try {
                                com.fadcam.services.RecordingService
                                        .runSelfHealingScan(app, null);
                            } catch (Throwable error) {
                                com.fadcam.FLog.w(
                                        "FadCamApplication",
                                        "Self-healing scan trigger failed",
                                        error);
                            }
                        }
                    });
            com.fadcam.FLog.d(
                    "FadCamApplication",
                    "Self-healing scan observer registered (video_index)");
        } catch (Throwable error) {
            // Recovery infrastructure is optional and must never break launch.
            com.fadcam.FLog.w(
                    "FadCamApplication",
                    "Self-healing observer unavailable",
                    error);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        try {
            SharedPreferencesManager.getInstance(this)
                    .setAppLockSessionUnlocked(false);
        } catch (Throwable error) {
            com.fadcam.FLog.w(
                    "FadCamApplication",
                    "Unable to reset app-lock session",
                    error);
        }
        // Deliberately do not start RecordingService here.
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        // Deliberately no service start from process lifecycle callbacks.
        // Explicit recording actions own service lifecycle and notification setup.
    }
}
