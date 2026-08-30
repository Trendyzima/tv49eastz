package com.tv49eastz.integration.adapters.streaming

import com.tv49eastz.core.model.Channel
import com.tv49eastz.core.model.Stream
import com.tv49eastz.core.model.StreamSource
import com.tv49eastz.integration.contracts.StreamProvider

/**
 * Adapter boundary for the protected FadCam server-tap.
 * The tap, not the domain layer, owns the private upstream connection.
 */
class ServerTapStreamProvider(
    private val sourceResolver: suspend (channel: Channel) -> List<StreamSource>
) : StreamProvider {
    override suspend fun streamsFor(channel: Channel): List<Stream> =
        sourceResolver(channel).map { source ->
            require(source.channelId == channel.id) { "source/channel mismatch" }
            require(source.enabled) { "disabled stream source" }
            Stream(
                id = "${channel.id}:${source.id}",
                channelId = channel.id,
                sourceIds = listOf(source.id),
                live = source.kind == StreamSource.Kind.HLS || source.kind == StreamSource.Kind.DASH,
                available = source.enabled
            )
        }
}
