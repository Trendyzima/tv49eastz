package com.tv49east.handoff;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/** Process-local publisher bridge; the protected FadCam server source is untouched. */
public final class FadCamTvHandoffProvider extends ContentProvider {
    private static final String TV_PACKAGE = "com.tv49.com";
    private static final String TV_ACTIVITY = "com.fadcam.tv.FadCamHandoffActivity";
    private static final String KEY_ALIAS = "tv49east_fadcam_handoff_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PREFS = "tv49_handoff_publisher";
    private static final String LAST_URL = "last_url";
    private static final String LAST_SENT = "last_sent";
    private static final String CHANNEL_ID = "tv49_fadcam_broadcast";
    private static final int NOTIFICATION_ID = 4901;
    private static final long POLL_MS = 5000L;
    private static final long RESEND_COOLDOWN_MS = 60_000L;
    private static final long HANDOFF_LIFETIME_MS = 45_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            executor.execute(FadCamTvHandoffProvider.this::pollServer);
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override public boolean onCreate() {
        Context context = getContext();
        if (context != null && isSupportedPublisherPackage(context)) handler.post(poller);
        return true;
    }

    private void pollServer() {
        Context context = getContext();
        if (context == null || stopped || !isSupportedPublisherPackage(context)) return;
        int configuredPort = context.getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE)
                .getInt("stream_server_port", -1);
        if (configuredPort > 0 && tryPublish(context, configuredPort)) return;
        for (int port = 8080; port <= 8089; port++) {
            if (port != configuredPort && tryPublish(context, port)) return;
        }
    }

    private boolean tryPublish(Context context, int port) {
        String ip = findLanIpv4();
        if (ip == null) return false;
        String streamUrl = "http://" + ip + ":" + port + "/live.m3u8";
        if (!isLiveHls(streamUrl)) return false;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lastUrl = prefs.getString(LAST_URL, "");
        long lastSent = prefs.getLong(LAST_SENT, 0L);
        if (streamUrl.equals(lastUrl) && now - lastSent < RESEND_COOLDOWN_MS) return true;
        if (sendSignedHandoff(context, streamUrl, now)) {
            prefs.edit().putString(LAST_URL, streamUrl).putLong(LAST_SENT, now).apply();
            return true;
        }
        return false;
    }

    private boolean isLiveHls(String streamUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(streamUrl).openConnection();
            connection.setConnectTimeout(900);
            connection.setReadTimeout(1200);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return false;
            try (InputStream input = connection.getInputStream()) { return input.read() >= 0; }
        } catch (Exception ignored) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private boolean sendSignedHandoff(Context context, String streamUrl, long issuedAt) {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) generateSigningKey();
            keyStore.load(null);
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            if (entry == null || entry.getPrivateKey() == null || entry.getCertificate() == null) return false;

            long expiresAt = issuedAt + HANDOFF_LIFETIME_MS;
            String nonce = randomNonce();
            String packageName = context.getPackageName();
            String name = "FadCam Local Broadcast";
            String owner = "FadCam";
            String canonical = canonical(1, nonce, issuedAt, expiresAt, packageName, streamUrl, name, owner);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(entry.getPrivateKey());
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));

            Uri uri = new Uri.Builder().scheme("fadcam").authority("stream")
                    .appendQueryParameter("v", "1").appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("iat", Long.toString(issuedAt)).appendQueryParameter("exp", Long.toString(expiresAt))
                    .appendQueryParameter("package", packageName).appendQueryParameter("url", streamUrl)
                    .appendQueryParameter("name", name).appendQueryParameter("owner", owner)
                    .appendQueryParameter("pub", encode(entry.getCertificate().getPublicKey().getEncoded()))
                    .appendQueryParameter("sig", encode(signer.sign())).build();

            Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .setComponent(new ComponentName(TV_PACKAGE, TV_ACTIVITY)).setPackage(TV_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            if (isPublisherForeground(context)) {
                try {
                    context.startActivity(intent);
                    return true;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // Fall through to the notification path.
                }
            }
            return postHandoffNotification(context, intent, streamUrl);
        } catch (Exception ignored) { return false; }
    }

    private boolean postHandoffNotification(Context context, Intent handoffIntent, String streamUrl) {
        if (!canResolveTvActivity(context)) return false;
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "FadCam broadcasts", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Open an active FadCam broadcast in TV 49 East");
                manager.createNotificationChannel(channel);
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, handoffIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new android.app.Notification.Builder(context, CHANNEL_ID)
                    : new android.app.Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("FadCam is broadcasting")
                    .setContentText("Open the live camera stream in TV 49 East")
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(
                            "FadCam is live at " + streamUrl + ". Tap to open it in TV 49 East."))
                    .setContentIntent(pendingIntent).setAutoCancel(true).setOngoing(false);
            manager.notify(NOTIFICATION_ID, builder.build());
            return true;
        } catch (SecurityException ignored) { return false; }
        catch (Exception ignored) { return false; }
    }

    private static boolean canResolveTvActivity(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(TV_PACKAGE, TV_ACTIVITY));
            return context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (Exception ignored) { return false; }
    }

    private static boolean isPublisherForeground(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            int uid = android.os.Process.myUid();
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.uid == uid) {
                    return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                            || process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static void generateSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build());
        generator.generateKeyPair();
    }

    static String canonical(int version, String nonce, long issuedAt, long expiresAt,
                            String packageName, String streamUrl, String name, String owner) {
        return version + "|" + nonce + "|" + issuedAt + "|" + expiresAt + "|"
                + packageName + "|" + streamUrl + "|" + name + "|" + owner;
    }

    private static String randomNonce() {
        byte[] bytes = new byte[18];
        new java.security.SecureRandom().nextBytes(bytes);
        return encode(bytes);
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static boolean isSupportedPublisherPackage(Context context) {
        String packageName = context.getPackageName();
        return "com.fadcam".equals(packageName) || "com.fadcam.beta".equals(packageName);
    }

    private static String findLanIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase();
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("wwan")
                        || name.contains("seth") || name.contains("ndc") || name.contains("tun")
                        || name.contains("tap") || name.contains("wg") || name.contains("ppp")) continue;
                Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    String[] parts = ip.split("\\.");
                    if (parts.length != 4) continue;
                    int a = Integer.parseInt(parts[0]);
                    int b = Integer.parseInt(parts[1]);
                    if (a == 10 || (a == 172 && b >= 16 && b <= 31)
                            || (a == 192 && b == 168) || (a == 169 && b == 254)) return ip;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override public void shutdown() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.shutdown();
    }
}
