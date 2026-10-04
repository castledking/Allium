package codes.castled.allium.listeners.chat;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import codes.castled.allium.managers.chat.ChatColorParser;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

/**
 * Colours signs with the same rules chat gets: legacy {@code &a} codes and MiniMessage tags can
 * be mixed on one line, and each is gated on the matching {@code allium.sign} permission. The
 * parsing lives in {@link ChatColorParser} under the {@code allium.sign} scope, so a wildcard
 * grant like {@code allium.sign.color.*} reaches every legacy colour.
 */
public class SignColorListener implements Listener {

    /** Permission root for sign colouring; chat uses {@link ChatColorParser#CHAT} for the same parser. */
    private static final String SCOPE = "allium.sign";

    /**
     * The line with its styling spelled back out as {@code &} codes. A typed line arrives as
     * literal text, but another plugin may already have styled the component, and serializing
     * rather than flattening keeps that styling in play for the permission pass to judge.
     */
    private static final LegacyComponentSerializer TYPED = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private final boolean placeholderAPIEnabled;

    public SignColorListener() {
        this.placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        ChatColorParser.PermissionCheck permissions = player::hasPermission;

        // Paper scopes the event to the side being edited, so these four lines are that side.
        for (int i = 0; i < 4; i++) {
            String line = TYPED.serialize(event.line(i));
            if (line == null || line.isEmpty()) {
                continue;
            }

            String source = ChatColorParser.toMiniMessage(permissions, processPlaceholders(player, line), SCOPE);
            if (!source.equals(line)) {
                Text.sendDebugLog(INFO, player.getName() + " - Line " + i + ": '" + line + "' -> '" + source + "'");
            }

            event.line(i, ChatColorParser.deserialize(source));
        }
    }

    /**
     * Processes PlaceholderAPI placeholders in a message if the player has permission
     * @param player The player creating/editing the sign
     * @param message The message to process
     * @return The processed message
     */
    private String processPlaceholders(Player player, String message) {
        if (!placeholderAPIEnabled || !player.hasPermission("allium.sign.placeholderapi")) {
            return message;
        }

        return PlaceholderAPI.setPlaceholders(player, message);
    }
}
