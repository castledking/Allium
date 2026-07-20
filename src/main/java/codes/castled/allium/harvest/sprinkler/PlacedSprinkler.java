package codes.castled.allium.harvest.sprinkler;

import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.UUID;

/**
 * A sprinkler block placed in the world.
 *
 * <p>Holds only the tier id, never the {@link SprinklerDefinition} itself, so a
 * config reload can replace every tier without leaving placed sprinklers
 * pointing at stale definitions.
 */
public final class PlacedSprinkler {

    private final BlockPositionKey position;
    private final String sprinklerId;
    private final long placedAt;
    private final UUID owner;
    private UUID visualEntityId;

    public PlacedSprinkler(BlockPositionKey position, String sprinklerId,
                           long placedAt, UUID owner, UUID visualEntityId) {
        this.position = position;
        this.sprinklerId = sprinklerId;
        this.placedAt = placedAt;
        this.owner = owner;
        this.visualEntityId = visualEntityId;
    }

    public BlockPositionKey position() { return position; }
    public String sprinklerId() { return sprinklerId; }
    public long placedAt() { return placedAt; }
    public UUID owner() { return owner; }
    public UUID visualEntityId() { return visualEntityId; }

    public void setVisualEntityId(UUID visualEntityId) {
        this.visualEntityId = visualEntityId;
    }
}
