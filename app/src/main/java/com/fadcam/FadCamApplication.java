package com.fadcam;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

public class FadCamApplication extends Application implements LifecycleObserver {
    private static final String TAG = "FadCamApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

        // Keep Room observer registration off the main thread. This observer must
        // never be allowed to interfere with application launch.
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    /**
     * Register the native self-healing trigger without starting RecordingService.
     * The observer only reacts to index changes after Room has initialized.
     */
    private void registerSelfHealingScanObserver() {
        try {
            final Context app = getApplicationContext();
            androidx.room.RoomDatabase db =
                    com.fadcam.data.VideoIndexDatabase.getInstance(app);
            db.getInvalidationTracker().addObserver(
                    new androidx.room.InvalidationTracker.Observer(new String[]{"video_index"}) {
                        @Override
                        public void onInvalidated(
                                @androidx.annotation.NonNull java.util.Set<String> tables) {
                            try {
                                com.fadcam.services.RecordingService.runSelfHealingScan(app, null);
                            } catch (Exception e) {
                                FLog.w(TAG, "Self-healing scan trigger failed", e);
                            }
                        }
                    });
            FLog.d(TAG, "Self-healing scan observer registered (video_index)");
        } catch (Exception e) {
            FLog.w(TAG, "Failed to register self-healing scan observer", e);
        }
    }

    /**
     * Lifecycle notifications must never create RecordingService implicitly.
     * Creating the full camera service from Application ON_START/ON_STOP caused
     * launch-time crashes when optional service initialization failed or Android
     * rejected a background start. Notify the service only when it is already
     * running; actual service creation remains owned by explicit user actions.
     */
    private void safelyNotifyRunningRecordingService(String action) {
        if (!isRecordingServiceRunning()) {
            FLog.d(TAG, "Skipping lifecycle notification; RecordingService is not running: " + action);
            return;
        }

        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction(action);
        try {
            // Explicitly target the already-running service without creating a new
            // service instance merely to deliver a lifecycle event.
            startService(intent);
        } catch (IllegalStateException e) {
            FLog.w(TAG, "Lifecycle service notification rejected: " + action, e);
        } catch (SecurityException e) {
            FLog.w(TAG, "Lifecycle service notification denied: " + action, e);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Unexpected lifecycle notification failure: " + action, e);
        }
    }

    private boolean isRecordingServiceRunning() {
        try {
            ActivityManager manager =
                    (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            java.util.List<ActivityManager.RunningServiceInfo> services =
                    manager.getRunningServices(Integer.MAX_VALUE);
            String packageName = getPackageName();
            for (ActivityManager.RunningServiceInfo service : services) {
                ComponentName name = service.service;
                if (name != null
                        && packageName.equals(name.getPackageName())
                        && com.fadcam.services.RecordingService.class.getName()
                                .equals(name.getClassName())) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            FLog.w(TAG, "Unable to inspect running services", e);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Running-service inspection failed", e);
        }
        return false;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        try {
            SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Failed to reset AppLock lifecycle state", e);
        }
        safelyNotifyRunningRecordingService("ACTION_APP_BACKGROUND");
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        // Do not inspect or launch activities/services during cold start. If the
        // recording service is already alive, it may receive the notification;
        // otherwise no lifecycle action is required.
        safelyNotifyRunningRecordingService("ACTION_APP_FOREGROUND");
    }
}
