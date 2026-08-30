package tv49eastz.services.streamservice

import tv49eastz.core.model.Channel
import tv49eastz.core.model.Stream
import tv49eastz.integration.contracts.StreamProvider

class StreamService(private val providers: List<StreamProvider>) {
    suspend fun streamsFor(channel: Channel): List<Stream> = providers.flatMap { it.streamsFor(channel) }.distinctBy(Stream::id)
}
