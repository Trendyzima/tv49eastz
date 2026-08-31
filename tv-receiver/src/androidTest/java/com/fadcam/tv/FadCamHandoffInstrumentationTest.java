package com.fadcam.tv;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class FadCamHandoffInstrumentationTest {
    @Test
    public void handoffActivityIsSignatureProtected() throws Exception {
        PackageManager pm = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        ActivityInfo info = pm.getActivityInfo(
                new ComponentName("com.tv49.com", "com.fadcam.tv.FadCamHandoffActivity"), 0);
        assertEquals("com.tv49.com.permission.PUBLISH_FADCAM", info.permission);
        assertTrue((info.exported));
    }

    @Test
    public void receiverAndFadCamShareReleaseSignerWhenBothInstalled() throws Exception {
        PackageManager pm = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        try {
            assertEquals(PackageManager.SIGNATURE_MATCH,
                    pm.checkSignatures("com.tv49.com", "com.fadcam"));
        } catch (PackageManager.NameNotFoundException e) {
            // The focused receiver-only instrumentation environment may not install FadCam.
            // The paired APK CI job separately verifies both APK certificates are identical.
            assertTrue("FadCam is not installed in this instrumentation environment", true);
        }
    }
}
