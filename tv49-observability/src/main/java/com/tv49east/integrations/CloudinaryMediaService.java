package com.tv49east.integrations;

import android.net.Uri;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;

import java.util.Map;

/** Public media boundary for social images/videos; no Cloudinary secret is stored on-device. */
public final class CloudinaryMediaService {
    private static String uploadPreset;
    private static String folder = "tv49-east";

    private CloudinaryMediaService() {}

    public static void configure(String preset, String targetFolder) {
        uploadPreset = preset == null ? "" : preset.trim();
        if (targetFolder != null && !targetFolder.trim().isEmpty()) folder = targetFolder.trim();
    }

    public static boolean isConfigured() {
        return uploadPreset != null && !uploadPreset.isEmpty();
    }

    public static String upload(Uri mediaUri, UploadCallback callback) {
        if (!isConfigured()) throw new IllegalStateException("Cloudinary upload preset is not configured");
        return MediaManager.get()
                .upload(mediaUri)
                .unsigned(uploadPreset)
                .option("folder", folder)
                .option("resource_type", "auto")
                .callback(callback == null ? NO_OP : callback)
                .dispatch();
    }

    public static String deliveryUrl(String publicId, String resourceType) {
        if (publicId == null || publicId.isEmpty()) return null;
        return MediaManager.get().url()
                .resourceType(resourceType == null ? "image" : resourceType)
                .secure(true)
                .generate(publicId);
    }

    private static final UploadCallback NO_OP = new UploadCallback() {
        @Override public void onStart(String requestId) {}
        @Override public void onProgress(String requestId, long bytes, long totalBytes) {}
        @Override public void onSuccess(String requestId, Map resultData) {}
        @Override public void onError(String requestId, ErrorInfo error) {}
        @Override public void onReschedule(String requestId, ErrorInfo error) {}
    };
}
