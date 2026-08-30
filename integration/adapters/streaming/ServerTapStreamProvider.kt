package tv49eastz.integration.adapters.streaming

import tv49eastz.core.model.Channel
import tv49eastz.core.model.Stream
import tv49eastz.core.model.StreamSource
import tv49eastz.integration.contracts.StreamProvider

/**
 * Adapter boundary for the protected FadCam server-tap.
 *
 * The adapter accepts a tap-issued stream reference and converts it into the
 * canonical Stream model. It must never expose the private FadCam address to
 * callers. The actual HTTP/tunnel transport belongs outside this model layer.
 */
class ServerTapStreamProvider(
    private val sourceResolver: suspend (channel: Channel) -> List<StreamSource>
) : StreamProvider {
    override suspend fun streamsFor(channel: Channel): List<Stream> =
        sourceResolver(channel).map { source ->
            Stream(
                id = "${channel.id}:${source.id}",
                channelId = channel.id,
                sources = listOf(source)
            )
        }
}
