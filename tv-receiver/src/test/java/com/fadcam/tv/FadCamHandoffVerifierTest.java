package com.fadcam.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Base64;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public final class FadCamHandoffVerifierTest {
    private static final long NOW = System.currentTimeMillis();

    @Test
    public void acceptsValidSignedHandoff() throws Exception {
        TestFixture fixture = fixture("valid");
        FadCamHandoffVerifier.Result result = FadCamHandoffVerifier.verify(fixture.context, fixture.uri);
        assertTrue(result.accepted);
        assertTrue(result.streamUrl.startsWith("http://192.168.1.20:8080/"));
    }

    @Test
    public void rejectsExpiredHandoff() throws Exception {
        TestFixture fixture = fixture("expired");
        fixture = fixtureWithTimes(fixture, NOW - 120_000L, NOW - 60_000L);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
    }

    @Test
    public void rejectsFutureTimestamp() throws Exception {
        TestFixture fixture = fixtureWithTimes(fixture("future"), NOW + 31_000L, NOW + 60_000L);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
    }

    @Test
    public void rejectsLifetimeOverOneMinute() throws Exception {
        TestFixture fixture = fixtureWithTimes(fixture("long"), NOW, NOW + 60_001L);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
    }

    @Test
    public void rejectsWrongPackage() throws Exception {
        TestFixture fixture = fixture("wrong-package");
        fixture = signedFixture(fixture.context, fixture.keyPair, "com.attacker", fixture.streamUrl,
                fixture.name, fixture.owner, NOW, NOW + 45_000L, fixture.nonce);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
    }

    @Test
    public void rejectsWrongSignature() throws Exception {
        TestFixture fixture = fixture("wrong-signature");
        String raw = fixture.uri.getQueryParameter("sig");
        String mutated = raw.substring(0, raw.length() - 1) + (raw.endsWith("A") ? "B" : "A");
        Uri uri = fixture.uri.buildUpon().clearQuery().build();
        uri = signedUriWithSignature(fixture, mutated);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, uri).accepted);
    }

    @Test
    public void rejectsWrongStreamOrigin() throws Exception {
        TestFixture fixture = fixture("origin");
        fixture = signedFixture(fixture.context, fixture.keyPair, "com.fadcam", "http://example.com/live.m3u8",
                fixture.name, fixture.owner, NOW, NOW + 45_000L, fixture.nonce);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
    }

    @Test
    public void rejectsReplayedNonce() throws Exception {
        TestFixture fixture = fixture("replay");
        assertTrue(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, fixture.uri).accepted);
    }

    @Test
    public void rejectsModifiedUrl() throws Exception {
        TestFixture fixture = fixture("modified-url");
        Uri modified = fixture.uri.buildUpon().appendQueryParameter("url", "http://192.168.1.99:8080/live.m3u8").build();
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, modified).accepted);
    }

    @Test
    public void rejectsModifiedMetadata() throws Exception {
        TestFixture fixture = fixture("modified-metadata");
        Uri modified = fixture.uri.buildUpon().appendQueryParameter("name", "Attacker").build();
        assertFalse(FadCamHandoffVerifier.verify(fixture.context, modified).accepted);
    }

    @Test
    public void rejectsWrongScheme() {
        assertFalse(FadCamHandoffVerifier.verify(null,
                Uri.parse("tv49east://channel?url=https%3A%2F%2Fexample.com%2Flive.m3u8")).accepted);
    }

    @Test
    public void rejectsUnsignedFadCamUri() {
        assertFalse(FadCamHandoffVerifier.verify(null,
                Uri.parse("fadcam://stream?url=https%3A%2F%2Fexample.com%2Flive.m3u8")).accepted);
    }

    @Test
    public void canonicalContractIsVersionedAndStable() {
        String canonical = FadCamHandoffVerifier.canonical(1, "nonce", 1000L, 2000L,
                "com.fadcam", "https://example.com/live.m3u8", "Demo", "FadCam");
        assertTrue(canonical.startsWith("1|nonce|1000|2000|com.fadcam|"));
        assertTrue(canonical.endsWith("|Demo|FadCam"));
    }

    private static TestFixture fixture(String nonce) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keyPair = generator.generateKeyPair();
        Context context = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(context.getPackageName()).thenReturn("com.tv49.com");
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.checkSignatures("com.tv49.com", "com.fadcam"))
                .thenReturn(PackageManager.SIGNATURE_MATCH);
        when(packageManager.checkSignatures("com.tv49.com", "com.fadcam.beta"))
                .thenReturn(PackageManager.SIGNATURE_MATCH);
        when(context.getSharedPreferences(any(String.class), any(Integer.TYPE))).thenReturn(preferences);
        when(preferences.getStringSet(any(String.class), any())).thenReturn(null);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(any(String.class), any())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        return signedFixture(context, keyPair, "com.fadcam", "http://192.168.1.20:8080/live.m3u8",
                "Demo FadCam", "FadCam", NOW, NOW + 45_000L, nonce);
    }

    private static TestFixture fixtureWithTimes(TestFixture base, long issuedAt, long expiresAt) throws Exception {
        return signedFixture(base.context, base.keyPair, "com.fadcam", base.streamUrl,
                base.name, base.owner, issuedAt, expiresAt, base.nonce + "-times");
    }

    private static TestFixture signedFixture(Context context, KeyPair keyPair, String packageName,
                                             String streamUrl, String name, String owner,
                                             long issuedAt, long expiresAt, String nonce) throws Exception {
        String canonical = FadCamHandoffVerifier.canonical(1, nonce, issuedAt, expiresAt,
                packageName, streamUrl, name, owner);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        String pub = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String sig = Base64.encodeToString(signer.sign(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        Uri uri = new Uri.Builder().scheme("fadcam").authority("stream")
                .appendQueryParameter("v", "1").appendQueryParameter("nonce", nonce)
                .appendQueryParameter("iat", Long.toString(issuedAt)).appendQueryParameter("exp", Long.toString(expiresAt))
                .appendQueryParameter("package", packageName).appendQueryParameter("url", streamUrl)
                .appendQueryParameter("name", name).appendQueryParameter("owner", owner)
                .appendQueryParameter("pub", pub).appendQueryParameter("sig", sig).build();
        return new TestFixture(context, keyPair, uri, nonce, streamUrl, name, owner);
    }

    private static Uri signedUriWithSignature(TestFixture fixture, String signature) {
        return new Uri.Builder().scheme("fadcam").authority("stream")
                .appendQueryParameter("v", fixture.uri.getQueryParameter("v"))
                .appendQueryParameter("nonce", fixture.uri.getQueryParameter("nonce"))
                .appendQueryParameter("iat", fixture.uri.getQueryParameter("iat"))
                .appendQueryParameter("exp", fixture.uri.getQueryParameter("exp"))
                .appendQueryParameter("package", fixture.uri.getQueryParameter("package"))
                .appendQueryParameter("url", fixture.uri.getQueryParameter("url"))
                .appendQueryParameter("name", fixture.uri.getQueryParameter("name"))
                .appendQueryParameter("owner", fixture.uri.getQueryParameter("owner"))
                .appendQueryParameter("pub", fixture.uri.getQueryParameter("pub"))
                .appendQueryParameter("sig", signature).build();
    }

    private static final class TestFixture {
        final Context context;
        final KeyPair keyPair;
        final Uri uri;
        final String nonce;
        final String streamUrl;
        final String name;
        final String owner;

        TestFixture(Context context, KeyPair keyPair, Uri uri, String nonce,
                    String streamUrl, String name, String owner) {
            this.context = context;
            this.keyPair = keyPair;
            this.uri = uri;
            this.nonce = nonce;
            this.streamUrl = streamUrl;
            this.name = name;
            this.owner = owner;
        }
    }
}
