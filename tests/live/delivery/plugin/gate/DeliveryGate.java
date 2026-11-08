package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.pack.delivery.PackDelivery;
import com.google.gson.GsonBuilder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S06c fixture: reports Allium's pack delivery state and exports packet IDs for the raw client. It never sends packs. */
public final class DeliveryGate extends JavaPlugin {

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                    new GsonBuilder().create().toJson(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            getLogger().info("DELIVG wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("DELIVG-GATE READY " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PackDelivery delivery = ((PluginStart) getServer().getPluginManager().getPlugin("Allium")).getPackDelivery();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delivery", delivery != null);
        if (delivery != null) {
            out.put("hosted", delivery.hosted().name());
            out.put("detail", delivery.hostedDetail());
            out.put("multiplePacks", delivery.multiplePacks());
            out.put("packId", PackDelivery.PACK_ID.toString());
            Map<String, Object> statuses = new LinkedHashMap<>();
            for (Player p : getServer().getOnlinePlayers()) {
                statuses.put(p.getName(), delivery.lastStatus(p.getUniqueId()));
            }
            out.put("statuses", statuses);
        }
        getLogger().info("DELIVG-STATE " + new GsonBuilder().serializeNulls().create().toJson(out));
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
