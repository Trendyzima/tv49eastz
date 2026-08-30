package tv49eastz.gateway

import tv49eastz.core.model.Stream
import tv49eastz.core.model.StreamSession
import tv49eastz.integration.contracts.StreamProvider
import java.time.Instant
import java.util.UUID

/** Application-facing gateway seam. Network/auth middleware must call this service after authorization. */
class StreamGatewayService(
    private val streamProvider: StreamProvider,
    private val sessionTtlSeconds: Long = 3600
) {
    suspend fun openSession(userId: String, channelId: String, stream: Stream): StreamSession {
        require(userId.isNotBlank()) { "userId is required" }
        require(stream.channelId == channelId) { "stream does not belong to channel" }
        val now = Instant.now().epochSecond
        return StreamSession(
            id = UUID.randomUUID().toString(),
            userId = userId,
            streamId = stream.id,
            startedAtEpochSeconds = now,
            expiresAtEpochSeconds = now + sessionTtlSeconds
        )
    }

    suspend fun resolveStreams(channelId: String): List<Stream> =
        streamProvider.streamsFor(tv49eastz.core.model.Channel(id = channelId, name = channelId))
}
