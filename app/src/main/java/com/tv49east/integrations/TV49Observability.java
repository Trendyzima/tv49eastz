package com.tv49east.integrations;

import android.content.Context;

import com.fadcam.BuildConfig;
import com.posthog.PostHog;
import com.posthog.android.PostHogAndroid;
import com.posthog.android.PostHogAndroidConfig;

import io.sentry.Sentry;

import java.util.Collections;
import java.util.Map;

/** Central integration boundary for TV 49 East analytics and diagnostics. */
public final class TV49Observability {
    private static volatile boolean initialized;

    private TV49Observability() {}

    public static synchronized void initialize(Context context) {
        if (initialized) return;

        if (!BuildConfig.POSTHOG_API_KEY.isEmpty()) {
            PostHogAndroidConfig config = new PostHogAndroidConfig(
                    BuildConfig.POSTHOG_API_KEY,
                    BuildConfig.POSTHOG_HOST
            );
            config.setDebug(BuildConfig.DEBUG);
            config.setCaptureApplicationLifecycleEvents(true);
            config.setCaptureScreenViews(true);
            config.setFlushAt(20);
            config.setFlushIntervalSeconds(30);
            PostHogAndroid.setup(context.getApplicationContext(), config);
        }

        if (!BuildConfig.SENTRY_DSN.isEmpty()) {
            Sentry.init(options -> {
                options.setDsn(BuildConfig.SENTRY_DSN);
                options.setEnvironment(BuildConfig.DEBUG ? "development" : "production");
                options.setTracesSampleRate(BuildConfig.DEBUG ? 0.10 : 0.20);
                options.setSendDefaultPii(false);
                options.setEnableAutoSessionTracking(true);
                options.setAttachScreenshot(false);
                options.setAttachViewHierarchy(false);
            });
        }

        initialized = true;
    }

    public static void capture(String event, Map<String, Object> properties) {
        if (BuildConfig.POSTHOG_API_KEY.isEmpty()) return;
        PostHog.capture(event, properties == null ? Collections.emptyMap() : properties);
    }

    public static void capture(String event) {
        capture(event, Collections.emptyMap());
    }

    public static void exception(Throwable throwable) {
        if (throwable != null && !BuildConfig.SENTRY_DSN.isEmpty()) Sentry.captureException(throwable);
    }

    public static void breadcrumb(String message) {
        if (!BuildConfig.SENTRY_DSN.isEmpty()) Sentry.addBreadcrumb(message);
    }

    public static void identify(String distinctId, Map<String, Object> properties) {
        if (BuildConfig.POSTHOG_API_KEY.isEmpty() || distinctId == null || distinctId.isEmpty()) return;
        PostHog.identify(distinctId, properties == null ? Collections.emptyMap() : properties);
    }

    public static void resetIdentity() {
        if (!BuildConfig.POSTHOG_API_KEY.isEmpty()) PostHog.reset();
        if (!BuildConfig.SENTRY_DSN.isEmpty()) Sentry.setUser(null);
    }
}
