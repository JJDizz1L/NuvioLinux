package com.nuviolinux.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()
    private val stateStores = mutableMapOf<String, Store>()

    private val privateFilePermissions: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    private val privateDirectoryPermissions: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    val rootDir: Path by lazy {
        AppPaths.configDir.also { path ->
            Files.createDirectories(path)
            applyPrivateDirectoryPermissions(path)
        }
    }

    val stateDir: Path by lazy {
        AppPaths.stateDir.also { path ->
            Files.createDirectories(path)
            applyPrivateDirectoryPermissions(path)
        }
    }

    val cacheDir: Path by lazy {
        AppPaths.cacheDir.also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun stateStore(name: String): Store = synchronized(stateStores) {
        stateStores.getOrPut(name) { Store(stateDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        synchronized(stateStores) {
            stateStores.values.forEach(Store::clearInMemory)
            stateStores.clear()
        }
        listOf(rootDir, stateDir, cacheDir).filter { it.exists() }.forEach { root ->
            Files.walk(root).use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .filter { it != root }
                    .forEach { path -> runCatching { Files.deleteIfExists(path) } }
            }
        }
    }

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            if (value == null) {
                properties.remove(key)
            } else {
                properties.setProperty(key, value)
            }
            persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            properties.remove(key)
            persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            keys.forEach(properties::remove)
            persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            runCatching {
                Files.newInputStream(file).use { input ->
                    properties.load(input)
                }
            }
        }

        private fun persist() {
            Files.createDirectories(file.parent)
            applyPrivateDirectoryPermissions(file.parent)
            val pending = Files.createTempFile(file.parent, file.fileName.toString(), ".part")
            try {
                applyPrivateFilePermissions(pending)
                Files.newOutputStream(pending).use { output ->
                    properties.store(output, "Nuvio Linux desktop preferences")
                }
                runCatching {
                    Files.move(
                        pending,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING)
                }
                applyPrivateFilePermissions(file)
            } finally {
                Files.deleteIfExists(pending)
            }
        }
    }

    private fun applyPrivateFilePermissions(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, privateFilePermissions) }
    }

    private fun applyPrivateDirectoryPermissions(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, privateDirectoryPermissions) }
    }
}
