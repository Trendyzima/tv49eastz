package com.tv49eastz.services.streamservice

import com.tv49eastz.core.model.Channel
import com.tv49eastz.core.model.Stream
import com.tv49eastz.integration.contracts.StreamProvider

class StreamService(private val providers: List<StreamProvider>) {
    suspend fun streamsFor(channel: Channel): List<Stream> =
        providers.flatMap { it.streamsFor(channel) }.distinctBy(Stream::id)
}
