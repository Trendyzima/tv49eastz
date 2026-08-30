package com.tv49eastz.core.model

/** UI/player-independent playback state. */
data class PlaybackState(
    val sessionId: String,
    val status: Status,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val errorCode: String? = null,
    val updatedAt: String? = null
) {
    enum class Status { IDLE, PREPARING, PLAYING, BUFFERING, PAUSED, ENDED, ERROR }
}
