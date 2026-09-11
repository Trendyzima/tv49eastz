package com.fadcam;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * Application-level lifecycle hooks.
 *
 * Startup must remain side-effect-light: a launcher activity must never be able
 * to crash because a background-service start is rejected by Android's modern
 * background execution rules. Service notifications/recording are owned by the
 * service entry points; this class only sends best-effort lifecycle hints.
 */
public class FadCamApplication extends Application implements LifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

        // Room initialization is deliberately off the main thread. Failure to
        // register the optional self-healing observer must never affect launch.
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    private void registerSelfHealingScanObserver() {
        try {
            final android.content.Context app = getApplicationContext();
            androidx.room.RoomDatabase db = com.fadcam.data.VideoIndexDatabase.getInstance(app);
            db.getInvalidationTracker().addObserver(
                    new androidx.room.InvalidationTracker.Observer(new String[]{"video_index"}) {
                        @Override
                        public void onInvalidated(@NonNull java.util.Set<String> tables) {
                            try {
                                com.fadcam.services.RecordingService.runSelfHealingScan(app, null);
                            } catch (Throwable error) {
                                com.fadcam.FLog.w("FadCamApplication", "Self-healing scan trigger failed", error);
                            }
                        }
                    });
            com.fadcam.FLog.d("FadCamApplication", "Self-healing scan observer registered (video_index)");
        } catch (Throwable error) {
            // Optional recovery infrastructure must never crash the APK.
            com.fadcam.FLog.w("FadCamApplication", "Self-healing observer unavailable", error);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        try {
            SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        } catch (Throwable error) {
            com.fadcam.FLog.w("FadCamApplication", "Unable to reset app-lock session", error);
        }

        // Never start a normal background service from ON_STOP. Android 8+
        // can throw IllegalStateException here and terminate the whole app.
        // RecordingService receives explicit recording/stop intents elsewhere.
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        // This is a best-effort foreground hint only. Do not start a service
        // unless the currently visible task is a recording-related activity.
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;
            ComponentName topActivity = tasks.get(0).topActivity;
            if (topActivity == null) return;

            String className = topActivity.getClassName();
            boolean recordingRelated = className.contains("MainActivity")
                    || className.contains("FadRecHomeActivity")
                    || className.contains("RecordingActivity");
            if (!recordingRelated) return;

            Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
            intent.setAction("ACTION_APP_FOREGROUND");
            // ON_START is normally foreground, but OEM task/lifecycle behavior
            // can still reject service starts. Treat this hint as non-critical.
            try {
                startService(intent);
            } catch (IllegalStateException | SecurityException error) {
                com.fadcam.FLog.w("FadCamApplication", "Foreground hint rejected; continuing safely", error);
            }
        } catch (Throwable error) {
            com.fadcam.FLog.w("FadCamApplication", "Foreground lifecycle hint skipped", error);
        }
    }
}
