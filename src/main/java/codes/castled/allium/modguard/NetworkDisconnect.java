package codes.castled.allium.modguard;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Disconnects a player from this server, or through a BungeeCord-compatible proxy from the whole network. */
final class NetworkDisconnect {
    static final String CHANNEL = "BungeeCord";

    private Mode mode = Mode.AUTO;
    BooleanSupplier proxyDetected = () -> {
        try {
            return Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
        } catch (Throwable unavailable) {
            return false;
        }
    };
    Sender sender;

    NetworkDisconnect(Plugin plugin) {
        this.sender = (player, payload) -> player.sendPluginMessage(plugin, CHANNEL, payload);
    }

    void configure(Mode next) {
        mode = next;
    }

    boolean usesProxy() {
        return mode == Mode.PROXY || mode == Mode.AUTO && proxyDetected.getAsBoolean();
    }

    /** The proxy's KickPlayerRaw message: the player's name and the reason as a chat component. */
    static byte[] packet(String name, String legacyReason) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("KickPlayerRaw");
            out.writeUTF(name);
            out.writeUTF(ComponentSerializer.toString(TextComponent.fromLegacyText(legacyReason)));
            return bytes.toByteArray();
        } catch (IOException tooLarge) {
            throw new IllegalArgumentException("Disconnect message too large", tooLarge);
        }
    }

    void disconnect(Player player, String legacyReason) {
        if (!usesProxy()) {
            player.kickPlayer(legacyReason);
            return;
        }
        try {
            sender.send(player, packet(player.getName(), legacyReason));
        } catch (Throwable failed) {
            player.kickPlayer(legacyReason);
        }
    }

    @FunctionalInterface
    interface Sender {
        void send(Player player, byte[] payload);
    }

    enum Mode {
        AUTO, PROXY, SERVER;

        static Mode parse(String value) {
            if (value == null) return AUTO;
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return AUTO;
            }
        }
    }
}
