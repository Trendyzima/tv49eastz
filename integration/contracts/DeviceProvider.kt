package com.tv49eastz.integration.contracts

import com.tv49eastz.core.model.Device

/** Discovers and reports the health of protected streaming devices. */
interface DeviceProvider {
    suspend fun devices(): List<Device>
    suspend fun health(deviceId: String): Device?
}
