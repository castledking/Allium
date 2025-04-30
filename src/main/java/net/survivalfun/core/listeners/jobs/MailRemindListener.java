package net.survivalfun.core.listeners.jobs;

import net.survivalfun.core.commands.utils.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

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