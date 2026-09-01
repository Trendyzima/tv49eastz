package com.fadcam.tv;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

/**
 * Regression test for the installed-APK startup crash: MainActivity is an
 * AppCompatActivity and therefore must launch with an AppCompat-compatible theme.
 *
 * Robolectric is explicitly pinned to API 35 here because this project currently
 * uses a Robolectric release whose bundled Android resource/runtime support is
 * not guaranteed for the app's API-36 target. The production APK remains
 * targetSdk 36; this test deliberately validates the Android resource path on
 * the highest stable SDK supported by the test harness.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MainActivityStartupTest {
    @Test
    public void mainActivityStartsWithoutThemeCrash() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.create().start().resume().get();
        assertNotNull(activity.findViewById(android.R.id.content));
        controller.pause().stop().destroy();
    }
}
