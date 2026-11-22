package codes.castled.crops;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class FarmInputs implements Listener {
    record Fertilizer(String id, double speed, Map<String, Double> quality, double yield, int bonus, int cap,
                      double special, Map<String, Double> paths, long retain, boolean soil, boolean crop) {
        static final Fertilizer NONE = new Fertilizer("", 1, Map.of(), 1, 0, 0, 1, Map.of(), 0, false, false);

        Catalog.Path choose(Catalog.Crop crop) {
            var choices = crop.paths().values();
            var common = choices.stream().max(Comparator.comparingDouble(Catalog.Path::weight)).orElse(null);
            return Catalog.weighted(choices, path -> path.weight() * (path == common ? 1 : special)
                    * paths.getOrDefault(path.id(), 1.0));
        }

        int amount(int rolled) {
            long scaled = Math.round(rolled * yield) + bonus;
            return (int) Math.max(0, Math.min(cap > 0 ? cap : Integer.MAX_VALUE, scaled));
        }
    }

    private record Sprinkler(String item, int radius, double speed, boolean moist, long retain) {
        Catalog.Visual visual(boolean ceiling) {
            return new Catalog.Visual(item, ItemDisplayTransform.FIXED, 1, ceiling ? .2 : .5, false);
        }
    }

    private record Soil(String fertilizer, long expires) {}
    private record Coverage(double speed, boolean moist) {}

    private final CropsPlugin plugin;
    private final ModelWorld world;
    private final Map<String, Fertilizer> fertilizers = new HashMap<>();
    private final Map<String, Fertilizer> fertilizerItems = new HashMap<>();
    private final Map<String, Sprinkler> sprinklers = new HashMap<>();
    private final Set<Material> soils = new HashSet<>();
    private final boolean exhaustion;
    private final long lifetime;
    private final long forgetAfter;
    private final String exhaustedMessage;
    private final int maximumPerChunk;
    private final int maximumRadius;

    FarmInputs(CropsPlugin plugin, ModelWorld world, Collection<Catalog.Crop> crops,
               ConfigurationSection fertilizerConfig, ConfigurationSection sprinklerConfig) {
        this.plugin = plugin;
        this.world = world;
        for (Catalog.Crop crop : crops) soils.addAll(crop.soils());
        exhaustion = fertilizerConfig.getBoolean("soil.enabled");
        lifetime = Catalog.duration(fertilizerConfig.getString("soil.lifetime", "7d"));
        forgetAfter = Catalog.duration(fertilizerConfig.getString("soil.forget-exhausted-after", "14d"));
        exhaustedMessage = fertilizerConfig.getString("soil.exhausted-message", "This soil needs a soil retainer.");
        var entries = fertilizerConfig.getConfigurationSection("fertilizers");
        for (String id : entries.getKeys(false)) {
            var row = entries.getConfigurationSection(id);
            var paths = multipliers(row.getConfigurationSection("variation.paths"));
            double special = factor(row.getDouble("variation.special-path-multiplier", 1), 1);
            long retain = Catalog.duration(row.getString("soil.retain-for", "0"));
            Fertilizer fertilizer = new Fertilizer(id, factor(row.getDouble("growth.speed-multiplier", 1), 1),
                    multipliers(row.getConfigurationSection("quality")), factor(row.getDouble("yield.multiply", 1), 0),
                    row.getInt("yield.bonus"), Math.max(0, row.getInt("yield.maximum")), special, paths, retain,
                    row.getBoolean("apply-to-soil", true),
                    row.getBoolean("apply-to-crop", paths.isEmpty() && special == 1 && retain == 0));
            if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid fertilizer ID " + id);
            fertilizers.put(id, fertilizer);
            fertilizerItems.put(row.getString("item"), fertilizer);
        }
        maximumPerChunk = Math.max(0, sprinklerConfig.getInt("maximum-per-chunk", 64));
        var models = sprinklerConfig.getConfigurationSection("sprinklers");
        for (String id : models.getKeys(false)) {
            var row = models.getConfigurationSection(id);
            int radius = row.getInt("radius");
            if (radius < 0 || radius > 16) throw new IllegalArgumentException("Invalid sprinkler radius for " + id);
            String item = row.getString("item", "allium:" + id);
            sprinklers.put(item, new Sprinkler(item, radius, factor(row.getDouble("speed-multiplier", 1), 1),
                    row.getBoolean("keeps-soil-moist"), Catalog.duration(row.getString("soil-retain", "0"))));
        }
        maximumRadius = sprinklers.values().stream().mapToInt(Sprinkler::radius).max().orElse(0);
    }

    private static double factor(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? Math.min(100, value) : fallback;
    }

    private static Map<String, Double> multipliers(ConfigurationSection config) {
        Map<String, Double> result = new HashMap<>();
        if (config != null) for (String key : config.getKeys(false)) {
            double value = config.getDouble(key, 1);
            if (Double.isFinite(value) && value >= 0) result.put(key, Math.min(100, value));
        }
        return Map.copyOf(result);
    }

    Fertilizer fertilizer(ModelWorld.Placed crop) { return fertilizers.getOrDefault(crop.fertilizer, Fertilizer.NONE); }

    Fertilizer fertilizer(Block soil, long now) {
        return fertilizers.getOrDefault(readSoil(soil, now).fertilizer(), Fertilizer.NONE);
    }

    boolean exhausted(Block soil, long now) {
        return exhaustion && readSoil(soil, now).expires() <= now;
    }

    String exhaustedMessage() { return exhaustedMessage; }

    void planted(Block soil, long now) {
        putSoil(soil, new Soil("", readSoil(soil, now).expires()));
    }

    double speed(Block anchor, Fertilizer fertilizer) {
        return Math.max(.01, Math.min(100, coverage(anchor).speed() * fertilizer.speed()));
    }

    boolean moist(Block anchor) { return coverage(anchor).moist(); }

    private Coverage coverage(Block anchor) {
        var uuid = anchor.getWorld().getUID();
        double speed = 1;
        boolean moist = false;
        for (int dx = -maximumRadius; dx <= maximumRadius; dx++) {
            for (int dz = -maximumRadius; dz <= maximumRadius; dz++) {
                var placed = world.at(new ModelWorld.Cell(uuid, anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz));
                if (placed == null || !placed.kind.equals("sprinkler") || !placed.hitbox.isValid()) continue;
                var sprinkler = sprinklers.get(placed.type);
                if (sprinkler == null || Math.abs(dx) > sprinkler.radius() || Math.abs(dz) > sprinkler.radius()) continue;
                speed *= sprinkler.speed();
                moist |= sprinkler.moist();
            }
        }
        return new Coverage(speed, moist);
    }

    boolean applyCrop(Player player, ModelWorld.Placed crop, boolean growing, ItemStack held, Event source) {
        var fertilizer = fertilizerItems.get(plugin.reference(held));
        if (fertilizer == null) return false;
        if (!allowed(player) || !plugin.connection().mayModify(player, crop.cell(), "fertilize", source)) return true;
        if (!fertilizer.crop()) {
            player.sendMessage("§eWork this fertilizer into bare soil before planting.");
        } else if (growing && !fertilizer.id().equals(crop.fertilizer)) {
            crop.fertilizer = fertilizer.id();
            world.store(crop);
            CropsPlugin.spend(player, held, 1);
            fertilized(player, crop.cell(), "Fertilizer applied.");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void rightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) return;
        var player = event.getPlayer();
        var block = event.getClickedBlock();
        String item = plugin.reference(event.getItem());
        var fertilizer = fertilizerItems.get(item);
        if (fertilizer != null && event.getBlockFace() == BlockFace.UP && soils.contains(block.getType())) {
            var anchor = block.getRelative(BlockFace.UP);
            if (!anchor.getType().isAir() || world.at(anchor) != null) return;
            event.setCancelled(true);
            if (!fertilizer.soil() || !allowed(player) || !plugin.connection().mayModify(player, block, "fertilize", event)) return;
            long now = System.currentTimeMillis();
            var prior = readSoil(block, now);
            if (prior.fertilizer().equals(fertilizer.id()) && fertilizer.retain() == 0) return;
            long expires = fertilizer.retain() == 0 ? prior.expires() : Math.max(now, prior.expires()) + fertilizer.retain();
            putSoil(block, new Soil(fertilizer.id(), expires));
            CropsPlugin.spend(player, event.getItem(), 1);
            fertilized(player, block, "Worked into the soil.");
        } else if (sprinklers.containsKey(item) && (event.getBlockFace() == BlockFace.UP || event.getBlockFace() == BlockFace.DOWN)) {
            placeSprinkler(event, sprinklers.get(item));
        }
    }

    private void placeSprinkler(PlayerInteractEvent event, Sprinkler definition) {
        event.setCancelled(true);
        var player = event.getPlayer();
        var support = event.getClickedBlock();
        Block cell = support.getRelative(event.getBlockFace());
        if (!allowed(player) || !(player.hasPermission("crops.sprinkler.place")
                || player.hasPermission("allium.harvest.sprinkler.place")) || !support.getType().isSolid()
                || !plugin.connection().mayModify(player, cell, "place_sprinkler", event)) return;
        long count = world.active().stream().filter(p -> p.kind.equals("sprinkler") && p.hitbox.getWorld().equals(cell.getWorld())
                && p.cell().getX() >> 4 == cell.getX() >> 4 && p.cell().getZ() >> 4 == cell.getZ() >> 4).count();
        if (count >= maximumPerChunk) {
            player.sendMessage("§eThere are too many sprinklers in this chunk.");
            return;
        }
        boolean ceiling = event.getBlockFace() == BlockFace.DOWN;
        var placed = world.place(cell, "sprinkler", definition.item(), ceiling ? "ceiling" : "floor", 0, 0,
                definition.visual(ceiling));
        if (placed != null) {
            CropsPlugin.spend(player, event.getItem(), 1);
            tick(placed);
        }
    }

    void restore(ModelWorld.Placed placed) {
        var sprinkler = sprinklers.get(placed.type);
        if (sprinkler != null) world.show(placed, sprinkler.visual(placed.path.equals("ceiling")));
    }

    void tick(ModelWorld.Placed placed) {
        var sprinkler = sprinklers.get(placed.type);
        if (sprinkler == null) return;
        Block cell = placed.cell();
        BlockFace support = placed.path.equals("ceiling") ? BlockFace.UP : BlockFace.DOWN;
        if (!cell.getType().isAir() || !cell.getRelative(support).getType().isSolid()) {
            remove(placed);
            return;
        }
        var attack = placed.hitbox.getLastAttack();
        if (attack != null && attack.getTimestamp() > placed.lastAttack) {
            placed.lastAttack = attack.getTimestamp();
            Player actor = attack.getPlayer().getPlayer();
            if (actor != null && allowed(actor) && plugin.connection().mayModify(actor, cell, "break_sprinkler", null)) {
                remove(placed);
                return;
            }
        }
        long now = System.currentTimeMillis();
        for (int x = -sprinkler.radius(); x <= sprinkler.radius(); x++) {
            for (int z = -sprinkler.radius(); z <= sprinkler.radius(); z++) {
                Block soil = cell.getRelative(x, -1, z);
                if (!soil.getWorld().isChunkLoaded(soil.getX() >> 4, soil.getZ() >> 4) || !soils.contains(soil.getType())) continue;
                if (sprinkler.moist() && soil.getBlockData() instanceof Farmland farmland
                        && farmland.getMoisture() < farmland.getMaximumMoisture()) {
                    farmland.setMoisture(farmland.getMaximumMoisture());
                    soil.setBlockData(farmland, false);
                }
                if (exhaustion && sprinkler.retain() > 0) {
                    var prior = readSoil(soil, now);
                    long until = now + sprinkler.retain();
                    if (prior.expires() < until) putSoil(soil, new Soil(prior.fertilizer(), until));
                }
            }
        }
    }

    private void remove(ModelWorld.Placed sprinkler) {
        var item = plugin.item(sprinkler.type, 1);
        var location = sprinkler.hitbox.getLocation();
        world.remove(sprinkler);
        if (item != null) location.getWorld().dropItemNaturally(location, item);
    }

    private void fertilized(Player player, Block where, String message) {
        player.sendMessage("§a" + message);
        where.getWorld().playSound(where.getLocation(), "item.bone_meal.use", 1, 1);
    }

    private boolean allowed(Player player) {
        return player.hasPermission("crops.use") && plugin.connection().canEditInventory(player);
    }

    private Soil readSoil(Block block, long now) {
        var key = BlockKeys.at(plugin, "soil", block);
        String raw = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw != null) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length == 2) {
                try {
                    long expires = Long.parseLong(parts[1]);
                    if (!exhaustion || forgetAfter <= 0 || now - expires < forgetAfter) return new Soil(parts[0], expires);
                } catch (NumberFormatException invalid) {
                    // Treat an unreadable record as untracked soil.
                }
            }
        }
        return new Soil("", now + lifetime);
    }

    private void putSoil(Block block, Soil value) {
        if (!exhaustion && value.fertilizer().isEmpty()) {
            clearSoil(block);
            return;
        }
        block.getChunk().getPersistentDataContainer().set(BlockKeys.at(plugin, "soil", block), PersistentDataType.STRING,
                value.fertilizer() + "|" + value.expires());
    }

    private void clearSoil(Block block) {
        block.getChunk().getPersistentDataContainer().remove(BlockKeys.at(plugin, "soil", block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakSoil(BlockBreakEvent event) {
        if (soils.contains(event.getBlock().getType())) clearSoil(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fadeSoil(BlockFadeEvent event) {
        if (soils.contains(event.getBlock().getType()) && !soils.contains(event.getNewState().getType())) clearSoil(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changeSoil(EntityChangeBlockEvent event) {
        if (soils.contains(event.getBlock().getType()) && !soils.contains(event.getTo())) clearSoil(event.getBlock());
    }
}
