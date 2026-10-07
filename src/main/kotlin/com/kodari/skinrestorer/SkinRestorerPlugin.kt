package com.kodari.skinrestorer

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

class SkinRestorerPlugin : JavaPlugin(), Listener {
    private lateinit var skinManager: SkinManager
    private lateinit var skinStorage: SkinStorage
    private lateinit var skinService: MojangSkinService

    override fun onEnable() {
        skinStorage = SkinStorage(this)
        skinService = MojangSkinService()
        skinManager = SkinManager(this, skinService, skinStorage)
        val skinCommand = SkinCommand(skinManager)
        getCommand("skin")?.setExecutor(skinCommand)
        getCommand("skin")?.tabCompleter = skinCommand
        server.pluginManager.registerEvents(this, this)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        skinManager.onJoin(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        skinManager.onQuit(event.player)
    }

    override fun onDisable() {
        if (::skinStorage.isInitialized) skinStorage.flush()
        if (::skinService.isInitialized) skinService.shutdown()
    }
}