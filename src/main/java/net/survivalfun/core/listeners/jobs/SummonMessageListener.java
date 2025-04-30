package net.survivalfun.core.listeners.jobs;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class SummonMessageListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        // Get the message as plain text
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Check if it's a summon message
        if (plainMessage.contains("[Server: Summoned")) {
            // Cancel the event to prevent the message from being sent
            event.setCancelled(true);
        }
    }
}
