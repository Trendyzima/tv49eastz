package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;

/** Small navigation helper that keeps activity transitions consistent and fail-safe. */
final class IntentLauncher {
    private IntentLauncher() { }

    static void open(Activity activity, Class<?> destination, boolean finishCurrent) {
        if (activity == null || destination == null || activity.isFinishing()) return;
        activity.startActivity(new Intent(activity, destination));
        activity.overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
        if (finishCurrent) activity.finish();
    }
}
