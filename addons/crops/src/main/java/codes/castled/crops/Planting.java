package codes.castled.crops;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

final class Planting implements Listener {
    private final CropsPlugin plugin;
    private final ModelWorld world;
    private final Map<String, Catalog.Crop> crops;
    private final FarmInputs farm;
    private final Map<String, Catalog.Crop> seeds = new HashMap<>();

    Planting(CropsPlugin plugin, ModelWorld world, Map<String, Catalog.Crop> crops, FarmInputs farm) {
        this.plugin = plugin;
        this.world = world;
        this.crops = crops;
        this.farm = farm;
        crops.values().forEach(crop -> seeds.put(crop.seed(), crop));
    }

    int count() { return crops.size(); }
    List<String> cropIds() { return List.copyOf(crops.keySet()); }
    Map<String, Catalog.Crop> catalog() { return Collections.unmodifiableMap(crops); }
    Catalog.Crop crop(String id) { return id == null ? null : crops.get(id.toLowerCase(Locale.ROOT)); }
    Catalog.Path pathOf(ModelWorld.Placed planted) { return path(planted); }

    boolean placeMigrated(Block anchor, Catalog.Crop crop, Catalog.Path path, int stageIndex, long dueAt,
                          String fertilizerId, double growthSpeed) {
        Block soil = anchor.getRelative(BlockFace.DOWN);
        if (!crop.soils().contains(soil.getType()) || !anchor.getType().isAir() || world.at(anchor) != null) {
            return false;
        }
        int clamped = Math.max(0, Math.min(path.stages().size() - 1, stageIndex));
        var stage = path.stages().get(clamped);
        double speed = Double.isFinite(growthSpeed) && growthSpeed > 0 ? Math.max(.01, Math.min(100, growthSpeed)) : 1.0;
        long due = dueAt > 0L ? dueAt : next(System.currentTimeMillis(), crop, stage, speed);
        var planted = world.place(anchor, "crop", crop.id(), path.id(), clamped, due, stage.visual());
        if (planted == null) return false;
        planted.fertilizer = fertilizerId == null ? "" : fertilizerId;
        planted.growthSpeed = speed;
        world.store(planted);
        return true;
    }

    String plantDirect(Player player, Catalog.Crop crop, Block soil, String requestedPath) {
        if (!crop.soils().contains(soil.getType())) {
            return "Invalid soil for " + crop.id() + " (needs " + crop.soils() + ").";
        }
        Block target = soil.getRelative(BlockFace.UP);
        if (!target.getType().isAir() || world.at(target) != null) {
            return "The block above that soil is already occupied.";
        }
        long now = System.currentTimeMillis();
        var fertilizer = farm.fertilizer(soil, now);
        Catalog.Path chosen = requestedPath == null || requestedPath.isBlank()
                ? fertilizer.choose(crop)
                : crop.paths().get(requestedPath.toLowerCase(Locale.ROOT));
        if (chosen == null) {
            return "Unknown path '" + requestedPath + "' for " + crop.id() + " (available: "
                    + String.join(", ", crop.paths().keySet()) + ").";
        }
        if (!place(crop, chosen, soil, now, fertilizer)) {
            return "Could not place crop visual at that location.";
        }
        soil.getWorld().playSound(soil.getLocation(), "item.crop.plant", 1, 1);
        return null;
    }

    int forceGrow(ModelWorld.Placed planted, int stages) {
        Catalog.Crop def = crops.get(planted.type);
        Catalog.Path path = path(planted);
        if (def == null || path == null || stages <= 0) return 0;
        int maxIndex = path.stages().size() - 1;
        int targetStage = Math.min(maxIndex, planted.stage + stages);
        if (targetStage <= planted.stage) return 0;
        var stage = path.stages().get(targetStage);
        if (!world.show(planted, stage.visual())) return 0;
        int advanced = targetStage - planted.stage;
        planted.stage = targetStage;
        planted.due = targetStage >= maxIndex ? System.currentTimeMillis()
                : next(System.currentTimeMillis(), def, stage, planted.growthSpeed);
        world.store(planted);
        return advanced;
    }

    boolean isHydrated(Block soil) { return water(soil) || farm.moist(soil.getRelative(BlockFace.UP)); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void plant(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getBlockFace() != BlockFace.UP || event.getClickedBlock() == null) return;
        Block soil = event.getClickedBlock();
        Block target = soil.getRelative(BlockFace.UP);
        var existing = world.at(target);
        if (existing != null && existing.kind.equals("crop")
                && farm.applyCrop(event.getPlayer(), existing, growing(existing), event.getItem(), event)) {
            event.setCancelled(true);
            return;
        }
        var crop = seeds.get(plugin.reference(event.getItem()));
        if (crop == null || !crop.soils().contains(soil.getType())) return;
        event.setCancelled(true);
        var player = event.getPlayer();
        if (!player.hasPermission("crops.use") || !plugin.connection().canEditInventory(player)) return;
        if (!target.getType().isAir() || world.at(target) != null) return;
        if (!plugin.connection().mayModify(player, target, "plant", event)) return;
        long now = System.currentTimeMillis();
        if (farm.exhausted(soil, now)) {
            player.sendMessage("§e" + farm.exhaustedMessage());
            return;
        }
        if (target.getLightLevel() < crop.light() || crop.water() && !isHydrated(soil)) {
            player.sendMessage("§eThis crop needs hydrated soil and enough light.");
            return;
        }
        var fertilizer = farm.fertilizer(soil, now);
        var path = fertilizer.choose(crop);
        if (path == null) return;
        if (place(crop, path, soil, now, fertilizer)) {
            CropsPlugin.spend(player, event.getItem(), 1);
            soil.getWorld().playSound(soil.getLocation(), "item.crop.plant", 1, 1);
        }
    }

    private boolean place(Catalog.Crop crop, Catalog.Path path, Block soil, long now, FarmInputs.Fertilizer fertilizer) {
        Block target = soil.getRelative(BlockFace.UP);
        var stage = path.stages().get(0);
        double speed = farm.speed(target, fertilizer);
        var planted = world.place(target, "crop", crop.id(), path.id(), 0, next(now, crop, stage, speed), stage.visual());
        if (planted == null) return false;
        planted.fertilizer = fertilizer.id();
        planted.growthSpeed = speed;
        world.store(planted);
        farm.planted(soil, now);
        return true;
    }

    private boolean water(Block soil) {
        if (soil.getBlockData() instanceof Farmland farmland) return farmland.getMoisture() > 0;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int bx = soil.getX() + x, bz = soil.getZ() + z;
            World w = soil.getWorld();
            if (!plugin.connection().ownsRegion(w, bx >> 4, bz >> 4) || !w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block block = soil.getRelative(x, 0, z);
            if (block.getType() == Material.WATER || block.getRelative(BlockFace.UP).getType() == Material.WATER) return true;
        }
        return false;
    }

    void restore(ModelWorld.Placed planted) {
        Catalog.Path path = path(planted);
        if (path != null && planted.stage >= 0 && planted.stage < path.stages().size()) {
            world.show(planted, path.stages().get(planted.stage).visual());
        }
    }

    void tick(ModelWorld.Placed planted) {
        if (!planted.hitbox.isValid()) return;
        Catalog.Crop crop = crops.get(planted.type);
        Catalog.Path path = path(planted);
        if (crop == null || path == null || planted.stage < 0 || planted.stage >= path.stages().size()) return;
        var attack = planted.hitbox.getLastAttack();
        if (attack != null && attack.getTimestamp() > planted.lastAttack) {
            planted.lastAttack = attack.getTimestamp();
            Player player = attack.getPlayer().getPlayer();
            if (player != null && (!plugin.connection().isFolia() || plugin.connection().ownsEntity(player))) {
                harvest(player, planted, true, null);
            }
            if (!planted.hitbox.isValid()) return;
        }
        Block soil = planted.cell().getRelative(BlockFace.DOWN);
        if (!crop.soils().contains(soil.getType()) || !planted.cell().getType().isAir()) {
            drop(planted, null, path.immature().roll(), "DROP_AT_CROP");
            world.remove(planted);
            return;
        }
        long now = System.currentTimeMillis();
        int previous = planted.stage;
        if (growing(planted)) {
            double speed = farm.speed(planted.cell(), farm.fertilizer(planted));
            if (planted.growthSpeed != speed) {
                if (planted.due > now) planted.due = now + Math.round((planted.due - now) / planted.growthSpeed * speed);
                planted.growthSpeed = speed;
                world.store(planted);
            }
        }
        if (planted.stage + 1 < path.stages().size() && planted.due <= now
                && planted.cell().getLightLevel() >= crop.light() && (!crop.water() || isHydrated(soil))) {
            for (int steps = 0; steps < crop.catchUp() && planted.stage + 1 < path.stages().size()
                    && planted.due <= now; steps++) {
                var stage = path.stages().get(planted.stage + 1);
                if (!world.show(planted, stage.visual())) break;
                planted.stage++;
                planted.due = next(planted.due, crop, stage, planted.growthSpeed);
            }
        }
        if (previous != planted.stage) world.store(planted);
        restore(planted);
    }

    private Catalog.Path path(ModelWorld.Placed planted) {
        var crop = crops.get(planted.type);
        return crop == null ? null : crop.paths().get(planted.path);
    }

    private boolean growing(ModelWorld.Placed placement) {
        var path = path(placement);
        return path != null && placement.stage + 1 < path.stages().size();
    }

    private static long next(long start, Catalog.Crop crop, Catalog.Stage stage, double speed) {
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * crop.randomness();
        return start + Math.max(0, Math.round(stage.duration() * factor * speed));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        var placement = world.get(event.getRightClicked());
        if (placement == null || !placement.kind.equals("crop")) return;
        event.setCancelled(true);
        if (farm.applyCrop(event.getPlayer(), placement, growing(placement),
                event.getPlayer().getInventory().getItemInMainHand(), event)) return;
        harvest(event.getPlayer(), placement, false, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        var placement = world.get(event.getEntity());
        if (placement == null || !placement.kind.equals("crop")) return;
        event.setCancelled(true);
        harvest(player, placement, true, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakSoil(BlockBreakEvent event) {
        var placement = world.at(event.getBlock().getRelative(BlockFace.UP));
        if (placement != null && placement.kind.equals("crop")) harvest(event.getPlayer(), placement, true, event);
    }

    private void harvest(Player player, ModelWorld.Placed crop, boolean breaking, org.bukkit.event.Event source) {
        if (!player.hasPermission("crops.use") || !plugin.connection().mayModify(player, crop.cell(), "harvest", source)
                || !plugin.connection().canEditInventory(player)) return;
        Catalog.Crop def = crops.get(crop.type);
        Catalog.Path path = path(crop);
        if (def == null || path == null) return;
        boolean mature = crop.stage + 1 >= path.stages().size();
        boolean pick = mature && (breaking ? def.punch() : def.right());
        if (!breaking && !pick) {
            if (!def.progress().equalsIgnoreCase("OFF") && !def.progress().equalsIgnoreCase("false")) {
                player.sendMessage(mature ? "§aReady to harvest." : "§eStill growing.");
            }
            return;
        }
        var fertilizer = farm.fertilizer(crop);
        List<Catalog.Drop> primary = List.of();
        List<Catalog.Drop> loot;
        if (pick) {
            primary = path.primary().roll(fertilizer.quality(), cropQualityBonus(player));
            loot = path.additional().roll();
        } else loot = (mature ? path.mature() : path.immature()).roll();
        int regrow = breaking ? path.breakRegrowth() : path.rightRegrowth();
        if (pick && regrow >= 0 && regrow < path.stages().size() && !(source instanceof BlockBreakEvent)) {
            var stage = path.stages().get(regrow);
            if (!world.show(crop, stage.visual())) return;
            crop.stage = regrow;
            crop.growthSpeed = farm.speed(crop.cell(), fertilizer);
            crop.due = next(System.currentTimeMillis(), def, stage, crop.growthSpeed);
            world.store(crop);
        } else world.remove(crop);
        String delivery = pick ? path.delivery() : "DROP_AT_CROP";
        drop(crop, player, primary, delivery, fertilizer);
        drop(crop, player, loot, delivery);
        player.getWorld().playSound(crop.hitbox.getLocation(), "block.crop.break", 1, 1);
    }

    private double cropQualityBonus(Player player) {
        try {
            var boosts = plugin.getServer().getServicesManager().load(codes.castled.allium.api.CurioBoosts.class);
            if (boosts == null) return 0.0;
            double bonus = boosts.cropQualityBonus(player.getUniqueId());
            return Double.isFinite(bonus) && bonus > 0.0 ? bonus : 0.0;
        } catch (NoClassDefFoundError | RuntimeException unavailable) {
            return 0.0;
        }
    }

    private void drop(ModelWorld.Placed crop, Player player, List<Catalog.Drop> loot, String delivery) {
        drop(crop, player, loot, delivery, FarmInputs.Fertilizer.NONE);
    }

    private void drop(ModelWorld.Placed crop, Player player, List<Catalog.Drop> loot, String delivery,
                      FarmInputs.Fertilizer fertilizer) {
        Location location = crop.hitbox.getLocation().add(0, .25, 0);
        for (Catalog.Drop reward : loot) {
            int amount = fertilizer.amount(ThreadLocalRandom.current().nextInt(reward.min(), reward.max() + 1));
            if (amount <= 0) continue;
            var stack = plugin.item(reward.item(), amount);
            if (stack == null) continue;
            if (player != null && (delivery.equals("PLAYER_INVENTORY") || delivery.equals("INVENTORY_THEN_DROP"))) plugin.give(player, stack);
            else location.getWorld().dropItemNaturally(location, stack);
        }
    }
}
