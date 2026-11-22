package codes.castled.crops;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

final class BlockKeys {
    private BlockKeys() {}

    static NamespacedKey at(Plugin plugin, String purpose, Block block) {
        return new NamespacedKey(plugin, purpose + "/" + (block.getX() & 15) + "/"
                + block.getY() + "/" + (block.getZ() & 15));
    }
}
