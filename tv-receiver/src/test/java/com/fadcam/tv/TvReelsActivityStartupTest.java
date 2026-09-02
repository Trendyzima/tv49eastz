package com.fadcam.tv;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.LooperMode;

/** Regression test for launcher-time Activity construction failures. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35)
public class TvReelsActivityStartupTest {
    @Test
    public void launcherActivityStartsAfterAndroidContextIsAttached() {
        ActivityController<TvReelsActivity> controller = Robolectric.buildActivity(TvReelsActivity.class);
        TvReelsActivity activity = controller.create().start().resume().get();

        assertNotNull(activity);
        assertNotNull(activity.getWindow());
        assertNotNull(activity.findViewById(android.R.id.content));

        controller.pause().stop().destroy();
        ShadowLooper shadowLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        shadowLooper.reset();
    }
}
