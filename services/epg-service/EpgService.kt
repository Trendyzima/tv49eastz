package com.tv49eastz.services.epgservice

import com.tv49eastz.core.model.EpgChannel
import com.tv49eastz.core.model.Program
import com.tv49eastz.integration.contracts.EpgProvider

class EpgService(private val providers: List<EpgProvider>) {
    suspend fun channels(): List<EpgChannel> = providers.flatMap { it.channels() }.distinctBy(EpgChannel::id)

    suspend fun programs(epgChannelId: String, fromEpochSeconds: Long, toEpochSeconds: Long): List<Program> =
        providers.flatMap { it.programs(epgChannelId, fromEpochSeconds, toEpochSeconds) }.distinctBy(Program::id)
}
