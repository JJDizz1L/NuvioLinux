package com.nuviolinux.app.core.storage

import com.nuviolinux.app.core.sync.SyncClientIdentity

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        DesktopStorage.wipe()
        SyncClientIdentity.reset()
    }
}
