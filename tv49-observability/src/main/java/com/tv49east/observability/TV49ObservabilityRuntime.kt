package com.tv49east.observability

import android.content.Context
import com.cloudinary.android.MediaManager
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.tv49east.integrations.CloudinaryMediaService
import io.sentry.Sentry

internal object TV49ObservabilityRuntime {
    fun initialize(
        context: Context,
        posthogKey: String,
        posthogHost: String,
        sentryDsn: String,
        cloudName: String,
        uploadPreset: String,
        folder: String,
    ) {
        val applicationContext = context.applicationContext

        if (posthogKey.isNotEmpty()) {
            runCatching {
                val config = PostHogAndroidConfig(
                    apiKey = posthogKey,
                    host = posthogHost,
                ).apply {
                    debug = false
                    captureApplicationLifecycleEvents = true
                    captureScreenViews = true
                    flushAt = 20
                    flushIntervalSeconds = 30
                }
                PostHogAndroid.setup(applicationContext, config)
                PostHog.capture(event = "tv49_observability_initialized")
            }
        }

        if (sentryDsn.isNotEmpty()) {
            runCatching {
                Sentry.init { options ->
                    options.dsn = sentryDsn
                    options.environment = "production"
                    options.tracesSampleRate = 0.20
                    options.isSendDefaultPii = false
                    options.isEnableAutoSessionTracking = true
                }
            }
        }

        if (cloudName.isNotEmpty()) {
            runCatching {
                val config = hashMapOf<String, Any>(
                    "cloud_name" to cloudName,
                    "secure" to true,
                    "urlAnalytics" to false,
                )
                MediaManager.init(applicationContext, config)
                CloudinaryMediaService.configure(uploadPreset, folder)
            }
        }
    }
}
