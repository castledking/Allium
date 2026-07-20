package codes.castled.allium.harvest.crop;

/** Lifecycle state of a planted crop instance. */
public enum CropState {
    /** Advancing through its path's stages. */
    GROWING,
    /** Reached the final stage of its path and can be harvested. */
    MATURE,
    /** Removed; kept only transiently while cleanup completes. */
    REMOVED
}
