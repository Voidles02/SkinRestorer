package com.kodari.skinrestorer

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import com.destroystokyo.paper.profile.PlayerProfile as PaperPlayerProfile
import org.bukkit.profile.PlayerTextures
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.util.UUID

class SkinManager(
    private val plugin: JavaPlugin,
    private val service: MojangSkinService,
    private val storage: SkinStorage
) {
    private val originalProfiles = mutableMapOf<UUID, PaperPlayerProfile>()
    private val pendingRequests = mutableMapOf<UUID, Any>()

    fun onJoin(player: Player) {
        originalProfiles.putIfAbsent(player.uniqueId, createPaperProfile(player))
        val saved = storage.get(player.uniqueId)
        if (saved != null && (!saved.automaticallyRestored || !plugin.server.onlineMode)) {
            val restored = runCatching { apply(player, saved) }.onFailure { exception ->
                plugin.logger.warning("Could not restore ${player.name}'s saved skin: ${exception.message}")
                storage.remove(player.uniqueId)
                restoreDefault(player)
            }.isSuccess
            if (!plugin.server.onlineMode && saved.automaticallyRestored) {
                refreshOfflineSkin(player, saved.sourceName ?: player.name, fallbackOnFailure = false)
                return
            }
            if (restored || plugin.server.onlineMode) return
        }
        if (!plugin.server.onlineMode) {
            refreshOfflineSkin(player, player.name, fallbackOnFailure = true)
        }
    }

    fun onQuit(player: Player) {
        pendingRequests.remove(player.uniqueId)
        originalProfiles.remove(player.uniqueId)
    }

    fun setByName(player: Player, name: String, completion: (Result<SkinData>) -> Unit) {
        fetchAndApply(player, persist = true, fallbackOnFailure = false, completion) {
            service.fetchByName(name)
        }
    }

    fun setUrl(player: Player, url: String, model: String, completion: (Result<SkinData>) -> Unit) {
        fetchAndApply(player, persist = true, fallbackOnFailure = false, completion) {
            service.fetchUrl(url, model)
        }
    }

    fun setRandom(player: Player, completion: (Result<SkinData>) -> Unit) {
        fetchAndApply(player, persist = true, fallbackOnFailure = false, completion) {
            service.fetchRandom()
        }
    }

    fun update(player: Player, completion: (Result<SkinData>) -> Unit) {
        val saved = storage.get(player.uniqueId)
        val playerName = player.name
        fetchAndApply(player, persist = true, fallbackOnFailure = false, completion) {
            when {
                saved?.sourceName != null -> service.fetchByName(saved.sourceName)
                saved?.sourceUrl != null -> service.fetchUrl(saved.sourceUrl, saved.model ?: "classic")
                else -> service.fetchByName(playerName)
            }
        }
    }

    fun clear(player: Player) {
        pendingRequests.remove(player.uniqueId)
        storage.remove(player.uniqueId)
        restoreDefault(player)
    }

    private fun refreshOfflineSkin(player: Player, name: String, fallbackOnFailure: Boolean) {
        fetchAndApply(player, persist = false, fallbackOnFailure = fallbackOnFailure, completion = { result ->
            result.getOrNull()?.let { storage.put(player.uniqueId, it.copy(automaticallyRestored = true)) }
        }) {
            service.fetchByName(name)
        }
    }

    private fun fetchAndApply(
        player: Player,
        persist: Boolean,
        fallbackOnFailure: Boolean,
        completion: (Result<SkinData>) -> Unit,
        fetch: () -> SkinData
    ) {
        val request = Any()
        pendingRequests[player.uniqueId] = request
        val future = try {
            service.submit(fetch)
        } catch (exception: Exception) {
            pendingRequests.remove(player.uniqueId)
            if (fallbackOnFailure) restoreDefault(player)
            completion(Result.failure(exception))
            return
        }
        future.whenComplete { skin, throwable ->
            val result = if (throwable == null) Result.success(skin) else {
                Result.failure(throwable.cause ?: throwable)
            }
            try {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (pendingRequests[player.uniqueId] !== request) return@Runnable
                    pendingRequests.remove(player.uniqueId)
                    if (!player.isOnline) {
                        completion(Result.failure(IllegalStateException("That player is no longer online.")))
                        return@Runnable
                    }
                    result.fold(
                        { skin ->
                            runCatching {
                                apply(player, skin)
                                if (persist) storage.put(player.uniqueId, skin)
                            }.fold(
                                { completion(Result.success(skin)) },
                                { exception ->
                                    if (fallbackOnFailure) restoreDefault(player)
                                    completion(Result.failure(exception))
                                }
                            )
                        },
                        { exception ->
                            if (fallbackOnFailure) restoreDefault(player)
                            completion(Result.failure(exception))
                        }
                    )
                })
            } catch (exception: Exception) {
                plugin.logger.warning("Could not schedule skin result for ${player.name}: ${exception.message}")
            }
        }
    }

    private fun apply(player: Player, skin: SkinData) {
        originalProfiles.putIfAbsent(player.uniqueId, createPaperProfile(player))
        val profile = createPaperProfile(player)
        val textures = profile.textures
        val skinModel = if (skin.model.equals("slim", true)) {
            PlayerTextures.SkinModel.SLIM
        } else {
            PlayerTextures.SkinModel.CLASSIC
        }
        textures.setSkin(URI(skin.textureUrl).toURL(), skinModel)
        profile.setTextures(textures)
        player.setPlayerProfile(profile)
        refreshViewers(player)
    }

    private fun restoreDefault(player: Player) {
        val profile = originalProfiles.remove(player.uniqueId) ?: createPaperProfile(player)
        player.setPlayerProfile(profile)
        refreshViewers(player)
    }

    private fun createPaperProfile(player: Player): PaperPlayerProfile =
        Bukkit.createProfile(player.uniqueId, player.name).apply {
            setTextures(player.playerProfile.textures)
        }

    private fun refreshViewers(player: Player) {
        val viewers = Bukkit.getOnlinePlayers().filter { it != player && it.canSee(player) }
        viewers.forEach { it.hidePlayer(plugin, player) }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            viewers.filter { it.isOnline }.forEach { it.showPlayer(plugin, player) }
        }, 1L)
    }
}