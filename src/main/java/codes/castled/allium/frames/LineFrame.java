package codes.castled.allium.frames;

import java.util.List;
import net.kyori.adventure.text.Component;

/** A frame that wraps lines of text, for item lore or for a chat hover. */
public interface LineFrame {

    /** Item lore, title first: the frame's lines, header and bottom included. */
    List<Component> item(List<Component> lines);

    /** A hover's lines, top first, framed to cover vanilla's hover panel. */
    List<Component> hover(List<Component> lines);
}
