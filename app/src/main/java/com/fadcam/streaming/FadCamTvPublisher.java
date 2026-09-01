package com.fadcam.streaming;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * FadCam-side publisher control for the TV handoff.
 *
 * The app never receives the gateway API key. It signs a short-lived request
 * with an Android Keystore EC key; the co-located device-tunnel publisher
 * control verifies that key and uses its own mTLS/API credentials to create
 * the gateway session.
 */
public final class FadCamTvPublisher {
    private static final String TAG = "FadCamTvPublisher";
    private static final String KEY_ALIAS = "tv49_fadcam_publisher_v1";
    private static final String CONTROL_ORIGIN = "http://127.0.0.1:8789";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private FadCamTvPublisher() {}

    /** Explicit Start TV entry point. Throws if the publisher boundary is unavailable. */
    public static String startTv(@NonNull Context context, @NonNull String deviceId,
                                 @NonNull String channelId, @NonNull String streamId,
                                 String name, String owner) throws Exception {
        String playlist = requestGatewaySession(deviceId, channelId, streamId);
        return buildHandoffUri(playlist, name == null ? "FadCam Local" : name,
                owner == null ? "FadCam" : owner).toString();
    }

    /** Explicit Stop TV entry point. The gateway revocation is immediate. */
    public static void stopTv(@NonNull String sessionId) throws Exception {
        if (sessionId == null || sessionId.trim().isEmpty() || sessionId.contains("/")) {
            throw new IllegalArgumentException("invalid session id");
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(CONTROL_ORIGIN + "/v1/publisher/session/" + Uri.encode(sessionId));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_NO_CONTENT && status != HttpURLConnection.HTTP_OK
                    && status != HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException("publisher revoke failed: HTTP " + status);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Public key enrollment material. Provision this exact value into the tunnel agent. */
    public static String getPublisherPublicKeyBase64(@NonNull Context context) throws Exception {
        ensureKey(context);
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        byte[] encoded = keyStore.getCertificate(KEY_ALIAS).getPublicKey().getEncoded();
        return Base64.encodeToString(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String requestGatewaySession(String deviceId, String channelId, String streamId) throws Exception {
        if (deviceId == null || deviceId.trim().isEmpty() || channelId == null || channelId.trim().isEmpty()
                || streamId == null || streamId.trim().isEmpty()) {
            throw new IllegalArgumentException("publisher identity and stream are required");
        }
        long issuedAt = System.currentTimeMillis() / 1000L;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String publicKey = Base64.encodeToString(loadPublicKey().getEncoded(),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String canonical = "1|" + nonce + "|" + issuedAt + "|" + deviceId + "|" + channelId + "|" + streamId;
        byte[] signature = sign(canonical.getBytes(StandardCharsets.UTF_8));
        String body = "{\"v\":1,\"nonce\":\"" + json(nonce) + "\",\"iat\":" + issuedAt
                + ",\"device_id\":\"" + json(deviceId) + "\",\"channel_id\":\"" + json(channelId)
                + "\",\"stream_id\":\"" + json(streamId) + "\",\"pub\":\"" + publicKey
                + "\",\"sig\":\"" + Base64.encodeToString(signature, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING) + "\"}";

        HttpURLConnection connection = null;
        try {
            URL url = new URL(CONTROL_ORIGIN + "/v1/publisher/session");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readBounded(input);
            if (status < 200 || status >= 300) {
                throw new IOException("publisher session rejected: HTTP " + status + " " + response);
            }
            String playlist = jsonString(response, "playlist");
            String session = jsonString(response, "session");
            if (session == null || session.isEmpty() || playlist == null || playlist.isEmpty()) {
                throw new IOException("publisher returned incomplete session");
            }
            if (!playlist.startsWith("https://")) {
                throw new IOException("publisher returned non-HTTPS playlist");
            }
            FLog.i(TAG, "TV session created: " + session);
            return playlist;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Uri buildHandoffUri(String playlist, String name, String owner) throws Exception {
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + 60_000L;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String packageName = "com.fadcam";
        String canonical = "1|" + nonce + "|" + issuedAt + "|" + expiresAt + "|" + packageName
                + "|" + playlist + "|" + name + "|" + owner;
        String signature = Base64.encodeToString(sign(canonical.getBytes(StandardCharsets.UTF_8)),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String pub = Base64.encodeToString(loadPublicKey().getEncoded(),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new Uri.Builder().scheme("fadcam").authority("stream")
                .appendQueryParameter("v", "1")
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("iat", Long.toString(issuedAt))
                .appendQueryParameter("exp", Long.toString(expiresAt))
                .appendQueryParameter("package", packageName)
                .appendQueryParameter("name", name)
                .appendQueryParameter("owner", owner)
                .appendQueryParameter("url", playlist)
                .appendQueryParameter("pub", pub)
                .appendQueryParameter("sig", signature)
                .build();
    }

    private static void ensureKey(Context context) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return;
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        generator.generateKeyPair();
    }

    private static java.security.PublicKey loadPublicKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) throw new IllegalStateException("publisher key is not enrolled");
        return keyStore.getCertificate(KEY_ALIAS).getPublicKey();
    }

    private static byte[] sign(byte[] data) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
        if (privateKey == null) throw new IllegalStateException("publisher key is not available");
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    private static String readBounded(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) throw new IOException("publisher response too large");
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String jsonString(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '\"') return null;
        start++;
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { out.append(c); escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '\"') return out.toString();
            out.append(c);
        }
        return null;
    }
}
