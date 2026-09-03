package com.fadcam.tv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;

/** Validates the signed, short-lived FadCam -> TV handoff contract. */
public final class FadCamHandoffVerifier {
    private static final long MAX_CLOCK_SKEW_MS = 30_000L;
    private static final long MAX_LIFETIME_MS = 60_000L;
    private static final int MAX_TRACKED_NONCES = 1024;
    private static final String NONCE_PREFS = "tv49_fadcam_handoff";
    private static final String USED_NONCES_KEY = "used_nonces";
    private static final Set<String> USED_NONCES = new HashSet<>();
    private static final Object LOCK = new Object();
    private static boolean noncesLoaded = false;

    private FadCamHandoffVerifier() {}

    public static Result verify(Context context, Uri uri) {
        if (context == null) return Result.reject("missing context");
        if (uri == null) return Result.reject("missing uri");
        if (!"fadcam".equalsIgnoreCase(uri.getScheme()) || !"stream".equalsIgnoreCase(uri.getHost())) {
            return Result.reject("wrong scheme");
        }

        String version = uri.getQueryParameter("v");
        String nonce = uri.getQueryParameter("nonce");
        String iatRaw = uri.getQueryParameter("iat");
        String expRaw = uri.getQueryParameter("exp");
        String packageName = uri.getQueryParameter("package");
        String name = uri.getQueryParameter("name");
        String owner = uri.getQueryParameter("owner");
        String streamUrl = uri.getQueryParameter("url");
        String publicKeyRaw = uri.getQueryParameter("pub");
        String signatureRaw = uri.getQueryParameter("sig");

        if (!"1".equals(version) || empty(nonce) || empty(packageName) || empty(streamUrl)
                || empty(publicKeyRaw) || empty(signatureRaw) || empty(iatRaw) || empty(expRaw)) {
            return Result.reject("invalid contract");
        }
        if (nonce.length() > 128) return Result.reject("invalid nonce");
        if (!"com.fadcam".equals(packageName) && !"com.fadcam.beta".equals(packageName)) {
            return Result.reject("unexpected publisher package");
        }
        if (!isAllowedStream(streamUrl)) return Result.reject("stream origin is not allowed");

        long issuedAt;
        long expiresAt;
        try {
            issuedAt = Long.parseLong(iatRaw);
            expiresAt = Long.parseLong(expRaw);
        } catch (NumberFormatException e) {
            return Result.reject("invalid timestamps");
        }

        long now = System.currentTimeMillis();
        if (issuedAt > now + MAX_CLOCK_SKEW_MS || expiresAt <= now
                || expiresAt <= issuedAt || expiresAt - issuedAt > MAX_LIFETIME_MS) {
            return Result.reject("expired or invalid lifetime");
        }
        if (!isSameSigningCertificate(context, packageName)) {
            return Result.reject("publisher signature mismatch");
        }

        synchronized (LOCK) {
            loadNoncesLocked(context);
            if (USED_NONCES.contains(nonce)) return Result.reject("replayed nonce");
        }

        try {
            byte[] publicKeyBytes = Base64.decode(publicKeyRaw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            byte[] signatureBytes = Base64.decode(signatureRaw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            String canonical = canonical(1, nonce, issuedAt, expiresAt, packageName, streamUrl,
                    name == null ? "" : name, owner == null ? "" : owner);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) return Result.reject("invalid signature");

            synchronized (LOCK) {
                if (USED_NONCES.contains(nonce)) return Result.reject("replayed nonce");
                USED_NONCES.add(nonce);
                trimNoncesLocked();
                if (!persistNoncesLocked(context)) {
                    USED_NONCES.remove(nonce);
                    return Result.reject("nonce persistence failed");
                }
            }
            return Result.accept(streamUrl, name == null ? "FadCam Local" : name,
                    owner == null ? "FadCam" : owner);
        } catch (Exception e) {
            return Result.reject("signature validation failed");
        }
    }

    static String canonical(int version, String nonce, long issuedAt, long expiresAt,
                            String packageName, String streamUrl, String name, String owner) {
        return version + "|" + nonce + "|" + issuedAt + "|" + expiresAt + "|"
                + packageName + "|" + streamUrl + "|" + name + "|" + owner;
    }

    private static void loadNoncesLocked(Context context) {
        if (noncesLoaded) return;
        Set<String> stored = context.getSharedPreferences(NONCE_PREFS, Context.MODE_PRIVATE)
                .getStringSet(USED_NONCES_KEY, null);
        USED_NONCES.clear();
        if (stored != null) USED_NONCES.addAll(stored);
        trimNoncesLocked();
        noncesLoaded = true;
    }

    private static boolean persistNoncesLocked(Context context) {
        return context.getSharedPreferences(NONCE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(USED_NONCES_KEY, new HashSet<>(USED_NONCES))
                .commit();
    }

    private static void trimNoncesLocked() {
        while (USED_NONCES.size() > MAX_TRACKED_NONCES) {
            String first = USED_NONCES.iterator().next();
            USED_NONCES.remove(first);
        }
    }

    private static boolean isSameSigningCertificate(Context context, String packageName) {
        try {
            return context.getPackageManager().checkSignatures(context.getPackageName(), packageName)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** HTTPS may be public; HTTP is limited to loopback/private IPv4 for local FadCam Server Room. */
    private static boolean isAllowedStream(String value) {
        try {
            Uri parsed = Uri.parse(value.trim());
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (host == null || parsed.getUserInfo() != null || parsed.getFragment() != null) return false;
            if ("https".equalsIgnoreCase(scheme)) {
                return !"localhost".equalsIgnoreCase(host) && !host.endsWith(".local");
            }
            if (!"http".equalsIgnoreCase(scheme)) return false;
            if (!looksLikeIpv4(host)) return false;
            String[] octets = host.split("\\.");
            int a = Integer.parseInt(octets[0]);
            int b = Integer.parseInt(octets[1]);
            return a == 127
                    || a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean looksLikeIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            int value = Integer.parseInt(part);
            if (value > 255) return false;
        }
        return true;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Result {
        public final boolean accepted;
        public final String reason;
        public final String streamUrl;
        public final String name;
        public final String owner;

        private Result(boolean accepted, String reason, String streamUrl, String name, String owner) {
            this.accepted = accepted;
            this.reason = reason;
            this.streamUrl = streamUrl;
            this.name = name;
            this.owner = owner;
        }

        static Result accept(String url, String name, String owner) {
            return new Result(true, "", url, name, owner);
        }

        static Result reject(String reason) {
            return new Result(false, reason, null, null, null);
        }
    }
}
