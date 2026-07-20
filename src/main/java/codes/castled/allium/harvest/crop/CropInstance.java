package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.List;
import java.util.UUID;

/**
 * Runtime state of one planted crop. Holds only ids — never references to
 * reloadable definition objects; the definition is resolved by id through the
 * registry whenever needed. The selected {@code pathId} is chosen exactly
 * once at plant time and persisted immediately, so it survives restarts,
 * chunk cycling, reloads and database reconnects.
 */
public final class CropInstance {

    private final UUID instanceId;
    private final String cropId;
    private final String pathId;
    private final BlockPositionKey position;
    private final long plantedAt;
    private final UUID planter;

    private int stage;
    private CropState state;
    private long stageStartedAt;
    /** Epoch millis when the next stage is due, or 0 when mature. */
    private long nextGrowthAt;
    /**
     * Milliseconds left to the next stage, captured when the chunk unloads
     * for LOADED_TIME crops (or REAL_TIME crops with offline growth off).
     */
    private long remainingMs;
    private UUID visualEntityId;
    /**
     * The Interaction hitbox that makes this crop clickable. Not persisted:
     * like the display, it is recreated from the crop row on chunk load, so
     * the database never has to be kept in step with entity lifetimes.
     */
    private UUID hitboxEntityId;
    private String fertilizerId;
    /**
     * Combined growth-speed multiplier from fertilizer and sprinkler coverage.
     * Cached on the instance rather than recomputed every growth pass, and
     * refreshed whenever one of its inputs changes (planting, fertilizing, a
     * sprinkler being placed or broken nearby).
     */
    private double speedMultiplier = 1.0D;
    /** All cells currently reserved by this crop (anchor first). */
    private List<BlockPositionKey> cells;

    public CropInstance(
        UUID instanceId, String cropId, String pathId, BlockPositionKey position,
        long plantedAt, UUID planter, int stage, CropState state,
        long stageStartedAt, long nextGrowthAt, long remainingMs,
        UUID visualEntityId, String fertilizerId, List<BlockPositionKey> cells
    ) {
        this.instanceId = instanceId;
        this.cropId = cropId;
        this.pathId = pathId;
        this.position = position;
        this.plantedAt = plantedAt;
        this.planter = planter;
        this.stage = stage;
        this.state = state;
        this.stageStartedAt = stageStartedAt;
        this.nextGrowthAt = nextGrowthAt;
        this.remainingMs = remainingMs;
        this.visualEntityId = visualEntityId;
        this.fertilizerId = fertilizerId;
        this.cells = cells == null || cells.isEmpty() ? List.of(position) : List.copyOf(cells);
    }

    public UUID instanceId() { return instanceId; }
    public String cropId() { return cropId; }
    public String pathId() { return pathId; }
    public BlockPositionKey position() { return position; }
    public long plantedAt() { return plantedAt; }
    public UUID planter() { return planter; }

    public int stage() { return stage; }
    public CropState state() { return state; }
    public long stageStartedAt() { return stageStartedAt; }
    public long nextGrowthAt() { return nextGrowthAt; }
    public long remainingMs() { return remainingMs; }
    public UUID visualEntityId() { return visualEntityId; }
    public UUID hitboxEntityId() { return hitboxEntityId; }
    public String fertilizerId() { return fertilizerId; }
    public double speedMultiplier() { return speedMultiplier; }
    public List<BlockPositionKey> cells() { return cells; }

    public void setStage(int stage) { this.stage = stage; }
    public void setState(CropState state) { this.state = state; }
    public void setStageStartedAt(long stageStartedAt) { this.stageStartedAt = stageStartedAt; }
    public void setNextGrowthAt(long nextGrowthAt) { this.nextGrowthAt = nextGrowthAt; }
    public void setRemainingMs(long remainingMs) { this.remainingMs = remainingMs; }
    public void setVisualEntityId(UUID visualEntityId) { this.visualEntityId = visualEntityId; }
    public void setHitboxEntityId(UUID hitboxEntityId) { this.hitboxEntityId = hitboxEntityId; }
    public void setFertilizerId(String fertilizerId) { this.fertilizerId = fertilizerId; }
    public void setSpeedMultiplier(double speedMultiplier) {
        this.speedMultiplier = GrowthSpeed.clamp(speedMultiplier);
    }
    public void setCells(List<BlockPositionKey> cells) {
        this.cells = cells == null || cells.isEmpty() ? List.of(position) : List.copyOf(cells);
    }

    public boolean isDue(long now) {
        return state == CropState.GROWING && nextGrowthAt > 0 && now >= nextGrowthAt;
    }
}
