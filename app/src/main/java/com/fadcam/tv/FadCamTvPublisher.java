package com.fadcam.tv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.UUID;

/**
 * Single integration point for publishing a FadCam stream to the TV receiver.
 * The receiver is protected by a signature permission; the handoff payload is
 * additionally signed by a per-install Android Keystore key.
 */
public final class FadCamTvPublisher {
    public static final String RECEIVER_PACKAGE = "com.tv49.com";
    public static final String ACTION_HANDOFF = Intent.ACTION_VIEW;
    public static final String SCHEME = "fadcam";
    public static final String HOST = "stream";
    public static final String PERMISSION = "com.tv49.com.permission.PUBLISH_FADCAM";
    public static final int PROTOCOL_VERSION = 1;
    private static final String KEY_ALIAS = "tv49east-fadcam-handoff-v1";
    private static final long TTL_MS = 60_000L;

    private FadCamTvPublisher() {}

    public static boolean publish(Context context, String streamUrl, String name, String owner) {
        if (context == null || !isHttps(streamUrl)) return false;
        try {
            ensureKey();
            long issuedAt = System.currentTimeMillis();
            long expiresAt = issuedAt + TTL_MS;
            String nonce = UUID.randomUUID().toString();
            String packageName = context.getPackageName();
            String canonical = canonical(PROTOCOL_VERSION, nonce, issuedAt, expiresAt,
                    packageName, streamUrl, safe(name), safe(owner));

            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            PrivateKey privateKey = (PrivateKey) ks.getKey(KEY_ALIAS, null);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            String signature = b64(signer.sign());
            Certificate cert = ks.getCertificate(KEY_ALIAS);
            String publicKey = b64(cert.getPublicKey().getEncoded());

            Uri uri = Uri.parse("fadcam://stream")
                    .buildUpon()
                    .appendQueryParameter("v", Integer.toString(PROTOCOL_VERSION))
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("iat", Long.toString(issuedAt))
                    .appendQueryParameter("exp", Long.toString(expiresAt))
                    .appendQueryParameter("package", packageName)
                    .appendQueryParameter("name", safe(name))
                    .appendQueryParameter("owner", safe(owner))
                    .appendQueryParameter("url", streamUrl)
                    .appendQueryParameter("pub", publicKey)
                    .appendQueryParameter("sig", signature)
                    .build();

            Intent intent = new Intent(ACTION_HANDOFF, uri);
            intent.setPackage(RECEIVER_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String canonical(int version, String nonce, long issuedAt, long expiresAt,
                                   String packageName, String streamUrl, String name, String owner) {
        return version + "|" + nonce + "|" + issuedAt + "|" + expiresAt + "|"
                + packageName + "|" + streamUrl + "|" + name + "|" + owner;
    }

    private static void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return;
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        generator.generateKeyPair();
    }

    private static boolean isHttps(String value) {
        try {
            Uri uri = Uri.parse(value == null ? "" : value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
