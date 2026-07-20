package codes.castled.allium.harvest.spawner;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;

/**
 * SPI over spawner implementations. The vanilla provider always works;
 * stacked-spawner plugins (RoseStacker, WildStacker, …) can register a
 * higher-priority provider through Bukkit's ServicesManager and the harvest
 * module will consult it for stack sizes and ownership without any hard
 * dependency.
 */
public interface SpawnerProvider {

    /** Whether this provider manages the given spawner block. */
    boolean owns(Block block);

    Optional<EntityType> getEntityType(Block block);

    /** Stack size for variant selection; vanilla spawners are size 1. */
    int getStackSize(Block block);

    Optional<UUID> getOwner(Block block);

    /** Whether the spawner is currently active/enabled. */
    boolean isEnabled(Block block);
}
