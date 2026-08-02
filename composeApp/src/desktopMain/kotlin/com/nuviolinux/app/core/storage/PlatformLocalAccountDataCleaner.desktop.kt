package com.nuviolinux.app.core.storage

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        DesktopStorage.wipe()
    }
}
