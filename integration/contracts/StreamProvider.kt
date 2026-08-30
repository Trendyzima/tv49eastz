package com.tv49eastz.integration.contracts

import com.tv49eastz.core.model.Channel
import com.tv49eastz.core.model.Stream

/** Resolves playable streams for canonical channels. */
interface StreamProvider {
    suspend fun streamsFor(channel: Channel): List<Stream>
}
