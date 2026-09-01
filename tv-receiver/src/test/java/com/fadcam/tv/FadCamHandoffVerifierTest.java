package com.fadcam.tv;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public final class FadCamHandoffVerifierTest {
    @Test
    public void rejectsWrongScheme() {
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(null,
                Uri.parse("tv49east://channel?url=https%3A%2F%2Fexample.com%2Flive.m3u8"));
        assertFalse(result.accepted);
    }

    @Test
    public void rejectsUnsignedFadCamUri() {
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(null,
                Uri.parse("fadcam://stream?url=https%3A%2F%2Fexample.com%2Flive.m3u8"));
        assertFalse(result.accepted);
    }

    @Test
    public void rejectsUnexpectedPublisherPackage() {
        String uri = "fadcam://stream?v=1&nonce=n&iat=1&exp=9999999999999"
                + "&package=com.attacker&name=x&owner=x&url=https%3A%2F%2Fexample.com%2Flive.m3u8"
                + "&pub=AA&sig=AA";
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(null, Uri.parse(uri));
        assertFalse(result.accepted);
    }

    @Test
    public void rejectsPlainHttpStream() {
        String uri = "fadcam://stream?v=1&nonce=n&iat=1&exp=9999999999999"
                + "&package=com.fadcam&name=x&owner=x&url=http%3A%2F%2Fexample.com%2Flive.m3u8"
                + "&pub=AA&sig=AA";
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(null, Uri.parse(uri));
        assertFalse(result.accepted);
    }

    @Test
    public void canonicalContractIsVersionedAndStable() {
        String canonical = FadCamHandoffVerifier.canonical(1, "nonce", 1000L, 2000L,
                "com.fadcam", "https://example.com/live.m3u8", "Demo", "FadCam");
        assertTrue(canonical.startsWith("1|nonce|1000|2000|com.fadcam|"));
        assertTrue(canonical.endsWith("|Demo|FadCam"));
    }
}
