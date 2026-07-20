package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.event.CropPathSelectEvent;
import codes.castled.allium.harvest.event.CropPlantEvent;
import codes.castled.allium.harvest.event.CropPrePlantEvent;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.FertilizerDefinition;
import codes.castled.allium.harvest.random.MutableWeightedTable;
import codes.castled.allium.harvest.random.WeightedSelector;
import codes.castled.allium.harvest.soil.SoilService;
import codes.castled.allium.harvest.sprinkler.SprinklerService;
import codes.castled.allium.harvest.storage.CropStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Handles the full planting flow: requirement checks, the cancellable
 * pre-plant event, the single mutually exclusive path roll, seed consumption,
 * persistence, visual creation and feedback. Registration in the in-memory
 * cell index happens synchronously before any async work, so double planting
 * from rapid clicks or both hands cannot create two crops in one cell.
 */
public final class CropPlacementService {

    /** Outcome of a plant attempt, with a player-facing denial reason. */
    public record PlantResult(boolean success, String pathId, String denyReason) {
        static PlantResult deny(String reason) {
            return new PlantResult(false, null, reason);
        }
        static PlantResult ok(String pathId) {
            return new PlantResult(true, pathId, null);
        }
    }

    private final CropRegistry registry;
    private final CropInstanceService instances;
    private final CropStorage storage;
    private final CropVisualService visuals;
    private final SoilService soils;
    private final SprinklerService sprinklers;
    private final RandomGenerator random;

    public CropPlacementService(
        CropRegistry registry,
        CropInstanceService instances,
        CropStorage storage,
        CropVisualService visuals,
        SoilService soils,
        SprinklerService sprinklers,
        RandomGenerator random
    ) {
        this.registry = registry;
        this.instances = instances;
        this.storage = storage;
        this.visuals = visuals;
        this.soils = soils;
        this.sprinklers = sprinklers;
        this.random = random;
    }

    /**
     * Attempts to plant on top of the clicked soil block. Must run on the
     * region thread owning the location.
     *
     * @param seedStack the held seed stack to consume from, or null to skip
     *                  consumption (API/command planting)
     * @param forcedPath a specific path id to use instead of rolling, or null
     */
    public PlantResult plant(Player player, CropDefinition crop, Block soil, ItemStack seedStack, String forcedPath) {
        Block anchor = soil.getRelative(0, 1, 0);
        World world = anchor.getWorld();
        BlockPositionKey position = new BlockPositionKey(
            world.getUID(), anchor.getX(), anchor.getY(), anchor.getZ());

        String denial = checkRequirements(player, crop, soil, anchor, world);
        if (denial != null) {
            return PlantResult.deny(denial);
        }
        if (instances.isOccupied(position) || !anchor.getType().isAir()) {
            return PlantResult.deny("There is already something growing here.");
        }

        CropPrePlantEvent preEvent = new CropPrePlantEvent(player, crop.id(), anchor.getLocation());
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) {
            return PlantResult.deny(null);
        }

        // The fertilizer worked into the soil is spent on this planting: it
        // supplies the variation effect for the roll below and then rides along
        // on the crop for its speed, quality and yield effects.
        // Keyed on the soil block, not the crop position one block above it —
        // that is where the fertilizer was worked in.
        BlockPositionKey soilKey = SoilService.keyOf(soil);
        FertilizerDefinition soilFertilizer = soils.consumeFertilizer(soilKey)
            .flatMap(registry::fertilizer)
            .orElse(null);

        // Exactly one path from one roll across the total positive weight.
        String pathId = forcedPath;
        if (pathId == null) {
            Optional<CropPathDefinition> selected = rollPath(crop, soilFertilizer);
            if (selected.isEmpty()) {
                if (crop.fallbackPath() == null) {
                    return PlantResult.deny("This crop has no selectable growth path.");
                }
                pathId = crop.fallbackPath();
            } else {
                pathId = selected.get().id();
            }
        } else if (crop.path(pathId).isEmpty()) {
            return PlantResult.deny("Unknown path '" + pathId + "'.");
        }

        CropPathSelectEvent selectEvent = new CropPathSelectEvent(
            crop.id(), anchor.getLocation(), pathId, crop.paths().keySet());
        Bukkit.getPluginManager().callEvent(selectEvent);
        pathId = selectEvent.getPathId();
        CropPathDefinition path = crop.path(pathId).orElseThrow();

        if (seedStack != null && player.getGameMode() != GameMode.CREATIVE) {
            seedStack.setAmount(seedStack.getAmount() - 1);
        }

        long now = System.currentTimeMillis();
        boolean singleStage = path.stages().size() == 1;
        double speed = GrowthSpeed.combine(
            soilFertilizer == null ? 1.0D : soilFertilizer.growthSpeedMultiplier(),
            sprinklers.speedMultiplierAt(position));
        CropInstance instance = new CropInstance(
            UUID.randomUUID(), crop.id(), pathId, position,
            now, player.getUniqueId(),
            0,
            singleStage ? CropState.MATURE : CropState.GROWING,
            now,
            singleStage ? 0L : now + GrowthSpeed.apply(path.stage(0).durationMs(), speed),
            0L, null,
            soilFertilizer == null ? null : soilFertilizer.id(),
            List.of(position)
        );
        instance.setSpeedMultiplier(speed);
        // First planting is what puts a soil block on the clock; farmland that
        // is never farmed is never tracked, so the table stays proportional to
        // actual use rather than to how much farmland exists in the world.
        soils.establish(soilKey, now);
        instances.register(instance);
        visuals.ensureVisual(world, instance);
        storage.saveNow(instance);

        Bukkit.getPluginManager().callEvent(new CropPlantEvent(
            player, instance.instanceId(), crop.id(), pathId, anchor.getLocation()));

        world.playSound(anchor.getLocation(), "item.crop.plant", 1.0F, 1.0F);
        player.sendActionBar(MiniMessage.miniMessage().deserialize(
            "<green>Planted <white>" + crop.displayName() + "</white></green>"));
        return PlantResult.ok(pathId);
    }

    /**
     * Performs the single weighted path roll, with the variation effect of a
     * soil fertilizer folded in first.
     *
     * <p>The variation multipliers adjust the weights <em>before</em> the draw,
     * so the roll stays a single selection over one total — a variation
     * fertilizer changes the odds of the rare paths without ever allowing two
     * paths to be chosen.
     */
    private Optional<CropPathDefinition> rollPath(CropDefinition crop, FertilizerDefinition fertilizer) {
        if (fertilizer == null || fertilizer.variation().isNone()) {
            return WeightedSelector.selectOne(crop.paths().values(), random);
        }
        FertilizerDefinition.VariationEffect variation = fertilizer.variation();

        // "Special" means every path except the heaviest one, which is the
        // ordinary outcome a variation fertilizer is meant to steer away from.
        String commonPath = crop.paths().values().stream()
            .max(java.util.Comparator.comparingDouble(CropPathDefinition::weight))
            .map(CropPathDefinition::id)
            .orElse(null);

        MutableWeightedTable<CropPathDefinition> table = new MutableWeightedTable<>();
        for (CropPathDefinition path : crop.paths().values()) {
            table.put(path.id(), path.weight(), path);
        }
        if (variation.specialMultiplier() != 1.0D) {
            for (CropPathDefinition path : crop.paths().values()) {
                if (!path.id().equals(commonPath)) {
                    table.multiplyWeight(path.id(), variation.specialMultiplier());
                }
            }
        }
        variation.pathMultipliers().forEach(table::multiplyWeight);
        return table.selectOne(random).map(MutableWeightedTable.Entry::value);
    }

    private String checkRequirements(Player player, CropDefinition crop, Block soil, Block anchor, World world) {
        var requirements = crop.requirements();
        if (!requirements.permission().isEmpty() && !player.hasPermission(requirements.permission())) {
            return "You are not allowed to plant this crop.";
        }
        if (!requirements.validSoils().contains(soil.getType())) {
            return "This crop cannot be planted on " + soil.getType().name().toLowerCase() + ".";
        }
        // Soil wear is a placement gate in the same family as light and water:
        // no-op entirely when the feature is disabled.
        BlockPositionKey soilKey = SoilService.keyOf(soil);
        if (!soils.isUsable(soilKey, System.currentTimeMillis())) {
            return soils.exhaustedMessage();
        }
        if (!requirements.worldAllow().isEmpty() && !requirements.worldAllow().contains(world.getName())) {
            return "This crop cannot grow in this world.";
        }
        if (requirements.worldDeny().contains(world.getName())) {
            return "This crop cannot grow in this world.";
        }
        if (!requirements.biomes().isEmpty()) {
            String biomeKey = anchor.getBiome().getKey().toString();
            if (!requirements.biomes().contains(biomeKey)) {
                return "This crop cannot grow in this biome.";
            }
        }
        if (requirements.minimumLight() > 0 && anchor.getLightLevel() < requirements.minimumLight()) {
            return "It is too dark to plant here (needs light " + requirements.minimumLight() + ").";
        }
        // A sprinkler that keeps soil moist stands in for natural hydration,
        // which is the point of putting one down in a dry biome.
        if (requirements.requiresWater()
            && !isHydrated(soil)
            && !sprinklers.isWatered(soilKey)) {
            return "The soil here is too dry.";
        }
        return null;
    }

    private boolean isHydrated(Block soil) {
        if (soil.getBlockData() instanceof Farmland farmland) {
            return farmland.getMoisture() > 0;
        }
        // Non-farmland soils: accept water within a 4-block square at soil level.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                Material type = soil.getRelative(dx, 0, dz).getType();
                if (type == Material.WATER) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Location helper for API planting: resolves the soil block below a location. */
    public static Block soilBelow(Location location) {
        return location.getBlock().getRelative(0, -1, 0);
    }
}
