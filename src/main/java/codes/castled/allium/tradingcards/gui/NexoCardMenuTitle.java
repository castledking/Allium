package codes.castled.allium.tradingcards.gui;

import com.nexomc.nexo.utils.AdventureUtils;
import net.kyori.adventure.text.Component;

/** Only loaded after the optional Nexo plugin has been checked. */
final class NexoCardMenuTitle {
    private NexoCardMenuTitle() {}

    static Component title(String id) {
        // Parse directly: glyphFromID's Glyph return type moved packages in Nexo 1.29.
        // Calling that old descriptor prevents the parser from ever seeing the title.
        return AdventureUtils.INSTANCE.getMINI_MESSAGE()
            .deserialize("<white><shift:-10><glyph:" + id + ">");
    }
}
