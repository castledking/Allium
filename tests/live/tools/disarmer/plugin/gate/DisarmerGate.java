package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.protection.AlliumProtectionCheckEvent;
import com.google.gson.GsonBuilder;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
import java.util.UUID;
import java.util.Optional;

/**
 * S06a validation fixture. Sets up deterministic cases from the console and records what the
 * server actually did after a real client click. It never performs a disarm itself.
 */
public final class DisarmerGate extends JavaPlugin implements Listener {

    private static final String BOT = "GateBot";
    private static final String BEHAVIOR = "allium:mob_disarmer";
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Material[] KIT = {Material.IRON_SWORD, Material.SHIELD, Material.IRON_HELMET,
            Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
    /** 1.0 and 2.0 always drop, 0.0 never: the rule makes the expected ground set exact. */
    private static final float[] CHANCES = {1.0F, 0.0F, 0.0F, 2.0F, 1.0F, 0.0F};

    private NamespacedKey marker;
    private ItemService items;
    private String veto = "none";
    private Zombie mob;
    private final List<Map<String, Object>> interactions = new ArrayList<>();
    private final List<String> deathMarkers = new ArrayList<>();
    private final List<String> spawnedMarkers = new ArrayList<>();
    private int checkEvents;
    // Phantom Obliterator cases: the spawned targets by UUID, their marker, and what happened to them.
    private static final double OB_X = 4000.5, OB_Z = 0.5;
    private final Map<UUID, String> obTargets = new LinkedHashMap<>();
    private final Map<String, String> obDeaths = new LinkedHashMap<>();
    private final Map<String, Integer> obDamage = new LinkedHashMap<>();
    private final Map<String, Integer> obSpared = new LinkedHashMap<>();
    private final List<int[]> obUnloaded = new ArrayList<>();
    /** Chunk loads since the last baseline: a use must load none. */
    private int obChunkLoads;
    private org.bukkit.permissions.PermissionAttachment attachment;

    @Override
    public void onEnable() {
        marker = new NamespacedKey(this, "piece");
        items = ((PluginStart) getServer().getPluginManager().getPlugin("Allium")).getItemService();
        getServer().getPluginManager().registerEvents(this, this);
        try {
            write("wire-ids", Map.of("wirePackets", WireIds.dump()));
        } catch (Throwable e) {
            getLogger().info("S06A wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("S06A-GATE READY " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        try {
            switch (args[0]) {
                case "prepare" -> prepare();
                case "status" -> write("status", status());
                case "setup" -> setup(args[1], args[2], args[3], args[4], args[5], args.length > 6 ? Double.parseDouble(args[6]) : 2.0);
                case "probe" -> write("probe", probe(args[1]));
                case "ob-setup" -> obSetup(args[1], args[2], args[3], args.length > 4 ? args[4] : "none");
                case "ob-observe" -> write(args[1] + "-observe", obObserve());
                case "ob-reload" -> obReload(args[1]);
                case "perm" -> perm(args[1], args[2]);
                case "observe" -> write(args[1] + "-observe", observe());
                case "kill" -> kill(args[1]);
                case "keep" -> move(0, Integer.parseInt(args[1]));
                case "take" -> move(Integer.parseInt(args[1]), 0);
                case "snapshot" -> write(args[1], inventorySnapshot());
                case "gpclaim" -> write("gpclaim", griefPreventionAdminClaim(args.length > 1 ? Integer.parseInt(args[1]) : 0));
                default -> throw new IllegalArgumentException("unknown " + args[0]);
            }
            getLogger().info("S06A-DONE " + String.join(" ", args));
        } catch (Exception e) {
            getLogger().severe("S06A-FAIL " + String.join(" ", args) + " " + e);
            e.printStackTrace();
        }
        return true;
    }

    private void prepare() {
        World world = getServer().getWorlds().get(0);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setTime(18000L);
        world.setStorm(false);
        world.setDifficulty(Difficulty.EASY);
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) entity.remove();
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String provider : List.of("nexo", "craftengine", "oraxen")) {
            out.put(provider, items.getProvider(provider).map(ItemProviderAdapter::status).map(Enum::name).orElse("UNREGISTERED"));
        }
        Player bot = getServer().getPlayerExact(BOT);
        out.put("botOnline", bot != null && bot.isOnline());
        return out;
    }

    /**
     * @param layout main | offhand | both | held (keep the current main-hand tool)
     * @param kit    full | empty
     * @param veto   none | check-event | interact | write-fault (arm one rejected item state write)
     */
    private void setup(String name, String appearance, String layout, String kit, String veto, double distance) throws IOException {
        Player bot = requireBot();
        World world = bot.getWorld();
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) entity.remove();
        }
        Location base = world.getHighestBlockAt(0, 0).getLocation().add(0.5, 1.0, 0.5);
        base.setYaw(-90.0F);
        base.setPitch(0.0F);
        bot.teleport(base);
        PlayerInventory inventory = bot.getInventory();
        inventory.setHeldItemSlot(0);
        // Pieces the bot picked up in earlier cases are test debris; keeper tools have no marker.
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (markerOf(inventory.getItem(slot)) != null) inventory.setItem(slot, null);
        }
        if (!layout.equals("held")) {
            inventory.setItem(0, null);
            inventory.setItemInOffHand(null);
            if (layout.equals("main") || layout.equals("both")) inventory.setItem(0, tool(appearance));
            if (layout.equals("offhand") || layout.equals("both")) inventory.setItemInOffHand(tool(appearance));
        }
        mob = (Zombie) world.spawnEntity(base.clone().add(distance, 0.0, 0.0), org.bukkit.entity.EntityType.ZOMBIE);
        mob.setAI(false);
        mob.setSilent(true);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        mob.setAdult();
        mob.setCanPickupItems(true);
        EntityEquipment equipment = mob.getEquipment();
        for (int i = 0; i < SLOTS.length; i++) {
            equipment.setItem(SLOTS[i], kit.equals("full") ? piece(name, i) : null);
            setChance(equipment, SLOTS[i], kit.equals("full") ? CHANCES[i] : 0.0F);
        }
        this.veto = veto;
        if (FaultInjection.enabled()) FaultInjection.reset();
        if (veto.equals("write-fault")) FaultInjection.arm(FaultInjection.ITEM_STATE_WRITE, 1);
        interactions.clear();
        deathMarkers.clear();
        spawnedMarkers.clear();
        checkEvents = 0;
        Map<String, Object> baseline = observe();
        baseline.put("entityId", mob.getEntityId());
        write(name + "-baseline", baseline);
    }

    /** A tool as its definition would issue it: the identity bound to the appearance, else the Mob Disarmer. */
    private ItemStack tool(String appearance) {
        ItemRef bound = items.refForKey(ProviderKey.parse(appearance));
        ResolutionResult result = items.create(bound.behaviorId().isPresent() ? bound : bound.withBehaviorId(BEHAVIOR));
        if (!result.isResolved()) throw new IllegalStateException(appearance + ": " + result.diagnosticMessage());
        return result.itemStack().orElseThrow();
    }

    private ItemStack piece(String name, int index) {
        ItemStack stack = new ItemStack(KIT[index]);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, name + ":" + SLOTS[index].name());
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * @param tool  an item reference (allium:phantom_obliterator, nexo:...), {@code fake} (a phantom
     *              membrane with the built-in's model data and name, no identity),
     *              {@code legacy:<variant>:<charges>:<rechargeSeconds>:<uses>:<both|custom-only>} (a
     *              pre-recode netherite sword) or {@code held} (keep the main hand)
     * @param spec  comma list of kind@distance: p (unnamed phantom), named, guard (a damage listener
     *              spares it), claimed (the protection check event denies it), zombie, unloaded (in a
     *              chunk that is then unloaded)
     */
    /** Spawns now; unloads and writes the baseline two seconds later, once the new entities are in the world. */
    private void obSetup(String name, String tool, String spec, String fault) throws IOException {
        Player bot = requireBot();
        World world = bot.getWorld();
        for (UUID id : obTargets.keySet()) {
            Entity old = getServer().getEntity(id);
            if (old != null) old.remove();
        }
        obTargets.clear();
        obDeaths.clear();
        obDamage.clear();
        obSpared.clear();
        obUnloaded.clear();
        Location base = new Location(world, OB_X, world.getHighestBlockYAt((int) Math.floor(OB_X), 0) + 1, OB_Z, -90.0F, 0.0F);
        bot.teleport(base);
        for (Entity entity : world.getNearbyEntities(base, 96, 64, 96)) {
            if (!(entity instanceof Player)) entity.remove();
        }
        PlayerInventory inventory = bot.getInventory();
        inventory.setHeldItemSlot(0);
        if (!tool.equals("held")) {
            inventory.setItem(0, obTool(tool));
        }
        int i = 0;
        for (String part : spec.equals("none") ? new String[0] : spec.split(",")) {
            String[] kd = part.split("@");
            String kind = kd[0];
            double distance = Double.parseDouble(kd[1]);
            Location at = base.clone().add(distance, 3.0, 0.0);
            if (kind.equals("unloaded")) {
                // Spawn it as a player out there would see it, then come back: its chunk loses the
                // player's ticket and unloads the normal way, with the phantom saved in it.
                bot.teleport(at.clone().add(0.0, -3.0, 0.0));
            }
            org.bukkit.entity.EntityType type = kind.equals("zombie") ? org.bukkit.entity.EntityType.ZOMBIE : org.bukkit.entity.EntityType.PHANTOM;
            org.bukkit.entity.LivingEntity mob = (org.bukkit.entity.LivingEntity) world.spawnEntity(at, type);
            mob.setAI(false);
            mob.setGravity(false);
            mob.setSilent(true);
            mob.setPersistent(true);
            mob.setRemoveWhenFarAway(false);
            if (kind.equals("named")) mob.setCustomName("Pet");
            String tag = name + ":" + (i++) + ":" + kind;
            mob.getPersistentDataContainer().set(marker, PersistentDataType.STRING, tag);
            obTargets.put(mob.getUniqueId(), tag);
            if (kind.equals("unloaded")) {
                obUnloaded.add(new int[]{at.getBlockX() >> 4, at.getBlockZ() >> 4});
                bot.teleport(base);
            }
        }
        List<Boolean> unloaded = new ArrayList<>();
        // The chunk system unloads over the next ticks, not when unloadChunk returns: ask at 2 s, look at 5 s.
        getServer().getScheduler().runTaskLater(this, () -> {
            for (int[] chunk : obUnloaded) unloaded.add(world.unloadChunk(chunk[0], chunk[1], true));
        }, 40L);
        getServer().getScheduler().runTaskLater(this, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("unloadedChunks", unloaded);
            List<Boolean> stillLoaded = new ArrayList<>();
            for (int[] chunk : obUnloaded) stillLoaded.add(world.isChunkLoaded(chunk[0], chunk[1]));
            out.put("chunksStillLoaded", stillLoaded);
            if (FaultInjection.enabled()) FaultInjection.reset();
            if (fault.equals("write-fault")) FaultInjection.arm(FaultInjection.ITEM_STATE_WRITE, 1);
            checkEvents = 0;
            obChunkLoads = 0;
            out.putAll(obObserve());
            try {
                write(name + "-baseline", out);
            } catch (IOException e) {
                getLogger().severe("S06A-FAIL ob-setup write " + e);
            }
        }, 100L);
    }

    private ItemStack obTool(String tool) {
        if (tool.equals("fake")) {
            ItemStack fake = new ItemStack(Material.PHANTOM_MEMBRANE);
            ItemMeta meta = fake.getItemMeta();
            meta.setCustomModelData(2577030);
            meta.setDisplayName("Phantom Obliterator");
            fake.setItemMeta(meta);
            return fake;
        }
        if (tool.startsWith("tampered:")) {
            // A genuine signed tool whose charge state was then edited without re-signing.
            ItemStack stack = obTool(tool.substring("tampered:".length()));
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey("allium", "state_charges"), PersistentDataType.STRING, "999");
            stack.setItemMeta(meta);
            return stack;
        }
        if (tool.startsWith("legacy:")) {
            String[] p = tool.split(":");
            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            ItemMeta meta = sword.getItemMeta();
            meta.setDisplayName("Phantom Obliterator");
            meta.setCustomModelData(1011);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.getByKey(NamespacedKey.minecraft("unbreaking")), 3, true);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(new NamespacedKey("allium", "custom_item_id"), PersistentDataType.STRING, p[1]);
            if (p[5].equals("both")) pdc.set(new NamespacedKey("nexo", "id"), PersistentDataType.STRING, p[1]);
            pdc.set(new NamespacedKey("allium", "phantom_obliterator_charges"), PersistentDataType.INTEGER, Integer.parseInt(p[2]));
            long rechargeAt = Long.parseLong(p[3]) <= 0 ? 0L : System.currentTimeMillis() + Long.parseLong(p[3]) * 1000L;
            pdc.set(new NamespacedKey("allium", "phantom_obliterator_recharge_at"), PersistentDataType.LONG, rechargeAt);
            pdc.set(new NamespacedKey("allium", "phantom_obliterator_uses"), PersistentDataType.LONG, Long.parseLong(p[4]));
            sword.setItemMeta(meta);
            return sword;
        }
        ItemRef bound = items.refForKey(ProviderKey.parse(tool));
        ResolutionResult result = items.create(bound.behaviorId().isPresent() ? bound : bound.withBehaviorId("allium:phantom_obliterator"));
        if (!result.isResolved()) throw new IllegalStateException(tool + ": " + result.diagnosticMessage());
        return result.itemStack().orElseThrow();
    }

    private Map<String, Object> obObserve() {
        Player bot = requireBot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", snapshot(bot.getInventory().getItemInMainHand()));
        Map<String, Object> enchants = new LinkedHashMap<>();
        ItemStack held = bot.getInventory().getItemInMainHand();
        if (held != null && !held.getType().isAir()) held.getEnchantments().forEach((e, level) -> enchants.put(e.getKey().getKey(), level));
        out.put("mainEnchantments", enchants);
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map.Entry<UUID, String> target : obTargets.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            Entity entity = getServer().getEntity(target.getKey());
            row.put("marker", target.getValue());
            row.put("kind", target.getValue().split(":")[2]);
            row.put("loaded", entity != null);
            row.put("alive", entity != null && entity.isValid() && !entity.isDead());
            row.put("killer", obDeaths.get(target.getValue()));
            row.put("damage", obDamage.getOrDefault(target.getValue(), 0));
            row.put("spared", obSpared.getOrDefault(target.getValue(), 0));
            targets.add(row);
        }
        out.put("targets", targets);
        out.put("checkEvents", checkEvents);
        out.put("chunkLoads", obChunkLoads);
        out.put("now", System.currentTimeMillis());
        Map<String, Object> fault = new LinkedHashMap<>();
        fault.put("enabled", FaultInjection.enabled());
        fault.put("armed", FaultInjection.armed(FaultInjection.ITEM_STATE_WRITE));
        fault.put("tripped", FaultInjection.tripped(FaultInjection.ITEM_STATE_WRITE));
        out.put("stateWriteFault", fault);
        return out;
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        obChunkLoads++;
    }

    /** Loads the chunks holding "unloaded" targets again and reports, a little later, which are alive. */
    private void obReload(String name) {
        World world = requireBot().getWorld();
        for (int[] chunk : obUnloaded) world.getChunkAt(chunk[0], chunk[1]).load(true);
        getServer().getScheduler().runTaskLater(this, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<String> alive = new ArrayList<>();
            for (int[] chunk : obUnloaded) {
                for (Entity entity : world.getChunkAt(chunk[0], chunk[1]).getEntities()) {
                    String tag = entity.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
                    if (tag != null && entity.isValid() && !entity.isDead()) alive.add(tag);
                }
            }
            out.put("aliveAfterReload", alive);
            try {
                write(name + "-reload", out);
            } catch (IOException e) {
                getLogger().severe("S06A-FAIL ob-reload write " + e);
            }
        }, 60L);
    }

    /** perm <node> true|false|unset on the bot, through one attachment. */
    private void perm(String node, String value) {
        Player bot = requireBot();
        if (attachment == null) attachment = bot.addAttachment(this);
        if (value.equals("unset")) attachment.unsetPermission(node);
        else attachment.setPermission(node, Boolean.parseBoolean(value));
        bot.recalculatePermissions();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTargetDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        String tag = event.getEntity().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (tag == null || !(event.getDamager() instanceof Player)) return;
        obDamage.merge(tag, 1, Integer::sum);
        if (tag.endsWith(":guard")) {
            event.setCancelled(true);
            obSpared.merge(tag, 1, Integer::sum);
        }
    }

    @EventHandler
    public void onTargetDeath(EntityDeathEvent event) {
        String tag = event.getEntity().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (tag != null) {
            Player killer = event.getEntity().getKiller();
            obDeaths.put(tag, killer == null ? "(none)" : killer.getName());
        }
    }

    /** What Allium registered for an identity that a definition claimed: nothing, for a rejected one. */
    private Map<String, Object> probe(String identity) {
        Map<String, Object> out = new LinkedHashMap<>();
        ProviderKey key = ProviderKey.parse(identity);
        out.put("identity", identity);
        out.put("builtInItem", items.getAlliumItem(key.id()).isPresent());
        out.put("boundBehavior", items.resolveBoundBehaviorId(key).orElse(null));
        out.put("signedBehavior", items.isSignedBehavior(identity));
        out.put("useNodeRegistered", getServer().getPluginManager().getPermission("allium.tool." + key.id() + ".use") != null);
        ResolutionResult created = items.createForBehavior(identity, 1);
        out.put("creates", created.isResolved());
        return out;
    }

    private void kill(String name) {
        Player bot = requireBot();
        mob.damage(1000.0, bot);
        getServer().getScheduler().runTaskLater(this, () -> {
            Map<String, Object> out = observe();
            out.put("deathMarkers", new ArrayList<>(deathMarkers));
            out.put("mobDead", mob.isDead());
            try {
                write(name + "-kill", out);
            } catch (IOException e) {
                getLogger().severe("S06A-FAIL kill write " + e);
            }
        }, 20L);
    }

    private void move(int from, int to) {
        PlayerInventory inventory = requireBot().getInventory();
        ItemStack stack = inventory.getItem(from);
        if (stack == null || stack.getType().isAir()) throw new IllegalStateException("slot " + from + " is empty");
        if (inventory.getItem(to) != null && !inventory.getItem(to).getType().isAir()) throw new IllegalStateException("slot " + to + " is occupied");
        inventory.setItem(to, stack);
        inventory.setItem(from, null);
        inventory.setHeldItemSlot(0);
    }

    /** Admin claim (no owner, so the bot has no trust) over the arena, through GriefPrevention's public API. */
    private Map<String, Object> griefPreventionAdminClaim(int centerX) throws Exception {
        org.bukkit.plugin.Plugin gp = getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp == null) gp = getServer().getPluginManager().getPlugin("GriefPrevention3D");
        if (gp == null) throw new IllegalStateException("GriefPrevention is not installed");
        ClassLoader loader = gp.getClass().getClassLoader();
        Class<?> main = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention", true, loader);
        Class<?> claim = Class.forName("me.ryanhamshire.GriefPrevention.Claim", true, loader);
        Object dataStore = main.getField("dataStore").get(main.getField("instance").get(null));
        World world = getServer().getWorlds().get(0);
        java.lang.reflect.Method create = dataStore.getClass().getMethod("createClaim", World.class, int.class, int.class,
                int.class, int.class, int.class, int.class, java.util.UUID.class, claim, Long.class, Player.class);
        Object result = create.invoke(dataStore, world, centerX - 16, centerX + 16, world.getMinHeight(), world.getMaxHeight() - 1, -16, 16,
                null, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plugin", gp.getName() + " " + gp.getDescription().getVersion());
        out.put("succeeded", result.getClass().getField("succeeded").getBoolean(result));
        Object denial = result.getClass().getField("denialMessage").get(result);
        out.put("denial", denial == null ? null : String.valueOf(((java.util.function.Supplier<?>) denial).get()));
        return out;
    }

    private Map<String, Object> observe() {
        Player bot = requireBot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", snapshot(bot.getInventory().getItemInMainHand()));
        out.put("off", snapshot(bot.getInventory().getItemInOffHand()));
        Map<String, Object> equipment = new LinkedHashMap<>();
        if (mob != null && mob.isValid()) {
            EntityEquipment eq = mob.getEquipment();
            for (EquipmentSlot slot : SLOTS) {
                Map<String, Object> row = new LinkedHashMap<>();
                ItemStack stack = eq.getItem(slot);
                row.put("item", stack == null || stack.getType().isAir() ? null : stack.getType().name());
                row.put("marker", markerOf(stack));
                row.put("chance", chance(eq, slot));
                equipment.put(slot.name(), row);
            }
        }
        out.put("mob", equipment);
        out.put("ground", groundMarkers(bot.getWorld()));
        List<String> carried = new ArrayList<>();
        for (ItemStack stack : bot.getInventory().getContents()) {
            String value = markerOf(stack);
            if (value != null) carried.add(value);
        }
        String cursor = markerOf(bot.getItemOnCursor());
        if (cursor != null) carried.add(cursor);
        carried.sort(String::compareTo);
        out.put("playerInventory", carried);
        out.put("spawnedMarkers", new ArrayList<>(spawnedMarkers));
        out.put("interactions", new ArrayList<>(interactions));
        out.put("checkEvents", checkEvents);
        out.put("now", System.currentTimeMillis());
        Map<String, Object> fault = new LinkedHashMap<>();
        fault.put("enabled", FaultInjection.enabled());
        fault.put("armed", FaultInjection.armed(FaultInjection.ITEM_STATE_WRITE));
        fault.put("tripped", FaultInjection.tripped(FaultInjection.ITEM_STATE_WRITE));
        out.put("stateWriteFault", fault);
        return out;
    }

    private Map<String, Object> inventorySnapshot() {
        PlayerInventory inventory = requireBot().getInventory();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int slot : new int[]{0, 9, 10, 11, 12}) {
            out.put(Integer.toString(slot), snapshot(inventory.getItem(slot)));
        }
        out.put("status", status());
        return out;
    }

    private Map<String, Object> snapshot(ItemStack stack) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (stack == null || stack.getType().isAir()) {
            out.put("empty", true);
            return out;
        }
        out.put("material", stack.getType().name());
        out.put("amount", stack.getAmount());
        Optional<ItemRef> ref = items.identify(stack);
        out.put("behavior", ref.flatMap(ItemRef::behaviorId).orElse(null));
        out.put("appearance", ref.map(r -> r.appearance().asString()).orElse(null));
        out.put("state", ref.map(ItemRef::stateData).orElse(Map.of()));
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        out.put("uuid", pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING));
        out.put("signature", pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        Map<String, String> allium = new LinkedHashMap<>();
        for (NamespacedKey key : pdc.getKeys()) {
            if (key.getNamespace().equals("allium") && pdc.has(key, PersistentDataType.STRING)) {
                allium.put(key.getKey(), pdc.get(key, PersistentDataType.STRING));
            }
        }
        out.put("alliumPdc", allium);
        Map<String, String> nativeIds = new LinkedHashMap<>();
        for (String provider : List.of("nexo", "craftengine", "oraxen")) {
            items.getProvider(provider).filter(a -> a.status() == ProviderStatus.READY)
                    .flatMap(a -> a.identifyItemId(stack)).ifPresent(id -> nativeIds.put(provider, id));
        }
        out.put("native", nativeIds);
        return out;
    }

    private List<String> groundMarkers(World world) {
        List<String> found = new ArrayList<>();
        for (Item item : world.getEntitiesByClass(Item.class)) {
            String value = markerOf(item.getItemStack());
            if (value != null) found.add(value);
        }
        found.sort(String::compareTo);
        return found;
    }

    private String markerOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }

    private Player requireBot() {
        Player bot = getServer().getPlayerExact(BOT);
        if (bot == null) throw new IllegalStateException(BOT + " is not online");
        return bot;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void vetoInteraction(PlayerInteractEntityEvent event) {
        if (veto.equals("interact") && event.getPlayer().getName().equals(BOT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void recordInteraction(PlayerInteractEntityEvent event) {
        record(event, "interact");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void recordInteractionAt(PlayerInteractAtEntityEvent event) {
        record(event, "interact_at");
    }

    private void record(PlayerInteractEntityEvent event, String kind) {
        if (!event.getPlayer().getName().equals(BOT)) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("hand", event.getHand().name());
        row.put("cancelled", event.isCancelled());
        row.put("entityId", event.getRightClicked().getEntityId());
        row.put("tick", getServer().getWorlds().get(0).getFullTime());
        interactions.add(row);
    }

    @EventHandler
    public void onCheck(AlliumProtectionCheckEvent event) {
        checkEvents++;
        if (veto.equals("check-event")) event.setCancelled(true);
        Entity target = event.getTarget();
        String tag = target == null ? null : target.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (tag != null && tag.endsWith(":claimed")) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        for (ItemStack drop : event.getDrops()) {
            String value = markerOf(drop);
            if (value != null) deathMarkers.add(value);
        }
    }

    @EventHandler
    public void onSpawn(ItemSpawnEvent event) {
        String value = markerOf(event.getEntity().getItemStack());
        if (value != null) spawnedMarkers.add(value);
    }

    private static float chance(EntityEquipment eq, EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> eq.getItemInMainHandDropChance();
            case OFF_HAND -> eq.getItemInOffHandDropChance();
            case HEAD -> eq.getHelmetDropChance();
            case CHEST -> eq.getChestplateDropChance();
            case LEGS -> eq.getLeggingsDropChance();
            case FEET -> eq.getBootsDropChance();
            default -> 0.0F;
        };
    }

    private static void setChance(EntityEquipment eq, EquipmentSlot slot, float value) {
        switch (slot) {
            case HAND -> eq.setItemInMainHandDropChance(value);
            case OFF_HAND -> eq.setItemInOffHandDropChance(value);
            case HEAD -> eq.setHelmetDropChance(value);
            case CHEST -> eq.setChestplateDropChance(value);
            case LEGS -> eq.setLeggingsDropChance(value);
            case FEET -> eq.setBootsDropChance(value);
            default -> { }
        }
    }

    private void write(String name, Object value) throws IOException {
        Path dir = getDataFolder().toPath();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".json.tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Exact packet IDs from the running server's protocol catalog (1.20.5+ only). */
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
                            (proxy, method, args) -> {
                                if (method.getName().equals("accept")) {
                                    ids.put(packetType.getMethod("id").invoke(args[0]).toString(), (Integer) args[1]);
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
