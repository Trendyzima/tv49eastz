package tv49eastz.integration.adapters.epg

import tv49eastz.core.model.EpgChannel
import tv49eastz.core.model.Program
import tv49eastz.integration.contracts.EpgProvider

/** Adapter seam for XMLTV/EPG implementations. */
class CanonicalEpgAdapter(
    private val channelLoader: suspend () -> List<EpgChannel>,
    private val programLoader: suspend (String, Long, Long) -> List<Program>
) : EpgProvider {
    override suspend fun channels(): List<EpgChannel> = channelLoader()

    override suspend fun programs(
        epgChannelId: String,
        fromEpochSeconds: Long,
        toEpochSeconds: Long
    ): List<Program> = programLoader(epgChannelId, fromEpochSeconds, toEpochSeconds)
}
