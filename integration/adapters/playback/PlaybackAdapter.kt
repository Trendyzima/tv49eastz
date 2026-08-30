package com.tv49eastz.integration.adapters.playback

import com.tv49eastz.core.model.PlaybackState
import com.tv49eastz.core.model.Stream

/** Player-independent seam. Concrete Media3 integration belongs behind this adapter. */
interface PlaybackAdapter {
    suspend fun prepare(stream: Stream)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    fun state(): PlaybackState
}
