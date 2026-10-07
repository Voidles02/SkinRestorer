package com.kodari.skinrestorer

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SkinStorage(private val plugin: JavaPlugin) {
    private val file = File(plugin.dataFolder, "skins.json")
    private val temporaryFile = File(plugin.dataFolder, "skins.json.tmp")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private val saveLock = Any()
    private val entries = mutableMapOf<UUID, SkinData>()
    private var dirty = false
    private var saveScheduled = false

    init {
        load()
    }

    fun get(playerId: UUID): SkinData? = synchronized(lock) { entries[playerId] }

    fun put(playerId: UUID, skin: SkinData) {
        update { entries[playerId] = skin }
    }

    fun remove(playerId: UUID) {
        update { entries.remove(playerId) }
    }

    fun flush() {
        synchronized(saveLock) {
            while (true) {
                val snapshot = synchronized(lock) {
                    dirty = false
                    entries.toMap()
                }
                save(snapshot)
                val complete = synchronized(lock) {
                    if (dirty) false else {
                        saveScheduled = false
                        true
                    }
                }
                if (complete) return
            }
        }
    }

    private fun load() {
        if (!file.isFile) return

        try {
            val type = object : TypeToken<Map<String, SkinData>>() {}.type
            val loaded: Map<String, SkinData> = gson.fromJson(file.readText(), type) ?: return
            loaded.forEach { (id, skin) ->
                runCatching { UUID.fromString(id) }.getOrNull()?.let { entries[it] = skin }
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Could not load skins.json: ${exception.message}")
        }
    }

    private fun update(change: () -> Unit) {
        val scheduleSave = synchronized(lock) {
            change()
            dirty = true
            if (saveScheduled) false else {
                saveScheduled = true
                true
            }
        }
        if (scheduleSave) {
            runCatching {
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { savePending() })
            }.onFailure { exception ->
                synchronized(lock) { saveScheduled = false }
                plugin.logger.warning("Could not schedule skins.json save: ${exception.message}")
            }
        }
    }

    private fun savePending() {
        while (true) {
            val hasMore = synchronized(saveLock) {
                val snapshot = synchronized(lock) {
                    dirty = false
                    entries.toMap()
                }
                save(snapshot)
                synchronized(lock) {
                    if (dirty) false else {
                        saveScheduled = false
                        true
                    }
                }
            }
            if (hasMore) return
        }
    }

    private fun save(snapshot: Map<UUID, SkinData>) {
        try {
            plugin.dataFolder.mkdirs()
            val json = snapshot.entries.associate { it.key.toString() to it.value }
            temporaryFile.writeText(gson.toJson(json))
            try {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: Exception) {
            plugin.logger.severe("Could not save skins.json: ${exception.message}")
        }
    }
}