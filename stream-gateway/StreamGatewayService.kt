package com.tv49eastz.gateway

import com.tv49eastz.core.model.Channel
import com.tv49eastz.core.model.Stream
import com.tv49eastz.core.model.StreamSession
import com.tv49eastz.integration.contracts.StreamProvider
import java.util.UUID

/**
 * Gateway application boundary. Authentication/authorization must happen before
 * an opaque playback URL is issued. No upstream/private URL is returned here.
 */
class StreamGatewayService(
    private val streamProvider: StreamProvider,
    private val authorizer: SessionAuthorizer,
    private val publicBaseUrl: String,
    private val sessionTtlSeconds: Long = 3600,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    suspend fun openAuthorizedSession(
        credential: String,
        channel: Channel,
        streamId: String,
        deviceId: String? = null
    ): AuthorizedStream {
        val userId = authorizer.authenticate(credential) ?: error("unauthorized")
        require(authorizer.authorize(userId, streamId)) { "forbidden" }
        val stream = streamProvider.streamsFor(channel).firstOrNull { it.id == streamId }
            ?: error("stream not found")
        val now = clock()
        val sessionId = UUID.randomUUID().toString()
        val session = StreamSession(
            id = sessionId,
            streamId = stream.id,
            userId = userId,
            deviceId = deviceId,
            state = StreamSession.State.ACTIVE,
            startedAt = now.toString(),
            lastActivityAt = now.toString(),
            expiresAt = (now + sessionTtlSeconds).toString()
        )
        val base = publicBaseUrl.trimEnd('/')
        return AuthorizedStream(session, "$base/stream/$sessionId/index.m3u8")
    }

    suspend fun resolveStreams(channel: Channel): List<Stream> = streamProvider.streamsFor(channel)
}

data class AuthorizedStream(val session: StreamSession, val manifestUrl: String)
