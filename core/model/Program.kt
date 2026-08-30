package com.tv49eastz.core.model

/** A single EPG programme in the canonical catalog. Times are ISO-8601 strings in UTC. */
data class Program(
    val id: String,
    val epgChannelId: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val description: String? = null,
    val category: String? = null,
    val episodeNumber: String? = null
)
