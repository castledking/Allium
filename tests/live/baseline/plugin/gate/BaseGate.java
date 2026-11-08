package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.item.ResolutionStatus;
import com.google.gson.GsonBuilder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Baseline gate fixture. Runs Allium's item contract on the server's real item, metadata and PDC
 * implementation, stores signed items in a chest so a restart can verify them, and exports packet
 * IDs for the raw protocol client. It never changes Allium's configuration.
 */
public final class BaseGate extends JavaPlugin {

    private static final String DISARMER = "allium:mob_disarmer";
    private static final String HANDCUFFS = "allium:handcuffs";
    private static final NamespacedKey GATE_ID = new NamespacedKey("basegate", "id");
    private ItemService items;

    /** A test-only provider so appearance swaps can be exercised on lanes without Nexo/CE/Oraxen. */
    private static final class GateProvider implements ItemProviderAdapter {
        @Override public String providerName() { return "basegate"; }
        @Override public ProviderStatus status() { return ProviderStatus.READY; }
        @Override public ResolutionResult createItem(String id) {
            if (!"cuffs".equals(id)) {
                return ResolutionResult.unknownId(ProviderKey.of("basegate", id), "not a gate item");
            }
            ItemStack stack = new ItemStack(Material.IRON_SHOVEL);
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(GATE_ID, PersistentDataType.STRING, id);
            stack.setItemMeta(meta);
            return ResolutionResult.resolved(ProviderKey.of("basegate", id), stack);
        }
        @Override public Optional<String> identifyItemId(ItemStack stack) {
            ItemMeta meta = stack.getItemMeta();
            return meta == null ? Optional.empty() : Optional.ofNullable(meta.getPersistentDataContainer().get(GATE_ID, PersistentDataType.STRING));
        }
        @Override public boolean hasAuthoritativeNativeIdCheck() { return true; }
    }

    @Override
    public void onEnable() {
        items = ((PluginStart) getServer().getPluginManager().getPlugin("Allium")).getItemService();
        items.registerProvider(new GateProvider());
        items.bindAppearanceToBehavior(ProviderKey.of("basegate", "cuffs"), HANDCUFFS);
        try {
            write("wire-ids", Map.of("wirePackets", WireIds.dump()));
        } catch (Throwable e) {
            getLogger().info("BASEG wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("BASEG-GATE READY " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            return true;
        }
        String text = String.join(" ", args);
        try {
            Object result = switch (args[0]) {
                case "contract" -> contract();
                case "store" -> store();
                case "verify" -> verify();
                default -> throw new IllegalArgumentException("unknown action " + args[0]);
            };
            getLogger().info("BASEG-DONE " + text + " => " + new GsonBuilder().serializeNulls().create().toJson(result));
        } catch (Throwable e) {
            getLogger().info("BASEG-FAIL " + text + " => " + e);
        }
        return true;
    }

    // ---- item contract ----

    private ItemStack mint(String ref) {
        ResolutionResult result = items.create(items.parseRef(ref));
        return result.itemStack().orElseThrow(() -> new IllegalStateException(ref + ": " + result.status()));
    }

    private static PersistentDataContainer pdc(ItemMeta meta) {
        return meta.getPersistentDataContainer();
    }

    private static String pdcString(ItemStack stack, NamespacedKey key) {
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : pdc(meta).get(key, PersistentDataType.STRING);
    }

    private ItemStack editPdc(ItemStack original, java.util.function.Consumer<PersistentDataContainer> edit) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        edit.accept(pdc(meta));
        copy.setItemMeta(meta);
        return copy;
    }

    private Map<String, Object> describe(ItemStack stack) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("material", stack.getType().name());
        out.put("amount", stack.getAmount());
        ItemRef ref = items.identify(stack).orElse(null);
        out.put("appearance", ref == null ? null : ref.appearance().toString());
        out.put("behavior", ref == null ? null : ref.behaviorId().orElse(null));
        out.put("state", ref == null ? Map.of() : ref.stateData());
        ItemMeta meta = stack.getItemMeta();
        out.put("schemaVersion", meta == null ? null : pdc(meta).get(ItemService.KEY_SCHEMA_VERSION, PersistentDataType.INTEGER));
        out.put("instanceUuid", pdcString(stack, ItemService.KEY_INSTANCE_UUID));
        out.put("signature", pdcString(stack, ItemService.KEY_SIGNATURE));
        return out;
    }

    private final List<Map<String, Object>> checks = new ArrayList<>();

    private void check(String name, boolean ok, Object detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("ok", ok);
        row.put("detail", detail);
        checks.add(row);
    }

    private Object contract() {
        checks.clear();
        ItemStack disarmer = mint(DISARMER);
        Map<String, Object> d = describe(disarmer);
        String uuid = (String) d.get("instanceUuid");
        String sig = (String) d.get("signature");
        check("create: allium:mob_disarmer carries schema 1, behavior, instance UUID and a v2 signature",
                DISARMER.equals(d.get("appearance")) && DISARMER.equals(d.get("behavior")) && Integer.valueOf(1).equals(d.get("schemaVersion"))
                        && uuid != null && sig != null && sig.startsWith("v2:"), d);

        ItemRef ref = items.identify(disarmer).orElseThrow();
        ItemRef back = ItemRef.deserialize(ref.serialize());
        ItemStack rebuilt = items.create(back).itemStack().orElse(null);
        Map<String, Object> r = rebuilt == null ? Map.of() : describe(rebuilt);
        check("ItemRef serialize/deserialize round-trips and rebuilds the same identity",
                back.equals(ref) && rebuilt != null && DISARMER.equals(r.get("behavior")) && ref.stateData().equals(r.get("state")),
                Map.of("serialized", ref.serialize(), "rebuilt", r));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", disarmer);
        ItemStack fromYaml;
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.loadFromString(yaml.saveToString());
            fromYaml = loaded.getItemStack("item");
        } catch (Exception e) {
            fromYaml = null;
        }
        Map<String, Object> y = fromYaml == null ? Map.of() : describe(fromYaml);
        check("Bukkit YAML serialization keeps the signed identity (same UUID and MAC, still verified)",
                fromYaml != null && DISARMER.equals(y.get("behavior")) && uuid.equals(y.get("instanceUuid")) && sig.equals(y.get("signature")), y);

        Map<String, Object> c = describe(disarmer.clone());
        ItemStack two = items.cloneItem(disarmer, 2);
        Map<String, Object> c2 = describe(two);
        check("clone and cloneItem keep a verified identity",
                DISARMER.equals(c.get("behavior")) && uuid.equals(c.get("instanceUuid")) && DISARMER.equals(c2.get("behavior"))
                        && Integer.valueOf(2).equals(c2.get("amount")), Map.of("clone", c, "cloneItem", c2));

        ItemStack spent = disarmer.clone();
        boolean updated = items.updateState(spent, Map.of("charges", "1"));
        Map<String, Object> s = describe(spent);
        check("updateState re-signs: the new state verifies, with the same UUID and a new MAC",
                updated && DISARMER.equals(s.get("behavior")) && "1".equals(((Map<?, ?>) s.get("state")).get("charges"))
                        && uuid.equals(s.get("instanceUuid")) && !sig.equals(s.get("signature")), s);

        ItemStack stateEdited = editPdc(spent, p -> p.set(new NamespacedKey("allium", ItemService.STATE_KEY_PREFIX + "charges"), PersistentDataType.STRING, "3"));
        Map<String, Object> se = describe(stateEdited);
        check("a state edited without re-signing is rejected (plain golden hoe)",
                se.get("behavior") == null && "minecraft:golden_hoe".equals(se.get("appearance")), se);
        boolean launder = items.updateState(stateEdited, Map.of("charges", "2"));
        check("updateState refuses to launder a rejected stack", !launder && describe(stateEdited).get("behavior") == null, describe(stateEdited));

        ItemStack tampered = editPdc(disarmer, p -> p.set(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING, UUID.randomUUID().toString()));
        Map<String, Object> t = describe(tampered);
        check("a tampered identity (instance UUID changed) is rejected", t.get("behavior") == null, t);

        ItemStack cuffs = mint(HANDCUFFS);
        Map<String, Object> h = describe(cuffs);
        check("privileged allium:handcuffs is minted signed", HANDCUFFS.equals(h.get("behavior")) && h.get("signature") != null, h);
        ItemStack unsigned = editPdc(cuffs, p -> p.remove(ItemService.KEY_SIGNATURE));
        Map<String, Object> u = describe(unsigned);
        check("privileged handcuffs without a signature are rejected", u.get("behavior") == null, u);

        String cuffUuid = (String) h.get("instanceUuid");
        ItemHmacSigner otherNetwork = ItemHmacSigner.randomEphemeral("forged");
        ItemStack foreign = editPdc(cuffs, p -> p.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING,
                otherNetwork.sign("allium", "handcuffs", HANDCUFFS, cuffUuid, Map.of())));
        Map<String, Object> f = describe(foreign);
        check("handcuffs signed with an unknown key ID are rejected", f.get("behavior") == null, f);
        ItemHmacSigner sameIdOtherSecret = ItemHmacSigner.randomEphemeral(ItemHmacSigner.DEFAULT_KEY_ID);
        ItemStack forged = editPdc(cuffs, p -> p.set(ItemService.KEY_SIGNATURE, PersistentDataType.STRING,
                sameIdOtherSecret.sign("allium", "handcuffs", HANDCUFFS, cuffUuid, Map.of())));
        Map<String, Object> fg = describe(forged);
        check("handcuffs forged under the active key ID with another secret are rejected", fg.get("behavior") == null, fg);

        ResolutionResult swap = items.swapOrRefreshAppearance(cuffs, ProviderKey.of("basegate", "cuffs"));
        Map<String, Object> sw = swap.itemStack().map(this::describe).orElse(Map.of("status", swap.status().name()));
        check("an appearance swap keeps the behavior and instance UUID and re-signs",
                "basegate:cuffs".equals(sw.get("appearance")) && HANDCUFFS.equals(sw.get("behavior")) && cuffUuid.equals(sw.get("instanceUuid"))
                        && "IRON_SHOVEL".equals(sw.get("material")), sw);

        ItemStack plainHoe = new ItemStack(Material.GOLDEN_HOE);
        ItemStack spoof = new ItemStack(Material.GOLDEN_HOE);
        ItemMeta spoofMeta = spoof.getItemMeta();
        spoofMeta.setDisplayName("§6Mob Disarmer");
        Integer model = null;
        ItemMeta disarmerMeta = disarmer.getItemMeta();
        if (disarmerMeta.hasCustomModelData()) {
            spoofMeta.setCustomModelData(disarmerMeta.getCustomModelData());
            model = disarmerMeta.getCustomModelData();
        }
        spoof.setItemMeta(spoofMeta);
        check("strict carriers: a disarmer is not minecraft:golden_hoe, and plain or dressed golden hoes are not disarmers",
                !items.matches(disarmer, items.parseRef("minecraft:golden_hoe")) && !items.matches(plainHoe, items.parseRef(DISARMER))
                        && items.identifyBehavior(spoof).isEmpty() && items.matches(spoof, items.parseRef("minecraft:golden_hoe")),
                Map.of("spoofModelData", String.valueOf(model)));

        ResolutionResult unknownBare = items.create(items.parseRef("no_such_item_xyz"));
        ResolutionResult unknownAllium = items.create(items.parseRef("allium:no_such_item"));
        ResolutionResult stone = items.create(items.parseRef("stone"));
        check("no AIR fallback: unknown references resolve to nothing, and bare names resolve to vanilla",
                unknownBare.itemStack().isEmpty() && unknownBare.status() != ResolutionStatus.RESOLVED
                        && unknownAllium.itemStack().isEmpty() && unknownAllium.status() == ResolutionStatus.UNKNOWN_ID
                        && stone.itemStack().map(i -> i.getType() == Material.STONE).orElse(false)
                        && "minecraft:stone".equals(items.parseRef("stone").appearance().toString()),
                Map.of("bare", unknownBare.status().name(), "allium", unknownAllium.status().name(), "stone", stone.status().name()));
        return new ArrayList<>(checks);
    }

    // ---- persistence across a restart ----

    private Block chestBlock() {
        World world = getServer().getWorlds().get(0);
        Location spawn = world.getSpawnLocation();
        world.getChunkAt(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4).load(true);
        return world.getBlockAt(spawn.getBlockX() + 2, world.getMinHeight() + 4, spawn.getBlockZ() + 2);
    }

    private Object store() throws IOException {
        List<ItemStack> stored = new ArrayList<>();
        ItemStack disarmer = mint(DISARMER);
        stored.add(disarmer);
        ItemStack spent = mint(DISARMER);
        items.updateState(spent, Map.of("charges", "1"));
        stored.add(spent);
        ItemStack cuffs = mint(HANDCUFFS);
        stored.add(cuffs);
        stored.add(items.swapOrRefreshAppearance(mint(HANDCUFFS), ProviderKey.of("basegate", "cuffs")).itemStack().orElseThrow());
        stored.add(editPdc(spent, p -> p.set(new NamespacedKey("allium", ItemService.STATE_KEY_PREFIX + "charges"), PersistentDataType.STRING, "3")));
        Block block = chestBlock();
        block.setType(Material.CHEST);
        Inventory inventory = ((Chest) block.getState()).getInventory();
        inventory.clear();
        List<Map<String, Object>> expected = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            inventory.setItem(i, stored.get(i));
            expected.add(describe(stored.get(i)));
        }
        getServer().getWorlds().get(0).save();
        write("stored", expected);
        return expected;
    }

    private Object verify() {
        Inventory inventory = ((Chest) chestBlock().getState()).getInventory();
        List<Map<String, Object>> found = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ItemStack stack = inventory.getItem(i);
            found.add(stack == null ? Map.of("slot", i, "empty", true) : describe(stack));
        }
        return found;
    }

    private void write(String name, Object value) throws IOException {
        Path dir = getDataFolder().toPath();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".json.tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Packet IDs for the raw 26.3 client, from the running server's protocol catalog. */
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
                                if (method.getName().equals("accept")) {
                                    ids.put(packetType.getMethod("id").invoke(a[0]).toString(), (Integer) a[1]);
                                }
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
