package com.tv49eastz.core.model

/** Canonical TV channel. Provider-specific details must be mapped into this model. */
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val countryCode: String? = null,
    val language: String? = null,
    val category: String? = null,
    val streamIds: List<String> = emptyList(),
    val epgChannelId: String? = null,
    val enabled: Boolean = true
)
