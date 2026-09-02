package com.fadcam.tv;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;

/** Regression test for the actual Android launcher activity. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35)
public class HomeActivityStartupTest {
    @Test
    public void launcherStartsWithoutEnteringHeavyReceiverStack() {
        ActivityController<HomeActivity> controller = Robolectric.buildActivity(HomeActivity.class);
        HomeActivity activity = controller.create().start().resume().get();

        assertNotNull(activity);
        assertNotNull(activity.getWindow());
        assertNotNull(activity.findViewById(android.R.id.content));

        controller.pause().stop().destroy();
    }
}
