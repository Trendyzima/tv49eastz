package com.tv49eastz.core.model

/** Canonical EPG identity mapped to one or more TV channels. */
data class EpgChannel(
    val id: String,
    val displayName: String,
    val channelIds: List<String> = emptyList(),
    val iconUrl: String? = null,
    val language: String? = null
)
