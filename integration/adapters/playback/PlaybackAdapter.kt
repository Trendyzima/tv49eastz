package tv49eastz.integration.adapters.playback

import tv49eastz.core.model.PlaybackState
import tv49eastz.core.model.Stream

/** Player-independent seam. Concrete ExoPlayer/Media3 integration belongs behind this adapter. */
interface PlaybackAdapter {
    suspend fun prepare(stream: Stream)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    fun state(): PlaybackState
}
