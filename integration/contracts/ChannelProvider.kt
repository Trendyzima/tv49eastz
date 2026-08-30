package tv49eastz.integration.contracts

import tv49eastz.core.model.Channel

/** Supplies canonical channels. Implementations must not expose provider-specific models. */
interface ChannelProvider {
    suspend fun listChannels(): List<Channel>
}
