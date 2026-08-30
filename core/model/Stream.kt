package com.tv49eastz.core.model

/** Logical stream associated with a channel; sources are resolved separately. */
data class Stream(
    val id: String,
    val channelId: String,
    val sourceIds: List<String> = emptyList(),
    val title: String? = null,
    val live: Boolean = true,
    val available: Boolean = true
)
