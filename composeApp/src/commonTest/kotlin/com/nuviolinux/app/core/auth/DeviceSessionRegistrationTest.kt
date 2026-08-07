package com.nuviolinux.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceSessionRegistrationTest {
    @Test
    fun buildsOfficialClientRegistrationPayload() {
        val params = buildDeviceRegistrationParams(
            installationId = "nuvio-desktop-installation",
            clientVersion = "1.2.3",
            metadata = DeviceClientMetadata(
                clientName = "Nuvio Desktop",
                deviceName = "Nayif MacBook Pro",
                platform = "macOS 15.5",
            ),
        )

        assertEquals("nuvio-desktop-installation", params.getValue("p_installation_id").toString().trim('"'))
        assertEquals("Nuvio Desktop", params.getValue("p_client_name").toString().trim('"'))
        assertEquals("1.2.3", params.getValue("p_client_version").toString().trim('"'))
        assertEquals("macOS 15.5", params.getValue("p_platform").toString().trim('"'))
        assertEquals("Nayif MacBook Pro", params.getValue("p_device_name").toString().trim('"'))
    }

    @Test
    fun combinesManufacturerAndModelWithoutRepeatingManufacturer() {
        assertEquals(
            "Samsung SM-S918B",
            formatDeviceName("Samsung", "SM-S918B", "Android device"),
        )
        assertEquals(
            "Google Pixel 9",
            formatDeviceName("Google", "Google Pixel 9", "Android device"),
        )
    }
}
