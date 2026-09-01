package com.fadcam.tv;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.core.app.ActivityScenario;

import org.robolectric.RobolectricTestRunner;

/**
 * Regression test for the installed-APK startup crash: MainActivity is an
 * AppCompatActivity and therefore must launch with an AppCompat-compatible theme.
 */
@RunWith(RobolectricTestRunner.class)
public class MainActivityStartupTest {
    @Test
    public void mainActivityStartsWithoutThemeCrash() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity((Activity activity) -> assertNotNull(activity.findViewById(android.R.id.content)));
        }
    }
}
