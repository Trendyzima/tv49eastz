package com.tv49eastz.core.model

/** A normalized playlist and its provider ownership. */
data class Playlist(
    val id: String,
    val providerId: String,
    val name: String,
    val sourceUri: String? = null,
    val channelIds: List<String> = emptyList(),
    val updatedAt: String? = null,
    val enabled: Boolean = true
)
