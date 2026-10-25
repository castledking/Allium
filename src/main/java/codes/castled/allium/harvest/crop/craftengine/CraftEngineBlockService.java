package codes.castled.allium.harvest.crop.craftengine;

import codes.castled.allium.harvest.crop.CropBlockRenderer;
import codes.castled.allium.harvest.crop.def.BlockRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.block.Block;

/**
 * Draws crop stages as real CraftEngine blocks.
 *
 * <p>Touches CraftEngine classes directly, so it must only be instantiated
 * once CraftEngine has loaded its packs (see {@code CraftEngineItemsGate}).
 */
public final class CraftEngineBlockService implements CropBlockRenderer {

    private final Logger logger;

    public CraftEngineBlockService(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean isCustomBlock(Block block) {
        if (block == null) {
            return false;
        }
        try {
            return CraftEngineBlocks.isCustomBlock(block);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean exists(BlockRef ref) {
        if (ref == null) {
            return false;
        }
        try {
            return CraftEngineBlocks.byId(Key.of(ref.namespace(), ref.id())) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean matches(Block block, BlockRef ref, Map<String, String> state) {
        if (block == null || ref == null) {
            return false;
        }
        BlockStateWrapper wrapper = wrapper(block);
        if (wrapper == null) {
            return false;
        }
        if (!ref.toString().equals(wrapper.ownerId().asString())) {
            return false;
        }
        for (Map.Entry<String, String> entry : state.entrySet()) {
            Object value = wrapper.getProperty(entry.getKey());
            if (value == null || !entry.getValue().equals(String.valueOf(value))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, String> stateOf(Block block) {
        BlockStateWrapper wrapper = wrapper(block);
        if (wrapper == null) {
            return Map.of();
        }
        Map<String, String> state = new LinkedHashMap<>();
        for (String name : wrapper.getPropertyNames()) {
            Object value = wrapper.getProperty(name);
            if (value != null) {
                state.put(name, String.valueOf(value));
            }
        }
        return state;
    }

    @Override
    public boolean apply(Block block, BlockRef ref, Map<String, String> state) {
        if (block == null || ref == null) {
            return false;
        }
        Key key = Key.of(ref.namespace(), ref.id());
        BlockDefinition definition;
        try {
            definition = CraftEngineBlocks.byId(key);
        } catch (Throwable t) {
            logger.warning("CraftEngine block '" + ref + "' could not be read: " + t);
            return false;
        }
        if (definition == null) {
            logger.warning("CraftEngine block '" + ref + "' does not exist");
            return false;
        }
        CompoundTag properties = new CompoundTag();
        for (Map.Entry<String, String> entry : state.entrySet()) {
            Property<?> property = definition.getProperty(entry.getKey());
            if (property == null) {
                logger.warning("CraftEngine block '" + ref + "' has no property '"
                    + entry.getKey() + "'");
                continue;
            }
            Optional<Tag> tag = property.createOptionalTag(entry.getValue());
            if (tag.isEmpty()) {
                logger.warning("CraftEngine block '" + ref + "' property '"
                    + entry.getKey() + "' has no value '" + entry.getValue() + "'");
                continue;
            }
            properties.put(entry.getKey(), tag.get());
        }
        try {
            return CraftEngineBlocks.place(block.getLocation(), key, properties, false, false);
        } catch (Throwable t) {
            logger.warning("CraftEngine block '" + ref + "' could not be placed: " + t);
            return false;
        }
    }

    @Override
    public boolean remove(Block block) {
        if (block == null) {
            return false;
        }
        try {
            return CraftEngineBlocks.remove(block, null, false, false, false);
        } catch (Throwable t) {
            logger.warning("CraftEngine block could not be removed: " + t);
            return false;
        }
    }

    private BlockStateWrapper wrapper(Block block) {
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            return state == null ? null : state.customBlockState();
        } catch (Throwable t) {
            return null;
        }
    }
}
