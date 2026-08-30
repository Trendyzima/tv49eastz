package tv49eastz.services.channelservice

import tv49eastz.core.model.Channel
import tv49eastz.integration.contracts.ChannelProvider

class ChannelService(private val providers: List<ChannelProvider>) {
    suspend fun listChannels(): List<Channel> = providers.flatMap { it.listChannels() }.distinctBy(Channel::id)
}
