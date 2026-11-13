package codes.castled.allium.naming;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

/** Request-time visibility for online targets, shared by online and known-name suggestions. */
public final class PlayerNameVisibility {
    private PlayerNameVisibility() {
    }

    public static boolean canSee(CommandSender sender, Player target) {
        if (!(sender instanceof Player viewer)) {
            return true;
        }
        if (!viewer.canSee(target)) {
            return false;
        }
        if (sender.hasPermission("allium.vanish.see")) {
            return true;
        }
        var values = target.getMetadata("vanished");
        if (values != null) {
            for (MetadataValue value : values) {
                if (value != null && value.asBoolean()) {
                    return false;
                }
            }
        }
        return true;
    }
}
