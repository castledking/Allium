package codes.castled.ores;

import org.bukkit.configuration.ConfigurationSection;

/** One ore's generation settings. Heights are inclusive. */
record OreDefinition(String name, BlockRef block, BlockRef deepslateBlock, int veinSize, int minHeight, int maxHeight,
                     double chancePerChunk, int veinsPerChunk, int lowlandMaxHeight, int singleAbove) {
    OreDefinition {
        if (!name.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid ore name");
        if (block.vanilla() || deepslateBlock.vanilla())
            throw new IllegalArgumentException("ore blocks need a CraftEngine pack namespace");
        if (veinSize < 1 || veinSize > 64 || veinsPerChunk < 1 || veinsPerChunk > 32)
            throw new IllegalArgumentException("vein-size must be 1..64 and veins-per-chunk 1..32");
        if (minHeight > maxHeight || minHeight < -2048 || maxHeight > 2048)
            throw new IllegalArgumentException("invalid height range");
        if (!Double.isFinite(chancePerChunk) || chancePerChunk < 0 || chancePerChunk > 1)
            throw new IllegalArgumentException("chance-per-chunk must be 0..1");
    }

    static OreDefinition read(String name, ConfigurationSection section) {
        // oraxen-id and ores-per-vein are the names legacy's first ore settings used.
        String block = section.getString("block", "sf:" + section.getString("oraxen-id", name + "_ore"));
        String deepslate = section.getString("deepslate-block", block);
        return new OreDefinition(name, BlockRef.parse(block), BlockRef.parse(deepslate),
                section.getInt("vein-size", section.getInt("ores-per-vein", 6)),
                section.getInt("min-height", -56), section.getInt("max-height", 16),
                section.getDouble("chance-per-chunk", 0.15), section.getInt("veins-per-chunk", 2),
                section.getInt("lowland-max-height", Integer.MAX_VALUE),
                section.getInt("single-above", Integer.MAX_VALUE));
    }

    /** The heights this ore generates at in a world; outside mountains, at most {@code lowland-max-height}. */
    HeightRange heights(int worldMin, int worldMaxExclusive, boolean mountainous) {
        int max = mountainous ? maxHeight : Math.min(maxHeight, lowlandMaxHeight);
        return new HeightRange(Math.max(minHeight, worldMin), Math.min(max, worldMaxExclusive - 1));
    }

    boolean placedAs(String blockId) {
        return block.toString().equals(blockId) || deepslateBlock.toString().equals(blockId);
    }

    record HeightRange(int min, int max) {
        boolean empty() { return min > max; }
        boolean contains(int y) { return y >= min && y <= max; }
    }
}
