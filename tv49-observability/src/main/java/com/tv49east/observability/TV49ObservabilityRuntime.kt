package com.tv49east.observability

import android.content.Context
import com.cloudinary.android.MediaManager
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.tv49east.integrations.CloudinaryMediaService
import io.sentry.Sentry

/**
 * Kotlin-owned SDK boundary. Java application code calls this facade instead of
 * directly referencing Kotlin-heavy PostHog/Sentry APIs.
 */
object TV49ObservabilityRuntime {
    @Volatile
    private var initialized = false

    @JvmStatic
    @Synchronized
    fun initialize(
        context: Context,
        posthogKey: String,
        posthogHost: String,
        sentryDsn: String,
        cloudName: String,
        uploadPreset: String,
        folder: String,
        debug: Boolean,
    ) {
        if (initialized) return

        val applicationContext = context.applicationContext

        if (posthogKey.isNotEmpty()) {
            runCatching {
                val config = PostHogAndroidConfig(
                    apiKey = posthogKey,
                    host = posthogHost,
                ).apply {
                    this.debug = debug
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
                    options.environment = if (debug) "development" else "production"
                    options.tracesSampleRate = if (debug) 0.10 else 0.20
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

        initialized = true
    }

    @JvmStatic
    fun capture(event: String, properties: Map<String, Any?>?) {
        if (event.isBlank()) return
        runCatching {
            PostHog.capture(
                event = event,
                properties = properties ?: emptyMap(),
            )
        }
    }

    @JvmStatic
    fun capture(event: String) {
        capture(event, emptyMap())
    }

    @JvmStatic
    fun exception(throwable: Throwable?) {
        if (throwable == null) return
        runCatching { Sentry.captureException(throwable) }
    }

    @JvmStatic
    fun breadcrumb(message: String?) {
        if (message.isNullOrBlank()) return
        runCatching { Sentry.addBreadcrumb(message) }
    }

    @JvmStatic
    fun identify(distinctId: String?, properties: Map<String, Any?>?) {
        if (distinctId.isNullOrBlank()) return
        runCatching {
            PostHog.identify(
                distinctId = distinctId,
                userProperties = properties ?: emptyMap(),
            )
        }
    }

    @JvmStatic
    fun resetIdentity() {
        runCatching { PostHog.reset() }
        runCatching { Sentry.setUser(null) }
    }
}
