package com.tv49eastz.core.model

/** User/device playback session. Tokens and secrets stay outside the domain model. */
data class StreamSession(
    val id: String,
    val streamId: String,
    val userId: String,
    val deviceId: String? = null,
    val state: State = State.CREATED,
    val startedAt: String? = null,
    val lastActivityAt: String? = null,
    val expiresAt: String? = null
) {
    enum class State { CREATED, ACTIVE, PAUSED, ENDED, EXPIRED, FAILED }
}
