package com.tv49east.observability;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

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

        boolean debug = (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        TV49ObservabilityRuntime.initialize(
                context,
                value(metadata, POSTHOG_KEY),
                value(metadata, POSTHOG_HOST, "https://us.i.posthog.com"),
                value(metadata, SENTRY_DSN),
                value(metadata, CLOUDINARY_CLOUD),
                value(metadata, CLOUDINARY_PRESET),
                value(metadata, CLOUDINARY_FOLDER, "tv49-east"),
                debug);

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
