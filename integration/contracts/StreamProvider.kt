package tv49eastz.integration.contracts

import tv49eastz.core.model.Channel
import tv49eastz.core.model.Stream

/** Resolves playable streams for canonical channels. */
interface StreamProvider {
    suspend fun streamsFor(channel: Channel): List<Stream>
}
