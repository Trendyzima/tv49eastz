package com.tv49eastz.core.model

/** Registered streaming/source device. Credentials are never represented in this model. */
data class Device(
    val id: String,
    val name: String,
    val status: Status = Status.UNKNOWN,
    val capabilities: Set<Capability> = emptySet(),
    val lastSeenAt: String? = null
) {
    enum class Status { ONLINE, OFFLINE, DEGRADED, UNKNOWN }
    enum class Capability { HLS, DASH, AUDIO, VIDEO, EPG, TUNNEL }
}
