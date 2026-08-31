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

/** Validates the signed, short-lived FadCam → TV handoff contract. */
public final class FadCamHandoffVerifier {
    private static final long MAX_CLOCK_SKEW_MS = 30_000L;
    private static final Set<String> USED_NONCES = new HashSet<>();
    private static final Object LOCK = new Object();

    private FadCamHandoffVerifier() {}

    public static Result verify(Context context, Uri uri) {
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
        if (!"com.fadcam".equals(packageName) && !"com.fadcam.beta".equals(packageName)) {
            return Result.reject("unexpected publisher package");
        }
        if (!isHttps(streamUrl)) return Result.reject("stream must be HTTPS");

        long issuedAt;
        long expiresAt;
        try {
            issuedAt = Long.parseLong(iatRaw);
            expiresAt = Long.parseLong(expRaw);
        } catch (NumberFormatException e) {
            return Result.reject("invalid timestamps");
        }
        long now = System.currentTimeMillis();
        if (issuedAt > now + MAX_CLOCK_SKEW_MS || expiresAt <= now || expiresAt - issuedAt > 60_000L) {
            return Result.reject("expired or invalid lifetime");
        }

        if (!isSameSigningCertificate(context, packageName)) {
            return Result.reject("publisher signature mismatch");
        }

        synchronized (LOCK) {
            if (USED_NONCES.contains(nonce)) return Result.reject("replayed nonce");
        }

        try {
            byte[] publicKeyBytes = Base64.decode(publicKeyRaw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            byte[] signatureBytes = Base64.decode(signatureRaw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            KeyFactory factory = KeyFactory.getInstance("EC");
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            String canonical = com.fadcam.tv.FadCamTvPublisher.canonical(
                    1, nonce, issuedAt, expiresAt, packageName, streamUrl,
                    name == null ? "" : name, owner == null ? "" : owner);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) return Result.reject("invalid signature");

            synchronized (LOCK) {
                USED_NONCES.add(nonce);
                if (USED_NONCES.size() > 1024) USED_NONCES.clear();
            }
            return Result.accept(streamUrl, name == null ? "FadCam Local" : name, owner == null ? "FadCam" : owner);
        } catch (Exception e) {
            return Result.reject("signature validation failed");
        }
    }

    private static boolean isSameSigningCertificate(Context context, String packageName) {
        try {
            int result = context.getPackageManager().checkSignatures(
                    context.getPackageName(), packageName);
            return result == PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isHttps(String value) {
        try {
            Uri uri = Uri.parse(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception ignored) {
            return false;
        }
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
