package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.crop.def.BlockRef;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.visual.VisualTags;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * Renders crop stages as one {@link ItemDisplay} per crop.
 *
 * <p><b>Persistence strategy:</b> displays are spawned non-persistent and are
 * reconstructed from the database whenever a chunk loads. The display entity
 * is therefore never authoritative — the crop row is. Reconciliation on chunk
 * load removes any leftover tagged displays that do not match a live crop
 * (e.g. from a crash before an unload persisted) and recreates missing ones,
 * so duplicates converge to exactly one display per crop.</p>
 *
 * <p>All methods must be called on the thread owning the crop's region.</p>
 */
public final class CropVisualService {

    /**
     * How far above the cell's floor a model is drawn when its stage says
     * nothing about its height: an eighth of a block, which is what clears
     * the soil for a model drawn in block space.
     */
    public static final double DISPLAY_Y_BASELINE = 0.125;

    private final Logger logger;
    private final CropRegistry registry;
    private final ItemResolverChain items;
    private final HarvestConfig.Visuals settings;
    private final CropBlockRenderer blocks;

    private long duplicatesRemoved;
    private long orphansRemoved;

    public CropVisualService(Logger logger, CropRegistry registry, ItemResolverChain items, HarvestConfig.Visuals settings) {
        this(logger, registry, items, settings, NoopCropBlockRenderer.INSTANCE);
    }

    public CropVisualService(Logger logger, CropRegistry registry, ItemResolverChain items,
                             HarvestConfig.Visuals settings, CropBlockRenderer blocks) {
        this.logger = logger;
        this.registry = registry;
        this.items = items;
        this.settings = settings;
        this.blocks = blocks == null ? NoopCropBlockRenderer.INSTANCE : blocks;
    }

    /**
     * Idempotently makes the crop's display match its current stage: creates
     * a display if missing, swaps the shown item if the model changed, and
     * leaves a correct display untouched.
     */
    public void ensureVisual(World world, CropInstance crop) {
        Optional<CropDefinition> definition = registry.crop(crop.cropId());
        if (definition.isEmpty()) {
            removeVisual(world, crop);
            return;
        }
        Optional<StageDefinition> stage = definition.get().path(crop.pathId())
            .map(path -> path.stage(crop.stage()));
        if (stage.isEmpty()) {
            removeVisual(world, crop);
            return;
        }
        if (stage.get().isBlockStage()) {
            ensureBlockVisual(world, crop, stage.get());
        }
        if (stage.get().hasModel()) {
            ensureModelVisual(world, crop, stage.get());
        }
    }

    /**
     * Draws the stage as a real block standing in the crop's cell.
     *
     * <p>Block stages need no display entity and no hitbox: the block itself
     * is what the player sees and clicks.</p>
     */
    /**
     * What the block standing in the crop's cell looks like right now.
     *
     * <p>For diagnostics: the wanted block and state come from the stage, the
     * actual ones from the provider, so a crop that renders the wrong stage
     * says so instead of just looking wrong.</p>
     */
    public BlockState blockStateOf(World world, CropInstance crop, StageDefinition stage) {
        BlockPositionKey anchor = crop.position();
        Block block = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
        return new BlockState(stage.block(), stage.state(),
            blocks.stateOf(block), blocks.matches(block, stage.block(), stage.state()));
    }

    /**
     * The wanted and the actual state of a crop's block.
     *
     * @param wanted the block the stage asks for
     * @param wantedState the state the stage asks for
     * @param actualState the state the block in the world actually has
     * @param matches whether the world already shows what the stage wants
     */
    public record BlockState(BlockRef wanted, Map<String, String> wantedState,
                             Map<String, String> actualState, boolean matches) {
    }

    private void ensureBlockVisual(World world, CropInstance crop, StageDefinition stage) {
        BlockPositionKey anchor = crop.position();
        Block block = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
        // A block stage needs neither a display nor a hitbox: the block is
        // what the player sees and clicks. If this crop used to be a model
        // stage, its leftovers have to go.
        ItemDisplay display = findDisplay(world, crop);
        if (display != null) {
            display.remove();
            crop.setVisualEntityId(null);
        }
        Interaction hitbox = findHitbox(world, crop);
        if (hitbox != null) {
            hitbox.remove();
            crop.setHitboxEntityId(null);
        }
        if (blocks.matches(block, stage.block(), stage.state())) {
            return;
        }
        if (!block.getType().isAir() && !blocks.isCustomBlock(block)) {
            logger.warning("[AlliumHarvest] Cannot render crop " + crop.cropId()
                + ": cell " + anchor + " is occupied by " + block.getType());
            return;
        }
        if (!blocks.apply(block, stage.block(), stage.state())) {
            logger.warning("[AlliumHarvest] Cannot render crop " + crop.cropId()
                + ": block " + stage.block() + " was not placed");
        }
    }

    private void ensureModelVisual(World world, CropInstance crop, StageDefinition stage) {
        String modelRef = stage.model().toString();

        // A crop drawn as a block leaves that block standing in its cell. If
        // the stage is now a model, the block has to go: the display would
        // otherwise be spawned inside it, and a plant that is never seen. A
        // stage that has both keeps its block — that is the point of it.
        if (!stage.isBlockStage()) {
            removeBlockVisual(world, crop);
        }

        ItemDisplay display = findDisplay(world, crop);
        if (display != null) {
            // Re-apply whenever the drawing no longer matches the stage, not
            // only when the model changed. A reload that moves a crop's
            // height, scale or transform has to reach the plants that are
            // already standing there, or the configuration only ever applies
            // to what is planted afterwards.
            if (!modelRef.equals(VisualTags.modelRefOf(display).orElse(null))
                || !matchesVisual(display, stage, crop)) {
                applyItem(display, stage, crop);
            }
            crop.setVisualEntityId(display.getUniqueId());
        } else {
            spawnDisplay(world, crop, stage);
        }
        ensureHitbox(world, crop, stage);
    }

    /**
     * Whether the display is already drawn the way the stage asks for.
     *
     * <p>Compared by value and not by identity: the height, the transform
     * the model is shown in, and the scale. A display that matches is left
     * alone, because re-applying it means rebuilding the item.</p>
     */
    private boolean matchesVisual(ItemDisplay display, StageDefinition stage, CropInstance crop) {
        StageDefinition.VisualOverride visual = stage.visual();
        double expectedHeight = crop.position().y() + displayHeight(visual);
        if (Math.abs(display.getLocation().getY() - expectedHeight) > 1.0E-6) {
            return false;
        }
        if (display.getItemDisplayTransform()
            != visual.transformOr(settings.displayTransform())) {
            return false;
        }
        Vector3f scale = display.getTransformation().getScale();
        float wanted = visual.scaleOr(settings.scale());
        return Math.abs(scale.x() - wanted) < 1.0E-4
            && Math.abs(scale.y() - wanted) < 1.0E-4
            && Math.abs(scale.z() - wanted) < 1.0E-4;
    }

    /**
     * Ensures the crop has exactly one {@link Interaction} hitbox.
     *
     * <p>A display entity has no hitbox at all — it cannot be clicked, and a
     * click aimed at one passes straight through to whatever is behind it.
     * Pairing each display with an Interaction is what makes a crop
     * right-clickable and punchable without needing a real block to stand in
     * for it.
     */
    private void ensureHitbox(World world, CropInstance crop, StageDefinition stage) {
        if (!settings.clickable()) {
            return;
        }
        if (findHitbox(world, crop) != null) {
            return;
        }
        BlockPositionKey anchor = crop.position();
        Location location = new Location(world,
            anchor.x() + 0.5D, anchor.y(), anchor.z() + 0.5D);
        Interaction hitbox = world.spawn(location, Interaction.class, entity -> {
            entity.setPersistent(false);
            entity.setInteractionWidth(settings.interactionWidth());
            entity.setInteractionHeight(settings.interactionHeight());
            // Responsive makes the client show an attack swing, so punching a
            // crop feels like breaking a block rather than hitting nothing.
            entity.setResponsive(true);
            VisualTags.tagCrop(entity, crop.instanceId(), crop.cropId(), anchor, stage.model().toString());
        });
        crop.setHitboxEntityId(hitbox.getUniqueId());
    }

    private Interaction findHitbox(World world, CropInstance crop) {
        UUID hitboxId = crop.hitboxEntityId();
        if (hitboxId != null) {
            Entity entity = world.getEntity(hitboxId);
            if (entity instanceof Interaction hitbox && hitbox.isValid()) {
                return hitbox;
            }
        }
        return null;
    }

    public void removeVisual(World world, CropInstance crop) {
        removeBlockVisual(world, crop);
        ItemDisplay display = findDisplay(world, crop);
        if (display != null) {
            display.remove();
        }
        crop.setVisualEntityId(null);
        Interaction hitbox = findHitbox(world, crop);
        if (hitbox != null) {
            hitbox.remove();
        }
        crop.setHitboxEntityId(null);
    }

    /**
     * Takes the block out of the crop's cell.
     *
     * <p>The cell belongs to the crop, so whatever stands there is removed
     * along with it. A crop that is drawn with a model has nothing to remove,
     * and a cell that holds a foreign block is left alone.</p>
     */
    private void removeBlockVisual(World world, CropInstance crop) {
        BlockPositionKey anchor = crop.position();
        Block block = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
        if (!blocks.isCustomBlock(block)) {
            return;
        }
        blocks.remove(block);
    }

    private ItemDisplay findDisplay(World world, CropInstance crop) {
        UUID visualId = crop.visualEntityId();
        if (visualId != null) {
            Entity entity = world.getEntity(visualId);
            if (entity instanceof ItemDisplay display && display.isValid()) {
                return display;
            }
        }
        return null;
    }

    private void spawnDisplay(World world, CropInstance crop, StageDefinition stage) {
        BlockPositionKey anchor = crop.position();
        StageDefinition.VisualOverride visual = stage.visual();
        Location location = new Location(world,
            anchor.x() + 0.5D,
            anchor.y() + displayHeight(visual),
            anchor.z() + 0.5D);
        ItemStack item = items.create(stage.model(), 1).orElse(null);
        if (item == null) {
            logger.warning("[AlliumHarvest] Cannot render crop " + crop.cropId()
                + ": model item " + stage.model() + " does not resolve");
            return;
        }
        ItemDisplay display = world.spawn(location, ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setItemStack(item);
            entity.setItemDisplayTransform(visual.transformOr(settings.displayTransform()));
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setViewRange(settings.viewRange() / 64.0F);
            entity.setShadowStrength(0.0F);
            entity.setTransformation(new Transformation(
                new Vector3f(0.0F, 0.0F, 0.0F),
                new org.joml.Quaternionf(),
                scaleVector(visual.scaleOr(settings.scale())),
                new org.joml.Quaternionf()));
            entity.setInterpolationDuration(0);
            entity.setTeleportDuration(0);
            VisualTags.tagCrop(entity, crop.instanceId(), crop.cropId(), anchor, stage.model().toString());
        });
        crop.setVisualEntityId(display.getUniqueId());
    }

    /**
     * The height a stage's model is drawn at, above the cell's floor.
     *
     * <p>The model's own y-offset is a correction, not a height: zero means
     * "as the model was authored", and a model authored in block space wants
     * to sit a little above the floor to clear the soil. The baseline is that
     * little lift, so a stage that says nothing about its height gets a crop
     * standing on the ground rather than in it.</p>
     */
    private double displayHeight(StageDefinition.VisualOverride visual) {
        return DISPLAY_Y_BASELINE + visual.yOffsetOr(settings.yOffset());
    }

    private void applyItem(ItemDisplay display, StageDefinition stage, CropInstance crop) {
        ItemStack item = items.create(stage.model(), 1).orElse(null);
        if (item == null) {
            logger.warning("[AlliumHarvest] Stage model " + stage.model() + " no longer resolves");
            return;
        }
        display.setItemStack(item);
        // Stages can differ in transform, scale and offset, so advancing a
        // stage has to re-apply all of them — swapping only the item would
        // leave the new model rendering with the previous stage's settings.
        StageDefinition.VisualOverride visual = stage.visual();
        display.setItemDisplayTransform(visual.transformOr(settings.displayTransform()));
        display.setTransformation(new Transformation(
            new Vector3f(0.0F, 0.0F, 0.0F),
            new org.joml.Quaternionf(),
            scaleVector(visual.scaleOr(settings.scale())),
            new org.joml.Quaternionf()));

        Location expected = new Location(display.getWorld(),
            crop.position().x() + 0.5D, crop.position().y() + displayHeight(visual),
            crop.position().z() + 0.5D);
        if (display.getLocation().getY() != expected.getY()) {
            display.teleport(expected);
        }
        VisualTags.tagCrop(display, crop.instanceId(), crop.cropId(), crop.position(), stage.model().toString());
    }

    private static Vector3f scaleVector(float scale) {
        return new Vector3f(scale, scale, scale);
    }

    /**
     * Reconciles every tagged crop display in a freshly loaded chunk against
     * the live crop set: removes orphans and duplicates, then ensures each
     * crop has its display. Safe to call repeatedly.
     */
    public void reconcileChunk(Chunk chunk, Map<UUID, CropInstance> cropsInChunk) {
        Map<UUID, ItemDisplay> kept = new HashMap<>();
        Set<UUID> claimedVisuals = new HashSet<>();
        // Hitboxes are reconciled in the same pass as displays: they are just
        // as capable of being orphaned or duplicated by a crash, and an
        // invisible leftover hitbox is worse than a visible leftover model
        // because nothing on screen explains why clicks are being eaten.
        Map<UUID, Interaction> keptHitboxes = new HashMap<>();

        for (Entity entity : chunk.getEntities()) {
            boolean isDisplay = entity instanceof ItemDisplay;
            boolean isHitbox = entity instanceof Interaction;
            if (!isDisplay && !isHitbox) continue;
            if (!VisualTags.isManaged(entity)) continue;
            if (VisualTags.kindOf(entity).orElse(null) != VisualTags.Kind.CROP) continue;

            UUID instanceId = VisualTags.cropInstanceOf(entity).orElse(null);
            CropInstance crop = instanceId == null ? null : cropsInChunk.get(instanceId);
            if (crop == null) {
                entity.remove();
                orphansRemoved++;
                continue;
            }
            Map<UUID, ?> alreadyKept = isDisplay ? kept : keptHitboxes;
            if (alreadyKept.containsKey(instanceId)) {
                entity.remove();
                duplicatesRemoved++;
                continue;
            }
            if (isDisplay) {
                kept.put(instanceId, (ItemDisplay) entity);
                claimedVisuals.add(entity.getUniqueId());
                crop.setVisualEntityId(entity.getUniqueId());
            } else {
                keptHitboxes.put(instanceId, (Interaction) entity);
                crop.setHitboxEntityId(entity.getUniqueId());
            }
        }
        // A hitbox with no surviving crop record is dropped above; one whose
        // crop exists but is no longer clickable is dropped here.
        if (!settings.clickable()) {
            keptHitboxes.forEach((instanceId, hitbox) -> {
                hitbox.remove();
                CropInstance crop = cropsInChunk.get(instanceId);
                if (crop != null) {
                    crop.setHitboxEntityId(null);
                }
            });
        }
        World world = chunk.getWorld();
        for (CropInstance crop : cropsInChunk.values()) {
            UUID visual = crop.visualEntityId();
            if (visual == null || !claimedVisuals.contains(visual)) {
                crop.setVisualEntityId(null);
            }
            ensureVisual(world, crop);
        }
    }

    public long duplicatesRemoved() { return duplicatesRemoved; }
    public long orphansRemoved() { return orphansRemoved; }
}
