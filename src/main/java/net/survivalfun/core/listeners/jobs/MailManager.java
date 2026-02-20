package net.survivalfun.core.listeners.jobs;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import net.survivalfun.core.commands.Msg;

public class MailManager implements Listener {

    private final Msg messageHandler;

    public MailManager(Msg messageHandler) {
        this.messageHandler = messageHandler;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Always delegate to message handler; it will handle permission checks with a delay
        messageHandler.deliverOfflineMessages(player);
    }
}