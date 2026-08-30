package com.tv49eastz.gateway

import com.tv49eastz.core.model.StreamSession

/** Authentication/authorization seam. Implementations must validate identity before opening a session. */
interface SessionAuthorizer {
    suspend fun authenticate(credential: String): String?
    suspend fun authorize(userId: String, streamId: String): Boolean
}

class StreamSessionManager(
    private val authorizer: SessionAuthorizer,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val ttlSeconds: Long = 3600
) {
    private val sessions = linkedMapOf<String, StreamSession>()

    suspend fun open(credential: String, streamId: String, deviceId: String? = null): StreamSession {
        val userId = authorizer.authenticate(credential) ?: error("unauthorized")
        require(authorizer.authorize(userId, streamId)) { "forbidden" }
        val now = clock()
        val id = java.util.UUID.randomUUID().toString()
        return StreamSession(
            id = id,
            streamId = streamId,
            userId = userId,
            deviceId = deviceId,
            state = StreamSession.State.ACTIVE,
            startedAt = now.toString(),
            lastActivityAt = now.toString(),
            expiresAt = (now + ttlSeconds).toString()
        ).also { sessions[id] = it }
    }

    fun get(id: String): StreamSession? = sessions[id]?.takeUnless { it.expiresAt?.toLongOrNull()?.let(clock::invoke)?.let { _ -> false } ?: false }

    fun end(id: String): Boolean {
        val current = sessions[id] ?: return false
        sessions[id] = current.copy(state = StreamSession.State.ENDED, lastActivityAt = clock().toString())
        return true
    }
}
