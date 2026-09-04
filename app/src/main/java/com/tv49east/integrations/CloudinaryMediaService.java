package com.tv49east.integrations;

import android.content.Context;
import android.net.Uri;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.fadcam.BuildConfig;

import java.util.HashMap;
import java.util.Map;

/** Cloudinary boundary for social image/video upload, CDN delivery and transformations. */
public final class CloudinaryMediaService {
    private static volatile boolean initialized;

    private CloudinaryMediaService() {}

    public static synchronized void initialize(Context context) {
        if (initialized || BuildConfig.CLOUDINARY_CLOUD_NAME.isEmpty()) return;
        Map<String, Object> config = new HashMap<>();
        config.put("cloud_name", BuildConfig.CLOUDINARY_CLOUD_NAME);
        config.put("secure", true);
        config.put("urlAnalytics", false);
        MediaManager.init(context.getApplicationContext(), config);
        initialized = true;
    }

    public static boolean isConfigured() {
        return initialized && !BuildConfig.CLOUDINARY_UPLOAD_PRESET.isEmpty();
    }

    public static String upload(Uri mediaUri, UploadCallback callback) {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary cloud name/upload preset is not configured");
        }
        return MediaManager.get()
                .upload(mediaUri)
                .unsigned(BuildConfig.CLOUDINARY_UPLOAD_PRESET)
                .option("folder", BuildConfig.CLOUDINARY_FOLDER)
                .option("resource_type", "auto")
                .callback(callback == null ? NO_OP_CALLBACK : callback)
                .dispatch();
    }

    public static String deliveryUrl(String publicId, String resourceType) {
        if (!initialized || publicId == null || publicId.isEmpty()) return null;
        return MediaManager.get().url()
                .resourceType(resourceType == null ? "image" : resourceType)
                .secure(true)
                .generate(publicId);
    }

    private static final UploadCallback NO_OP_CALLBACK = new UploadCallback() {
        @Override public void onStart(String requestId) {}
        @Override public void onProgress(String requestId, long bytes, long totalBytes) {}
        @Override public void onSuccess(String requestId, Map resultData) {}
        @Override public void onError(String requestId, ErrorInfo error) {}
        @Override public void onReschedule(String requestId, ErrorInfo error) {}
    };
}
