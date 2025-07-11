package net.survivalfun.core.listeners.jobs;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.survivalfun.core.commands.utils.core.player.Msg;

public class MailRemindListener implements Listener {

    private final Msg messageHandler;

    public MailRemindListener(Msg messageHandler) {
        this.messageHandler = messageHandler;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Deliver offline messages to the player
        messageHandler.deliverOfflineMessages(player);
    }
}