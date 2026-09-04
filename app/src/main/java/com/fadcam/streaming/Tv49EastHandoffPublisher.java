package com.fadcam.streaming;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.fadcam.FLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * Publishes the running FadCam local HLS endpoint to the TV 49 East receiver.
 *
 * The receiver accepts only a short-lived, nonce-protected ECDSA handoff and
 * separately verifies that the publisher APK is signed with the same Android
 * signing certificate as the receiver APK. The private key never leaves the
 * Android Keystore.
 */
public final class Tv49EastHandoffPublisher {
    private static final String TAG = "Tv49EastHandoff";
    private static final String KEY_ALIAS = "tv49east_fadcam_handoff_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String RECEIVER_PACKAGE = "com.tv49.com";
    private static final String RECEIVER_ACTIVITY = "com.fadcam.tv.FadCamHandoffActivity";
    private static final String PUBLISH_PERMISSION = "com.tv49.com.permission.PUBLISH_FADCAM";
    private static final String PACKAGE_NAME = "com.fadcam";
    private static final String SCHEME = "fadcam";
    private static final String HOST = "stream";
    private static final long HANDOFF_LIFETIME_MS = 45_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tv49EastHandoffPublisher() {}

    /**
     * Send the current local HLS URL to the installed TV 49 East receiver.
     * Returns false when the receiver is not installed or the handoff cannot
     * be created/sent; streaming itself is never stopped because of a failure.
     */
    public static boolean publish(Context context, String streamUrl) {
        if (context == null || !isAllowedLocalHlsUrl(streamUrl)) {
            return false;
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateSigningKey();
                keyStore.load(null);
            }

            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            if (entry == null || entry.getPrivateKey() == null || entry.getCertificate() == null) {
                FLog.e(TAG, "Unable to load handoff signing key");
                return false;
            }

            long issuedAt = System.currentTimeMillis();
            long expiresAt = issuedAt + HANDOFF_LIFETIME_MS;
            String nonce = randomNonce();
            String name = "FadCam Local Broadcast";
            String owner = "FadCam";
            String canonical = canonical(1, nonce, issuedAt, expiresAt, PACKAGE_NAME,
                    streamUrl, name, owner);

            byte[] signatureBytes = sign(entry.getPrivateKey(), canonical);
            String publicKey = encode(entry.getCertificate().getPublicKey().getEncoded());
            String signature = encode(signatureBytes);

            Uri handoffUri = new Uri.Builder()
                    .scheme(SCHEME)
                    .authority(HOST)
                    .appendQueryParameter("v", "1")
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("iat", Long.toString(issuedAt))
                    .appendQueryParameter("exp", Long.toString(expiresAt))
                    .appendQueryParameter("package", PACKAGE_NAME)
                    .appendQueryParameter("url", streamUrl)
                    .appendQueryParameter("name", name)
                    .appendQueryParameter("owner", owner)
                    .appendQueryParameter("pub", publicKey)
                    .appendQueryParameter("sig", signature)
                    .build();

            Intent intent = new Intent(Intent.ACTION_VIEW, handoffUri);
            intent.setComponent(new ComponentName(RECEIVER_PACKAGE, RECEIVER_ACTIVITY));
            intent.setPackage(RECEIVER_PACKAGE);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            context.startActivity(intent);
            FLog.i(TAG, "Signed local HLS handoff sent to TV 49 East: " + streamUrl);
            return true;
        } catch (ActivityNotFoundException e) {
            FLog.d(TAG, "TV 49 East receiver is not installed");
            return false;
        } catch (SecurityException e) {
            FLog.e(TAG, "TV 49 East receiver rejected the handoff permission", e);
            return false;
        } catch (Exception e) {
            FLog.e(TAG, "Unable to publish TV 49 East handoff", e);
            return false;
        }
    }

    private static void generateSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        generator.generateKeyPair();
    }

    private static byte[] sign(PrivateKey privateKey, String canonical) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        return signer.sign();
    }

    private static String canonical(int version, String nonce, long issuedAt, long expiresAt,
                                    String packageName, String streamUrl, String name, String owner) {
        return version + "|" + nonce + "|" + issuedAt + "|" + expiresAt + "|"
                + packageName + "|" + streamUrl + "|" + name + "|" + owner;
    }

    private static String randomNonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** Only loopback/private IPv4 HTTP endpoints are eligible for local handoff. */
    private static boolean isAllowedLocalHlsUrl(String value) {
        try {
            Uri uri = Uri.parse(value == null ? "" : value.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
            if (!uri.getPath().endsWith("/live.m3u8") && !uri.getPath().endsWith("/stream.m3u8")) return false;

            String[] octets = host.split("\\.");
            int a = Integer.parseInt(octets[0]);
            int b = Integer.parseInt(octets[1]);
            int c = Integer.parseInt(octets[2]);
            int d = Integer.parseInt(octets[3]);
            if (a > 255 || b > 255 || c > 255 || d > 255) return false;

            return a == 127
                    || a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        } catch (Exception ignored) {
            return false;
        }
    }
}
