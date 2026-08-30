package com.tv49eastz.services.deviceservice

import com.tv49eastz.core.model.Device
import com.tv49eastz.integration.contracts.DeviceProvider

class DeviceService(private val providers: List<DeviceProvider>) {
    suspend fun devices(): List<Device> = providers.flatMap { it.devices() }.distinctBy(Device::id)

    suspend fun health(deviceId: String): Device? = providers.asSequence()
        .mapNotNull { provider -> provider.health(deviceId) }
        .firstOrNull()
}
