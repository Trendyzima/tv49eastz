package com.tv49eastz.services.channelservice

import com.tv49eastz.core.model.Channel
import com.tv49eastz.integration.contracts.ChannelProvider

class ChannelService(private val providers: List<ChannelProvider>) {
    suspend fun listChannels(): List<Channel> = providers.flatMap { it.listChannels() }.distinctBy(Channel::id)
}
