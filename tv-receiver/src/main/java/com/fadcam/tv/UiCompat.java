package com.fadcam.tv;

import android.content.Context;

/** Shared UI conversion helpers. */
public final class UiCompat {
    private UiCompat() {}
    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
