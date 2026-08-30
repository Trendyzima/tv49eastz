package com.tv49eastz.integration.adapters.streaming

import com.tv49eastz.core.model.Channel
import com.tv49eastz.core.model.Stream
import com.tv49eastz.core.model.StreamSource
import com.tv49eastz.integration.contracts.StreamProvider

/**
 * Live streaming adapter backed by the configured server-tap transport.
 * It owns no FadCam knowledge; the tap is the only upstream boundary.
 */
class ServerTapStreamProvider(
    private val tapClient: ServerTapClient,
    private val providerId: String = "server-tap"
) : StreamProvider {
    override suspend fun streamsFor(channel: Channel): List<Stream> {
        val source = tapClient.resolveLiveSource(channel, providerId)
        require(source.channelId == channel.id)
        require(source.kind == StreamSource.Kind.HLS)
        return listOf(
            Stream(
                id = "${channel.id}:server-tap",
                channelId = channel.id,
                sourceIds = listOf(source.id),
                title = channel.name,
                live = true,
                available = source.enabled
            )
        )
    }
}
