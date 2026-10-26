package codes.castled.allium.managers.world;

import codes.castled.allium.harvest.crop.def.BlockRef;
import org.bukkit.configuration.ConfigurationSection;

/** Generation settings for one ore; heights are inclusive. */
public record OreDefinition(String name, BlockRef block, BlockRef deepslateBlock,
                            int veinSize, int minHeight, int maxHeight,
                            double chancePerChunk, int veinsPerChunk,
                            int lowlandMaxHeight, int singleAbove) {
    public OreDefinition {
        if (!name.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid ore name");
        if (block.isVanilla() || deepslateBlock.isVanilla())
            throw new IllegalArgumentException("ore blocks require a CraftEngine pack namespace");
        if (veinSize < 1 || veinSize > 64 || veinsPerChunk < 1 || veinsPerChunk > 32)
            throw new IllegalArgumentException("vein-size must be 1..64 and veins-per-chunk 1..32");
        if (minHeight > maxHeight || minHeight < -2048 || maxHeight > 2048)
            throw new IllegalArgumentException("invalid height range");
        if (!Double.isFinite(chancePerChunk) || chancePerChunk < 0 || chancePerChunk > 1)
            throw new IllegalArgumentException("chance-per-chunk must be 0..1");
    }

    public static OreDefinition read(String name, ConfigurationSection section) {
        String block = section.getString("block", "sf:" + section.getString("oraxen-id", name + "_ore"));
        String deepslate = section.getString("deepslate-block", block);
        return new OreDefinition(name, BlockRef.parse(block), BlockRef.parse(deepslate),
            section.getInt("vein-size", section.getInt("ores-per-vein", 6)),
            section.getInt("min-height", -56), section.getInt("max-height", 16),
            section.getDouble("chance-per-chunk", 0.15), section.getInt("veins-per-chunk", 2),
            section.getInt("lowland-max-height", Integer.MAX_VALUE),
            section.getInt("single-above", Integer.MAX_VALUE));
    }

    public HeightRange heights(int worldMin, int worldMaxExclusive, boolean mountainous) {
        int max = mountainous ? maxHeight : Math.min(maxHeight, lowlandMaxHeight);
        return new HeightRange(Math.max(minHeight, worldMin), Math.min(max, worldMaxExclusive - 1));
    }

    public record HeightRange(int min, int max) {
        public boolean empty() { return min > max; }
        public boolean contains(int y) { return y >= min && y <= max; }
    }
}
