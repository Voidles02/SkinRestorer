package com.kodari.skinrestorer

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SkinCommand(private val manager: SkinManager) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val subcommand = args[0].lowercase(java.util.Locale.ROOT)
        val permission = "skinsrestorer.command.$subcommand"
        if (subcommand !in SUBCOMMANDS) {
            sendUsage(sender)
            return true
        }
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("${ChatColor.RED}You do not have permission to use this command.")
            return true
        }

        when (subcommand) {
            "set" -> setSkin(sender, args)
            "url" -> setUrl(sender, args)
            "clear" -> withTarget(sender, args.getOrNull(1), args.size in 1..2) { manager.clear(it) }
            "update" -> withTarget(sender, args.getOrNull(1), args.size in 1..2) { player ->
                manager.update(player) { result -> report(sender, result, "Skin updated.") }
            }
            "random" -> withTarget(sender, args.getOrNull(1), args.size in 1..2) { player ->
                manager.setRandom(player) { result -> report(sender, result, "Random skin applied.") }
            }
        }
        return true
    }

    private fun setSkin(sender: CommandSender, args: Array<out String>) {
        if (args.size !in 2..3) {
            sender.sendMessage("${ChatColor.YELLOW}Usage: /skin set <skinName> [player]")
            return
        }
        val target = resolveTarget(sender, args.getOrNull(2)) ?: return
        manager.setByName(target, args[1]) { result ->
            report(sender, result, "Skin applied to ${target.name}.")
        }
    }

    private fun setUrl(sender: CommandSender, args: Array<out String>) {
        if (args.size !in 2..4) {
            sender.sendMessage("${ChatColor.YELLOW}Usage: /skin url <url> [classic|slim] [player]")
            return
        }
        val thirdArgument = args.getOrNull(2)
        val model = if (thirdArgument.equals("classic", true) || thirdArgument.equals("slim", true)) {
            thirdArgument!!.lowercase(java.util.Locale.ROOT)
        } else {
            "classic"
        }
        if (model !in setOf("classic", "slim")) {
            sender.sendMessage("${ChatColor.RED}Model must be classic or slim.")
            return
        }
        val targetName = when {
            args.size == 4 -> args[3]
            args.size == 3 && model == "classic" && !thirdArgument.equals("classic", true) -> thirdArgument
            else -> null
        }
        if (args.size == 4 && model == "classic" && thirdArgument.equals("slim", true).not() &&
            thirdArgument.equals("classic", true).not()) {
            sender.sendMessage("${ChatColor.YELLOW}Usage: /skin url <url> [classic|slim] [player]")
            return
        }
        val player = resolveTarget(sender, targetName) ?: return
        manager.setUrl(player, args[1], model) { result ->
            report(sender, result, "Skin applied to ${player.name}.")
        }
    }

    private fun withTarget(
        sender: CommandSender,
        targetName: String?,
        validArgs: Boolean,
        action: (Player) -> Unit
    ) {
        if (!validArgs) {
            sendUsage(sender)
            return
        }
        resolveTarget(sender, targetName)?.let(action)
    }

    private fun resolveTarget(sender: CommandSender, targetName: String?): Player? {
        if (targetName == null) {
            return sender as? Player ?: run {
                sender.sendMessage("${ChatColor.RED}Console must specify a target player.")
                null
            }
        }
        if (!sender.hasPermission("skinsrestorer.admin")) {
            sender.sendMessage("${ChatColor.RED}You need skinsrestorer.admin to change another player's skin.")
            return null
        }
        return Bukkit.getPlayerExact(targetName) ?: run {
            sender.sendMessage("${ChatColor.RED}That player is not online.")
            null
        }
    }

    private fun report(sender: CommandSender, result: Result<SkinData>, success: String) {
        result.fold(
            { sender.sendMessage("${ChatColor.GREEN}$success") },
            { sender.sendMessage("${ChatColor.RED}Could not apply skin: ${it.message ?: "request failed"}") }
        )
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage("${ChatColor.YELLOW}/skin set <skinName> [player]")
        sender.sendMessage("${ChatColor.YELLOW}/skin url <url> [classic|slim] [player]")
        sender.sendMessage("${ChatColor.YELLOW}/skin <clear|update|random> [player]")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) return SUBCOMMANDS.filter { it.startsWith(args[0], true) }
        if (args.size == 3 && args[0].equals("url", true)) {
            val suggestions = mutableListOf("classic", "slim")
            if (sender.hasPermission("skinsrestorer.admin")) {
                suggestions += Bukkit.getOnlinePlayers().map { it.name }
            }
            return suggestions.filter { it.startsWith(args[2], true) }
        }
        if (args.size == 3 && args[0].equals("set", true) && sender.hasPermission("skinsrestorer.admin")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) }
        }
        if (args.size == 2 && args[0].lowercase() in setOf("clear", "update", "random") &&
            sender.hasPermission("skinsrestorer.admin")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
        }
        if (args.size == 4 && args[0].equals("url", true) && sender.hasPermission("skinsrestorer.admin")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[3], true) }
        }
        return emptyList()
    }

    companion object {
        private val SUBCOMMANDS = listOf("set", "url", "clear", "update", "random")
    }
}