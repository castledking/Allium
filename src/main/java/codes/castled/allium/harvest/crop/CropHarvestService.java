package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.BreakDefinition;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.DeliveryMode;
import codes.castled.allium.harvest.crop.def.DropSpec;
import codes.castled.allium.harvest.crop.def.FertilizerDefinition;
import codes.castled.allium.harvest.crop.def.HarvestOutcome;
import codes.castled.allium.harvest.crop.def.HarvestSource;
import codes.castled.allium.harvest.crop.def.HarvestTable;
import codes.castled.allium.harvest.crop.def.RegrowthDefinition;
import codes.castled.allium.harvest.event.CropDropsGenerateEvent;
import codes.castled.allium.harvest.event.CropHarvestEvent;
import codes.castled.allium.harvest.event.CropPreHarvestEvent;
import codes.castled.allium.harvest.event.CropRemoveEvent;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.harvest.storage.CropStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Harvest lifecycle: verify maturity, fire events, resolve the path-specific
 * outcome tables (primary is mutually exclusive; additional rolls
 * independently), apply weight modifiers, generate drops in memory, deliver,
 * then regrow or remove — persisting each step. The crop is atomically taken
 * out of the harvestable state before drops are generated, so a racing second
 * interaction cannot double-harvest.
 */
public final class CropHarvestService {

    public record HarvestResult(boolean success, String primaryOutcomeId, List<ItemStack> drops, String denyReason) {
        static HarvestResult deny(String reason) {
            return new HarvestResult(false, null, List.of(), reason);
        }
    }

    private final CropRegistry registry;
    private final CropInstanceService instances;
    private final CropStorage storage;
    private final CropVisualService visuals;
    private final ItemResolverChain items;
    private final RandomGenerator random;
    private final List<HarvestWeightModifier> modifiers = new CopyOnWriteArrayList<>();

    public CropHarvestService(
        CropRegistry registry,
        CropInstanceService instances,
        CropStorage storage,
        CropVisualService visuals,
        ItemResolverChain items,
        RandomGenerator random
    ) {
        this.registry = registry;
        this.instances = instances;
        this.storage = storage;
        this.visuals = visuals;
        this.items = items;
        this.random = random;
        this.modifiers.add(this::applyFertilizer);
    }

    /** Registers an external weight modifier (skills, boosters, seasons, …). */
    public void registerModifier(HarvestWeightModifier modifier) {
        modifiers.add(modifier);
    }

    private void applyFertilizer(HarvestContext context,
                                 codes.castled.allium.harvest.random.MutableWeightedTable<HarvestOutcome> table) {
        FertilizerDefinition fertilizer = context.fertilizer();
        if (fertilizer == null) return;
        fertilizer.weightMultipliers().forEach(table::multiplyWeight);
    }

    /** Must run on the region thread owning the crop. */
    public HarvestResult harvest(Player player, CropInstance crop) {
        return harvest(player, crop, HarvestSource.RIGHT_CLICK);
    }

    /**
     * Harvests a mature crop.
     *
     * <p>The {@code source} decides only what happens to the plant afterwards —
     * the loot tables are the same whether the crop was picked or pulled up, so
     * a crop cannot be made to pay better by breaking it. Must run on the region
     * thread owning the crop.
     */
    public HarvestResult harvest(Player player, CropInstance crop, HarvestSource source) {
        if (crop.state() != CropState.MATURE) {
            return HarvestResult.deny("This crop is not ready to harvest yet.");
        }
        CropDefinition definition = registry.crop(crop.cropId()).orElse(null);
        if (definition == null) {
            removeCrop(crop, CropRemoveEvent.Reason.DEFINITION_REMOVED);
            return HarvestResult.deny("This crop no longer exists.");
        }
        CropPathDefinition path = definition.path(crop.pathId()).orElse(null);
        if (path == null) {
            removeCrop(crop, CropRemoveEvent.Reason.DEFINITION_REMOVED);
            return HarvestResult.deny("This crop no longer exists.");
        }

        World world = Bukkit.getWorld(crop.position().worldId());
        if (world == null) {
            return HarvestResult.deny("World is not available.");
        }
        Location location = new Location(world,
            crop.position().x() + 0.5, crop.position().y(), crop.position().z() + 0.5);

        CropPreHarvestEvent preEvent = new CropPreHarvestEvent(
            player, crop.instanceId(), crop.cropId(), crop.pathId(), location);
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) {
            return HarvestResult.deny(null);
        }

        // Take the crop out of the harvestable state before rolling anything;
        // a second click landing after this point sees a non-MATURE crop.
        crop.setState(CropState.REMOVED);

        FertilizerDefinition fertilizer = crop.fertilizerId() == null
            ? null : registry.fertilizer(crop.fertilizerId()).orElse(null);
        HarvestContext context = new HarvestContext(player, crop, definition, path, location, fertilizer);

        List<HarvestOutcome> primaryFired = LootResolver.resolve(
            path.harvest().primary(),
            table -> modifiers.forEach(modifier -> modifier.modify(context, table)),
            random);
        List<HarvestOutcome> additionalFired = LootResolver.resolve(
            path.harvest().additional(), null, random);

        String primaryOutcomeId = primaryFired.isEmpty() ? null : primaryFired.get(0).id();

        List<ItemStack> drops = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        FertilizerDefinition.YieldEffect yield =
            fertilizer == null ? FertilizerDefinition.YieldEffect.NONE : fertilizer.yield();
        for (HarvestOutcome outcome : primaryFired) {
            generate(outcome, drops, commands, yield);
        }
        // Additional tables are the seed/bonus drops. Yield deliberately does
        // not touch them: multiplying seed returns would let a yield fertilizer
        // pay for itself and then some.
        for (HarvestOutcome outcome : additionalFired) {
            generate(outcome, drops, commands, FertilizerDefinition.YieldEffect.NONE);
        }

        CropDropsGenerateEvent dropsEvent = new CropDropsGenerateEvent(
            player, crop.instanceId(), crop.cropId(), crop.pathId(), primaryOutcomeId, location, drops);
        Bukkit.getPluginManager().callEvent(dropsEvent);

        deliver(player, world, location, drops, path);
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command
                .replace("{player}", player.getName())
                .replace("{crop}", crop.cropId()));
        }

        RegrowthDefinition.Rule regrowth = path.regrowth().forSource(source);
        boolean regrown = regrowth.enabled();
        if (regrown) {
            long now = System.currentTimeMillis();
            int stage = regrowth.stage();
            crop.setStage(stage);
            crop.setState(CropState.GROWING);
            crop.setStageStartedAt(now);
            crop.setNextGrowthAt(now + StageDuration.forStage(
                crop.instanceId(), stage, path.stage(stage).durationMs(),
                crop.speedMultiplier(), definition.growth().randomness()));
            crop.setFertilizerId(null);
            instances.reindexCells(crop, List.of(crop.position()));
            visuals.ensureVisual(world, crop);
            storage.saveNow(crop);
        } else {
            removeCrop(crop, CropRemoveEvent.Reason.HARVESTED);
        }

        world.playSound(location, "block.crop.break", 1.0F, 1.0F);
        Bukkit.getPluginManager().callEvent(new CropHarvestEvent(
            player, crop.instanceId(), crop.cropId(), crop.pathId(),
            primaryOutcomeId, location, drops, regrown));
        return new HarvestResult(true, primaryOutcomeId, drops, null);
    }

    private void generate(HarvestOutcome outcome, List<ItemStack> drops, List<String> commands,
                          FertilizerDefinition.YieldEffect yield) {
        for (DropSpec drop : outcome.drops()) {
            // Yield scales the rolled amount, so the configured range still
            // governs the base spread and the fertilizer only shifts it.
            int amount = yield.apply(drop.rollAmount(random));
            if (amount <= 0) continue;
            items.create(drop.item(), amount).ifPresent(drops::add);
        }
        commands.addAll(outcome.commands());
    }

    private void deliver(Player player, World world, Location location, List<ItemStack> drops, CropPathDefinition path) {
        switch (path.harvest().delivery()) {
            case COMMANDS_ONLY -> { /* drops intentionally discarded */ }
            case DROP_AT_CROP -> drops.forEach(drop -> world.dropItemNaturally(location, drop));
            case PLAYER_INVENTORY, INVENTORY_THEN_DROP -> {
                var overflow = player.getInventory().addItem(drops.toArray(new ItemStack[0]));
                if (path.harvest().delivery() == codes.castled.allium.harvest.crop.def.DeliveryMode.INVENTORY_THEN_DROP) {
                    overflow.values().forEach(drop -> world.dropItemNaturally(location, drop));
                }
            }
        }
    }

    /**
     * Removes a crop entirely: loaded indexes, database row + cells, visual.
     * Safe to call for broken, harvested, obstructed or admin-removed crops.
     */
    public void removeCrop(CropInstance crop, CropRemoveEvent.Reason reason) {
        removeCrop(crop, reason, reason == CropRemoveEvent.Reason.BROKEN);
    }

    /**
     * Removes a crop, stating explicitly whether it pays out.
     *
     * <p>Every reason but {@code BROKEN} carries its own answer to that, which
     * is why the two-argument form can derive it. A liquid is the exception:
     * water and lava destroy a crop identically and differ only in whether
     * anything survives, so the caller has to say which it was.
     *
     * @param returnLoot whether the crop's destruction table is rolled and
     *                   dropped at its feet
     */
    public void removeCrop(CropInstance crop, CropRemoveEvent.Reason reason, boolean returnLoot) {
        // Maturity has to be read before the state is cleared, because the
        // break tables differ for a finished plant and a half-grown one.
        boolean wasMature = isMature(crop);

        crop.setState(CropState.REMOVED);
        instances.unregister(crop);
        storage.delete(crop.instanceId());
        World world = Bukkit.getWorld(crop.position().worldId());
        Location location = null;
        if (world != null) {
            visuals.removeVisual(world, crop);
            location = new Location(world,
                crop.position().x() + 0.5D, crop.position().y(), crop.position().z() + 0.5D);
            if (returnLoot) {
                dropDestructionLoot(world, location, crop, wasMature, reason);
            }
        }
        if (location != null) {
            Bukkit.getPluginManager().callEvent(new CropRemoveEvent(
                crop.instanceId(), crop.cropId(), crop.pathId(), location, reason));
        }
    }

    /**
     * Destroys a crop that water or lava reached.
     *
     * <p>The fluid is never held up by this: the flow that triggered it goes
     * ahead exactly as vanilla intended, and the plant standing in its way is
     * taken out as the fluid arrives. Must run on the region thread owning the
     * crop.
     *
     * @param response what the configured fluid does to a crop; {@code PROTECT}
     *                 is a no-op here, since a protected crop is spared by
     *                 cancelling the flow rather than by surviving it
     */
    public void destroyByLiquid(CropInstance crop, LiquidResponse response) {
        if (response.survives() || crop.state() == CropState.REMOVED) {
            return;
        }
        World world = Bukkit.getWorld(crop.position().worldId());
        removeCrop(crop, CropRemoveEvent.Reason.LIQUID, response == LiquidResponse.DROP);
        if (response == LiquidResponse.BURN && world != null) {
            // Vanilla's answer to lava swallowing a block: a hiss and a puff of
            // smoke, so the plant is visibly consumed rather than just absent.
            Location location = new Location(world,
                crop.position().x() + 0.5D, crop.position().y() + 0.5D, crop.position().z() + 0.5D);
            world.playSound(location, "block.fire.extinguish", 0.5F, 2.6F);
            world.spawnParticle(Particle.LARGE_SMOKE, location, 8, 0.25D, 0.25D, 0.25D, 0.0D);
        }
    }

    private boolean isMature(CropInstance crop) {
        if (crop.state() == CropState.MATURE) {
            return true;
        }
        return registry.crop(crop.cropId())
            .flatMap(definition -> definition.path(crop.pathId()))
            .map(path -> path.isMature(crop.stage()))
            .orElse(false);
    }

    /**
     * Drops what a destroyed crop leaves behind, at its feet.
     *
     * <p>Only reached for destructions that pay out. A crop removed by an admin
     * command, because its definition disappeared, or because its footprint was
     * obstructed must not spill items — that would turn a config edit into a
     * duplication bug.
     *
     * <p>Commands are dispatched for a player-driven break only. A command loot
     * table is written to reward whoever took the crop, and nobody took this
     * one, so firing those commands for a plant a river ran over would pay a
     * player who was not there — or worse, an absent one.
     */
    private void dropDestructionLoot(World world, Location location, CropInstance crop,
                                     boolean wasMature, CropRemoveEvent.Reason reason) {
        CropPathDefinition path = registry.crop(crop.cropId())
            .flatMap(definition -> definition.path(crop.pathId()))
            .orElse(null);
        if (path == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (HarvestTable table : destructionTables(crop, path, wasMature, reason)) {
            for (HarvestOutcome outcome : LootResolver.resolve(table, null, random)) {
                generate(outcome, drops, commands, FertilizerDefinition.YieldEffect.NONE);
            }
        }
        drops.forEach(drop -> world.dropItemNaturally(location, drop));
        if (reason != CropRemoveEvent.Reason.BROKEN) {
            return;
        }
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    /**
     * The tables a destroyed crop rolls.
     *
     * <p>A liquid or a piston destroying a crop is a break, so it pays what a
     * break pays — including the produce table for a finished plant on a crop
     * whose {@code break-harvest} is on. Water washing a ripe field out and
     * leaving only the consolation seeds behind would read as the crop being
     * eaten, which is lava's job, not water's; a piston shearing a ripe plant
     * should hand back the same thing a player breaking it would.
     *
     * <p>{@code COMMANDS_ONLY} delivery is the one case where a mature crop
     * still returns nothing: that table asked for no items, and there is no
     * player here for its commands to reward either.
     */
    private List<HarvestTable> destructionTables(CropInstance crop, CropPathDefinition path,
                                                 boolean wasMature, CropRemoveEvent.Reason reason) {
        boolean destroyedRipe = reason == CropRemoveEvent.Reason.LIQUID
            || reason == CropRemoveEvent.Reason.PISTON;
        boolean payProduce = destroyedRipe
            && wasMature
            && registry.crop(crop.cropId())
                .map(definition -> definition.interaction().breakHarvest())
                .orElse(false);
        if (payProduce) {
            if (path.harvest().delivery() == DeliveryMode.COMMANDS_ONLY) {
                return List.of();
            }
            return List.of(path.harvest().primary(), path.harvest().additional());
        }
        BreakDefinition breakDrops = path.breakDrops();
        return breakDrops.isEmpty() ? List.of() : List.of(breakDrops.tableFor(wasMature));
    }
}
