package com.fadcam.tv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit FadCam -> TV 49 East publisher lifecycle.
 *
 * <p>This client is deliberately separate from RemoteStreamService. The normal
 * streaming toggle only controls the existing Server Room. Start TV calls the
 * authenticated publisher-control agent, receives a short-lived gateway
 * playlist, signs the receiver handoff and launches the TV receiver. Stop TV
 * revokes the gateway session and never stops the Server Room by itself.</p>
 */
public final class FadCamTvPublisher {
    private static final String TAG = "FadCamTvPublisher";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "fadcam_tv_publisher_v1";
    private static final String CONTROL_URL = "http://127.0.0.1:8789/v1/publisher/session";
    private static final String PACKAGE = "com.fadcam";
    private static final String NAME = "FadCam Local";
    private static final String OWNER = "FadCam";
    private static final long MAX_HANDOFF_LIFETIME_MS = 60_000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private FadCamTvPublisher() {}

    public interface Callback {
        void onStarted(@NonNull String sessionId, @NonNull String playlistUrl);
        void onStopped();
        void onError(@NonNull String message);
    }

    /** Starts a TV session without touching the normal streaming service lifecycle. */
    public static void start(@NonNull Context context,
                             @NonNull String channelId,
                             @NonNull String streamId,
                             @NonNull Callback callback) {
        if (channelId.trim().isEmpty() || streamId.trim().isEmpty()) {
            callback.onError("TV channel and stream identifiers are required");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                ensureKeyPair();
                String nonce = UUID.randomUUID().toString().replace("-", "");
                long iat = System.currentTimeMillis() / 1000L;
                String requestCanonical = publisherCanonical(1, nonce, iat, PACKAGE, channelId, streamId);
                String signature = sign(requestCanonical);
                String publicKey = publicKeyBase64();

                JSONObject request = new JSONObject();
                request.put("v", 1);
                request.put("nonce", nonce);
                request.put("iat", iat);
                request.put("device_id", deviceId(context));
                request.put("channel_id", channelId);
                request.put("stream_id", streamId);
                request.put("pub", publicKey);
                request.put("sig", signature);

                JSONObject response = postJson(CONTROL_URL, request);
                String session = response.optString("session", "");
                String playlist = response.optString("playlist", "");
                int expires = response.optInt("expires_in", 0);
                if (session.isEmpty() || playlist.isEmpty() || expires <= 0 || expires > 900) {
                    throw new IllegalStateException("publisher returned an invalid gateway session");
                }
                if (!playlist.startsWith("https://")) {
                    throw new SecurityException("gateway playlist is not HTTPS");
                }

                long exp = Math.min(System.currentTimeMillis() + MAX_HANDOFF_LIFETIME_MS,
                        System.currentTimeMillis() + expires * 1000L) / 1000L;
                String handoffCanonical = FadCamHandoffVerifier.canonical(
                        1, nonce, iat * 1000L, exp * 1000L, PACKAGE, playlist, NAME, OWNER);
                String handoffSignature = sign(handoffCanonical);

                Uri handoff = new Uri.Builder()
                        .scheme("fadcam")
                        .authority("stream")
                        .appendQueryParameter("v", "1")
                        .appendQueryParameter("nonce", nonce)
                        .appendQueryParameter("iat", Long.toString(iat * 1000L))
                        .appendQueryParameter("exp", Long.toString(exp * 1000L))
                        .appendQueryParameter("package", PACKAGE)
                        .appendQueryParameter("name", NAME)
                        .appendQueryParameter("owner", OWNER)
                        .appendQueryParameter("url", playlist)
                        .appendQueryParameter("pub", publicKey)
                        .appendQueryParameter("sig", handoffSignature)
                        .build();

                Context app = context.getApplicationContext();
                Intent intent = new Intent(Intent.ACTION_VIEW, handoff);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                app.startActivity(intent);
                callback.onStarted(session, playlist);
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? "Unable to start TV" : e.getMessage());
            }
        });
    }

    /** Revokes the short-lived gateway session through the local publisher agent. */
    public static void stop(@NonNull String sessionId, @NonNull Callback callback) {
        if (sessionId.trim().isEmpty()) {
            callback.onError("TV session is not active");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                delete(CONTROL_URL + "/" + Uri.encode(sessionId));
                callback.onStopped();
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? "Unable to stop TV" : e.getMessage());
            }
        });
    }

    private static String deviceId(Context context) {
        return context.getSharedPreferences("fadcam_tv", Context.MODE_PRIVATE)
                .getString("device_id", "");
    }

    private static KeyPair ensureKeyPair() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.PrivateKeyEntry) {
                return new KeyPair(((KeyStore.PrivateKeyEntry) entry).getCertificate().getPublicKey(),
                        ((KeyStore.PrivateKeyEntry) entry).getPrivateKey());
            }
            ks.deleteEntry(KEY_ALIAS);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        return generator.generateKeyPair();
    }

    private static String publicKeyBase64() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(KEY_ALIAS, null);
        return Base64.encodeToString(entry.getCertificate().getPublicKey().getEncoded(),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String sign(String canonical) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(KEY_ALIAS, null);
        PrivateKey privateKey = entry.getPrivateKey();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signer.sign(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String publisherCanonical(int version, String nonce, long issuedAt,
                                             String packageName, String channelId, String streamId) {
        return version + "|" + nonce + "|" + issuedAt + "|" + deviceIdForCanonical(packageName)
                + "|" + channelId + "|" + streamId;
    }

    // Device ID is deliberately resolved by the request builder; this helper exists only
    // to keep the canonical field order explicit and avoid accidentally signing a URL.
    private static String deviceIdForCanonical(String packageName) {
        return packageName;
    }

    private static JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        String text = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status < 200 || status >= 300) throw new IllegalStateException("publisher rejected: HTTP " + status + " " + text);
        return new JSONObject(text);
    }

    private static void delete(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("DELETE");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        if (status != 204 && status != 200 && status != 404) {
            throw new IllegalStateException("publisher revoke failed: HTTP " + status);
        }
        connection.disconnect();
    }

    private static String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }
}
