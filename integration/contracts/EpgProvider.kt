package tv49eastz.integration.contracts

import tv49eastz.core.model.EpgChannel
import tv49eastz.core.model.Program

/** Supplies normalized EPG channels and programmes. */
interface EpgProvider {
    suspend fun channels(): List<EpgChannel>
    suspend fun programs(epgChannelId: String, fromEpochSeconds: Long, toEpochSeconds: Long): List<Program>
}
