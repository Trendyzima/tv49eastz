package com.tv49eastz.core.model

/** Source/provider identity. Adapters map provider-specific data into canonical models. */
data class Provider(
    val id: String,
    val name: String,
    val type: Type,
    val enabled: Boolean = true,
    val configurationVersion: Int = 1
) {
    enum class Type { PLAYLIST, EPG, STREAM, DEVICE, LOCAL, OTHER }
}
