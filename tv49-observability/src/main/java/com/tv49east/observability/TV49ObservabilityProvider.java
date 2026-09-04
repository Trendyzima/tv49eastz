package com.tv49east.observability;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.cloudinary.android.MediaManager;
import com.posthog.PostHog;
import com.posthog.android.PostHogAndroid;
import com.posthog.android.PostHogAndroidConfig;
import com.tv49east.integrations.CloudinaryMediaService;

import io.sentry.Sentry;

import java.util.HashMap;
import java.util.Map;

public final class TV49ObservabilityProvider extends ContentProvider {
    static final String POSTHOG_KEY = "tv49east.posthog.key";
    static final String POSTHOG_HOST = "tv49east.posthog.host";
    static final String SENTRY_DSN = "tv49east.sentry.dsn";
    static final String CLOUDINARY_CLOUD = "tv49east.cloudinary.cloud_name";
    static final String CLOUDINARY_PRESET = "tv49east.cloudinary.upload_preset";
    static final String CLOUDINARY_FOLDER = "tv49east.cloudinary.folder";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;

        Bundle metadata = null;
        try {
            ProviderInfo info = context.getPackageManager().getProviderInfo(
                    new android.content.ComponentName(context, TV49ObservabilityProvider.class),
                    android.content.pm.PackageManager.GET_META_DATA);
            metadata = info.metaData;
        } catch (Exception ignored) {}

        String posthogKey = value(metadata, POSTHOG_KEY);
        String posthogHost = value(metadata, POSTHOG_HOST, "https://us.i.posthog.com");
        String sentryDsn = value(metadata, SENTRY_DSN);
        String cloudName = value(metadata, CLOUDINARY_CLOUD);
        String uploadPreset = value(metadata, CLOUDINARY_PRESET);
        String folder = value(metadata, CLOUDINARY_FOLDER, "tv49-east");

        try {
            if (!posthogKey.isEmpty()) {
                PostHogAndroidConfig config = new PostHogAndroidConfig(posthogKey, posthogHost);
                config.setDebug(false);
                config.setCaptureApplicationLifecycleEvents(true);
                config.setCaptureScreenViews(true);
                config.setFlushAt(20);
                config.setFlushIntervalSeconds(30);
                PostHogAndroid.setup(context.getApplicationContext(), config);
                PostHog.capture("tv49_observability_initialized");
            }
        } catch (Throwable ignored) {}

        try {
            if (!sentryDsn.isEmpty()) {
                Sentry.init(options -> {
                    options.setDsn(sentryDsn);
                    options.setEnvironment("production");
                    options.setTracesSampleRate(0.20);
                    options.setSendDefaultPii(false);
                    options.setEnableAutoSessionTracking(true);
                    options.setAttachScreenshot(false);
                    options.setAttachViewHierarchy(false);
                });
            }
        } catch (Throwable ignored) {}

        try {
            if (!cloudName.isEmpty()) {
                Map<String, Object> config = new HashMap<>();
                config.put("cloud_name", cloudName);
                config.put("secure", true);
                config.put("urlAnalytics", false);
                MediaManager.init(context.getApplicationContext(), config);
                CloudinaryMediaService.configure(uploadPreset, folder);
            }
        } catch (Throwable ignored) {}

        return true;
    }

    private static String value(Bundle metadata, String key) { return value(metadata, key, ""); }
    private static String value(Bundle metadata, String key, String fallback) {
        if (metadata == null) return fallback;
        String value = metadata.getString(key);
        return value == null ? fallback : value.trim();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
