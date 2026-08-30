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
        val session = StreamSession(
            id = java.util.UUID.randomUUID().toString(),
            streamId = streamId,
            userId = userId,
            deviceId = deviceId,
            state = StreamSession.State.ACTIVE,
            startedAt = now.toString(),
            lastActivityAt = now.toString(),
            expiresAt = (now + ttlSeconds).toString()
        )
        sessions[session.id] = session
        return session
    }

    fun get(id: String): StreamSession? {
        val session = sessions[id] ?: return null
        val expiry = session.expiresAt?.toLongOrNull() ?: return null
        if (clock() >= expiry) {
            sessions[id] = session.copy(state = StreamSession.State.EXPIRED, lastActivityAt = clock().toString())
            return null
        }
        return session
    }

    fun touch(id: String): StreamSession? {
        val current = get(id) ?: return null
        val updated = current.copy(lastActivityAt = clock().toString())
        sessions[id] = updated
        return updated
    }

    fun end(id: String): Boolean {
        val current = sessions[id] ?: return false
        sessions[id] = current.copy(state = StreamSession.State.ENDED, lastActivityAt = clock().toString())
        return true
    }
}
