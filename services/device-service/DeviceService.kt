package tv49eastz.services.deviceservice

import tv49eastz.core.model.Device
import tv49eastz.integration.contracts.DeviceProvider

class DeviceService(private val providers: List<DeviceProvider>) {
    suspend fun devices(): List<Device> = providers.flatMap { it.devices() }.distinctBy(Device::id)

    suspend fun health(deviceId: String): Device? = providers.asSequence()
        .mapNotNull { provider -> provider.health(deviceId) }
        .firstOrNull()
}
