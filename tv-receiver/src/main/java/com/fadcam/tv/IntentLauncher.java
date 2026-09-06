package com.fadcam.tv;

import android.app.Activity;
import android.content.Intent;

/** Navigation helper with state-preserving TV/social handoff. */
final class IntentLauncher {
    private IntentLauncher() { }

    static void open(Activity activity, Class<?> destination, boolean finishCurrent) {
        if (activity == null || destination == null || activity.isFinishing()) return;
        Intent intent = new Intent(activity, destination);
        if (destination == SocialActivity.class || destination == SocialParityActivity.class || destination == MainActivity.class) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        activity.startActivity(intent);
        activity.overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
        // TV and Social are two persistent sibling surfaces. Do not destroy either one when switching modes.
        if (finishCurrent && destination != SocialActivity.class && destination != SocialParityActivity.class && destination != MainActivity.class) {
            activity.finish();
        }
    }
}
