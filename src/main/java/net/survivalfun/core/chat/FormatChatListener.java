package net.survivalfun.core.chat;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class FormatChatListener implements Listener {

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Create a formatted message using Bungee ChatColor
        TextComponent formattedMessage = new TextComponent(
                ChatColor.GREEN + player.getName() + ": " + ChatColor.WHITE + message
        );

        event.setCancelled(true);

        // Broadcast to all players
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(formattedMessage);
        }
    }
}