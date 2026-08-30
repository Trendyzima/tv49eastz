package com.tv49eastz.core.model

/** A concrete source from which a stream can be obtained. */
data class StreamSource(
    val id: String,
    val providerId: String,
    val channelId: String,
    val kind: Kind,
    val uri: String,
    val mimeType: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true
) {
    enum class Kind { HLS, DASH, HTTP, RTSP, OTHER }
}
