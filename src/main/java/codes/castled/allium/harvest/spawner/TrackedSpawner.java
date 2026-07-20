package codes.castled.allium.harvest.spawner;

import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.UUID;

/** In-memory + persisted record of one known spawner block. */
public final class TrackedSpawner {

    private final BlockPositionKey position;
    private String entityType;
    private UUID visualEntityId;
    private long updatedAt;

    public TrackedSpawner(BlockPositionKey position, String entityType, UUID visualEntityId, long updatedAt) {
        this.position = position;
        this.entityType = entityType;
        this.visualEntityId = visualEntityId;
        this.updatedAt = updatedAt;
    }

    public BlockPositionKey position() { return position; }
    public String entityType() { return entityType; }
    public UUID visualEntityId() { return visualEntityId; }
    public long updatedAt() { return updatedAt; }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setVisualEntityId(UUID visualEntityId) {
        this.visualEntityId = visualEntityId;
        this.updatedAt = System.currentTimeMillis();
    }
}
