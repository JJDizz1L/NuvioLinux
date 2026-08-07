package com.nuviolinux.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Installs versioned runtime resources (native libraries, bundled web assets)
 * into the XDG cache directory. Each distinct content set lives in its own
 * content-hashed subdirectory, so unchanged resources are reused across
 * launches and reinstallations, and updated resources don't collide.
 */
internal object DesktopCache {
    fun installVersionedFiles(namespace: String, files: Map<String, ByteArray>): Path {
        require(namespace.isNotBlank())
        require(files.isNotEmpty())
        val version = contentVersion(files)
        val directory = DesktopStorage.cacheDir.resolve(namespace).resolve(version).normalize()
        require(directory.startsWith(DesktopStorage.cacheDir))
        files.forEach { (relativePath, bytes) ->
            val target = directory.resolve(relativePath).normalize()
            require(target.startsWith(directory))
            writeIfChanged(target, bytes)
        }
        return directory
    }

    private fun contentVersion(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(24)
    }

    private fun writeIfChanged(target: Path, bytes: ByteArray) {
        if (Files.exists(target) && Files.readAllBytes(target).contentEquals(bytes)) return
        Files.createDirectories(target.parent)
        val pending = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            Files.write(pending, bytes)
            runCatching {
                Files.move(
                    pending,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
