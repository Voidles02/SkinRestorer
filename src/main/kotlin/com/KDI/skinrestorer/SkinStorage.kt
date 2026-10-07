package com.KDI.skinrestorer

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
        val normalizedSkin = skin.copy(automaticallyRestored = true)
        update {
            if (entries[playerId] == normalizedSkin) false else {
                entries[playerId] = normalizedSkin
                true
            }
        }
    }

    fun remove(playerId: UUID) {
        update { entries.remove(playerId) != null }
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
            var needsMigration = false
            loaded.forEach { (id, skin) ->
                runCatching { UUID.fromString(id) }.getOrNull()?.let { playerId ->
                    if (!skin.automaticallyRestored) needsMigration = true
                    entries[playerId] = skin.copy(automaticallyRestored = true)
                }
            }
            if (needsMigration) {
                val shouldSchedule = synchronized(lock) {
                    dirty = true
                    if (saveScheduled) false else {
                        saveScheduled = true
                        true
                    }
                }
                if (shouldSchedule) scheduleSave()
            }
        } catch (exception: Exception) {
            plugin.logger.warning("Could not load skins.json: ${exception.message}")
        }
    }

    private fun update(change: () -> Boolean) {
        val scheduleSave = synchronized(lock) {
            if (!change()) return
            dirty = true
            if (saveScheduled) false else {
                saveScheduled = true
                true
            }
        }
        if (scheduleSave) {
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        runCatching {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { savePending() })
        }.onFailure { exception ->
            synchronized(lock) { saveScheduled = false }
            plugin.logger.warning("Could not schedule skins.json save: ${exception.message}")
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
            Files.newBufferedWriter(temporaryFile.toPath(), Charsets.UTF_8).use { output ->
                val json = gson.newJsonWriter(output)
                json.beginObject()
                snapshot.forEach { (playerId, skin) ->
                    json.name(playerId.toString())
                    gson.toJson(skin, SkinData::class.java, json)
                }
                json.endObject()
                json.flush()
            }
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