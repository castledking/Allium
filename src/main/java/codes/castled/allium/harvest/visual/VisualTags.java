package codes.castled.allium.harvest.visual;

import codes.castled.allium.harvest.HarvestBranding;
import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Shared PersistentDataContainer tagging for every display entity the harvest
 * module owns (crop stages and spawner overlays). Tags are the single source
 * of truth during reconciliation: an entity with our marker but no matching
 * record is an orphan and gets removed; a record whose entity is missing gets
 * a fresh display.
 */
public final class VisualTags {

    public enum Kind { CROP, SPAWNER }

    public static final NamespacedKey MARKER = key("managed_visual");
    public static final NamespacedKey KIND = key("visual_kind");
    public static final NamespacedKey INSTANCE = key("crop_instance");
    public static final NamespacedKey CROP_ID = key("crop_id");
    public static final NamespacedKey BLOCK_X = key("block_x");
    public static final NamespacedKey BLOCK_Y = key("block_y");
    public static final NamespacedKey BLOCK_Z = key("block_z");
    public static final NamespacedKey ENTITY_TYPE = key("entity_type");
    public static final NamespacedKey MODEL_REF = key("model_ref");

    private static NamespacedKey key(String name) {
        return new NamespacedKey(HarvestBranding.NAMESPACE, name);
    }

    private VisualTags() {}

    public static void tagCrop(Entity entity, UUID instanceId, String cropId, BlockPositionKey anchor, String modelRef) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KIND, PersistentDataType.STRING, Kind.CROP.name());
        pdc.set(INSTANCE, PersistentDataType.STRING, instanceId.toString());
        pdc.set(CROP_ID, PersistentDataType.STRING, cropId);
        tagPosition(pdc, anchor);
        pdc.set(MODEL_REF, PersistentDataType.STRING, modelRef);
    }

    public static void tagSpawner(Entity entity, BlockPositionKey position, String entityType, String modelRef) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KIND, PersistentDataType.STRING, Kind.SPAWNER.name());
        pdc.set(ENTITY_TYPE, PersistentDataType.STRING, entityType);
        tagPosition(pdc, position);
        pdc.set(MODEL_REF, PersistentDataType.STRING, modelRef);
    }

    private static void tagPosition(PersistentDataContainer pdc, BlockPositionKey position) {
        pdc.set(BLOCK_X, PersistentDataType.INTEGER, position.x());
        pdc.set(BLOCK_Y, PersistentDataType.INTEGER, position.y());
        pdc.set(BLOCK_Z, PersistentDataType.INTEGER, position.z());
    }

    public static boolean isManaged(Entity entity) {
        return entity.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
    }

    public static Optional<Kind> kindOf(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(KIND, PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(Kind.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Optional<UUID> cropInstanceOf(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(INSTANCE, PersistentDataType.STRING);
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Optional<BlockPositionKey> positionOf(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Integer x = pdc.get(BLOCK_X, PersistentDataType.INTEGER);
        Integer y = pdc.get(BLOCK_Y, PersistentDataType.INTEGER);
        Integer z = pdc.get(BLOCK_Z, PersistentDataType.INTEGER);
        if (x == null || y == null || z == null) return Optional.empty();
        return Optional.of(new BlockPositionKey(entity.getWorld().getUID(), x, y, z));
    }

    public static Optional<String> modelRefOf(Entity entity) {
        return Optional.ofNullable(
            entity.getPersistentDataContainer().get(MODEL_REF, PersistentDataType.STRING));
    }
}
