package codes.castled.allium.api;

import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.entity.Player;

import java.util.List;

/** A resource-pack addon may render the configured frame around a player's chat hover. */
public interface ChatHoverFrame {
    /** Returns a framed tooltip, or null when the provider cannot draw this frame. */
    BaseComponent[] render(Player subject, String frame, List<BaseComponent[]> lines);
}
