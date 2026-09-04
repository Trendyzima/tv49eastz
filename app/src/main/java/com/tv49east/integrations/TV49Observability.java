package com.tv49east.integrations;

import android.content.Context;

import com.fadcam.BuildConfig;
import com.tv49east.observability.TV49ObservabilityRuntime;

import java.util.Map;

/** Central integration boundary for TV 49 East analytics and diagnostics. */
public final class TV49Observability {
    private static volatile boolean initialized;

    private TV49Observability() {}

    public static synchronized void initialize(Context context) {
        if (initialized || context == null) return;

        TV49ObservabilityRuntime.initialize(
                context.getApplicationContext(),
                BuildConfig.POSTHOG_API_KEY,
                BuildConfig.POSTHOG_HOST,
                BuildConfig.SENTRY_DSN,
                BuildConfig.CLOUDINARY_CLOUD_NAME,
                BuildConfig.CLOUDINARY_UPLOAD_PRESET,
                BuildConfig.CLOUDINARY_FOLDER,
                BuildConfig.DEBUG);

        initialized = true;
    }

    public static void capture(String event, Map<String, Object> properties) {
        if (BuildConfig.POSTHOG_API_KEY.isEmpty()) return;
        TV49ObservabilityRuntime.capture(event, properties);
    }

    public static void capture(String event) {
        TV49ObservabilityRuntime.capture(event);
    }

    public static void exception(Throwable throwable) {
        if (throwable != null && !BuildConfig.SENTRY_DSN.isEmpty()) {
            TV49ObservabilityRuntime.exception(throwable);
        }
    }

    public static void breadcrumb(String message) {
        if (!BuildConfig.SENTRY_DSN.isEmpty()) {
            TV49ObservabilityRuntime.breadcrumb(message);
        }
    }

    public static void identify(String distinctId, Map<String, Object> properties) {
        if (BuildConfig.POSTHOG_API_KEY.isEmpty() || distinctId == null || distinctId.isEmpty()) return;
        TV49ObservabilityRuntime.identify(distinctId, properties);
    }

    public static void resetIdentity() {
        if (!BuildConfig.POSTHOG_API_KEY.isEmpty() || !BuildConfig.SENTRY_DSN.isEmpty()) {
            TV49ObservabilityRuntime.resetIdentity();
        }
    }
}
