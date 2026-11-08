package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.module.MessagingModule;
import com.google.gson.GsonBuilder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S07 fixture: vanishes players through the common "vanished" metadata (as vanish plugins do),
 * mutes them through Allium's MuteSource hook (as moderation will), grants permissions and arms
 * faults. It never sends messages itself.
 */
public final class MsgGate extends JavaPlugin {

    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                    new GsonBuilder().create().toJson(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            getLogger().info("MSGG wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        messaging().messaging().setMuteSource(muted::contains);
        getLogger().info("MSGG-GATE READY " + getServer().getBukkitVersion());
    }

    private MessagingModule messaging() {
        return (MessagingModule) ((PluginStart) getServer().getPluginManager().getPlugin("Allium")).getModuleManager().getModule("messaging");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String text = String.join(" ", args);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Player target = args.length > 1 ? getServer().getPlayerExact(args[1]) : null;
            switch (args[0]) {
                case "vanish" -> {
                    if (args[2].equals("on")) target.setMetadata("vanished", new FixedMetadataValue(this, true));
                    else target.removeMetadata("vanished", this);
                    out.put("vanished", target.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean()));
                }
                case "mute" -> {
                    if (args[2].equals("on")) muted.add(target.getUniqueId());
                    else muted.remove(target.getUniqueId());
                    out.put("muted", muted.contains(target.getUniqueId()));
                }
                case "perm" -> {
                    PermissionAttachment attachment = attachments.computeIfAbsent(target.getUniqueId(), id -> target.addAttachment(this));
                    if (args[3].equals("unset")) attachment.unsetPermission(args[2]);
                    else attachment.setPermission(args[2], Boolean.parseBoolean(args[3]));
                    target.updateCommands();
                    out.put(args[2], target.hasPermission(args[2]));
                }
                case "fault" -> {
                    FaultInjection.arm(args[1], Integer.parseInt(args[2]));
                    out.put("armed", args[1]);
                }
                default -> throw new IllegalArgumentException("unknown action " + args[0]);
            }
            getLogger().info("MSGG-DONE " + text + " => " + new GsonBuilder().serializeNulls().create().toJson(out));
        } catch (Throwable e) {
            getLogger().info("MSGG-FAIL " + text + " => " + e);
        }
        return true;
    }

    static final class WireIds {
        static Map<String, Object> dump() throws Exception {
            Map<String, Object> result = new LinkedHashMap<>();
            Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
            Class<?> detailsType = Class.forName("net.minecraft.network.ProtocolInfo$Details");
            Class<?> visitor = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
            Class<?> packetType = Class.forName("net.minecraft.network.protocol.PacketType");
            for (String phase : List.of("game", "configuration", "login")) {
                String capital = phase.substring(0, 1).toUpperCase() + phase.substring(1);
                Class<?> protocols = Class.forName("net.minecraft.network.protocol." + phase + "." + capital + "Protocols");
                for (String flow : List.of("CLIENTBOUND", "SERVERBOUND")) {
                    Object template = protocols.getField(flow + "_TEMPLATE").get(null);
                    Object details = provider.getMethod("details").invoke(template);
                    Map<String, Integer> ids = new LinkedHashMap<>();
                    Object callback = java.lang.reflect.Proxy.newProxyInstance(visitor.getClassLoader(), new Class[]{visitor},
                            (proxy, method, a) -> {
                                if (method.getName().equals("accept")) ids.put(packetType.getMethod("id").invoke(a[0]).toString(), (Integer) a[1]);
                                return null;
                            });
                    detailsType.getMethod("listPackets", visitor).invoke(details, callback);
                    result.put(phase + "_" + flow, ids);
                }
            }
            return result;
        }
    }
}
