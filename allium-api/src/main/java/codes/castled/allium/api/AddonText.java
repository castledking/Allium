package codes.castled.allium.api;

import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Allium's text formatting: {@code &} and {@code §} codes, {@code &#RRGGBB} hex, and
 * {@code <gradient:#a:#b>} / {@code <rainbow>} tags. Players on Paper get components, others legacy
 * strings, with the same visible result.
 */
public interface AddonText {

    void send(CommandSender recipient, String text);

    /** Replaces each {@code {key}} with its value before formatting. */
    void send(CommandSender recipient, String text, Map<String, String> placeholders);

    /** The formatted text as a legacy {@code §} string (for item names, lore and other plain-string APIs). */
    String legacy(String text);
}
