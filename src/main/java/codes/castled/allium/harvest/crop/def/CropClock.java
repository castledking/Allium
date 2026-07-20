package codes.castled.allium.harvest.crop.def;

/** The clock used to advance crop growth. */
public enum CropClock {
    /** Wall-clock time; crops can grow while their chunk is unloaded. */
    REAL_TIME,
    /** Only time spent with the chunk loaded counts toward growth. */
    LOADED_TIME
}
