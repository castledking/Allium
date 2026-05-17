package codes.castled.allium.listeners.jobs;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import codes.castled.allium.commands.Msg;
import codes.castled.allium.managers.core.Text;

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