package com.fadcam.tv;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;

/**
 * Regression test for the installed-APK startup crash: MainActivity is an
 * AppCompatActivity and therefore must launch with an AppCompat-compatible theme.
 */
@RunWith(RobolectricTestRunner.class)
public class MainActivityStartupTest {
    @Test
    public void mainActivityStartsWithoutThemeCrash() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.create().start().resume().visible().get();
        assertNotNull(activity.findViewById(android.R.id.content));
        controller.pause().stop().destroy();
    }
}
