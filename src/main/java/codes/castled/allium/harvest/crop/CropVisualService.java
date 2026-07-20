package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.harvest.item.ItemResolverChain;
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

    private final Logger logger;
    private final CropRegistry registry;
    private final ItemResolverChain items;
    private final HarvestConfig.Visuals settings;

    private long duplicatesRemoved;
    private long orphansRemoved;

    public CropVisualService(Logger logger, CropRegistry registry, ItemResolverChain items, HarvestConfig.Visuals settings) {
        this.logger = logger;
        this.registry = registry;
        this.items = items;
        this.settings = settings;
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
        String modelRef = stage.get().model().toString();

        ItemDisplay display = findDisplay(world, crop);
        if (display != null) {
            if (!modelRef.equals(VisualTags.modelRefOf(display).orElse(null))) {
                applyItem(display, stage.get(), crop);
            }
            crop.setVisualEntityId(display.getUniqueId());
        } else {
            spawnDisplay(world, crop, stage.get());
        }
        ensureHitbox(world, crop, stage.get());
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
            anchor.y() + visual.yOffsetOr(settings.yOffset()),
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

        double yOffset = visual.yOffsetOr(settings.yOffset());
        Location expected = new Location(display.getWorld(),
            crop.position().x() + 0.5D, crop.position().y() + yOffset, crop.position().z() + 0.5D);
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
