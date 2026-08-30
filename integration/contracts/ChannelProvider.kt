package com.tv49eastz.integration.contracts

import com.tv49eastz.core.model.Channel

/** Supplies canonical channels. Implementations must not expose provider-specific models. */
interface ChannelProvider {
    suspend fun listChannels(): List<Channel>
}
