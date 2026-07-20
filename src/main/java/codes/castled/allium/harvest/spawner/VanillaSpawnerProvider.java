package codes.castled.allium.harvest.spawner;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;

/** Plain vanilla spawner semantics — always available fallback provider. */
public final class VanillaSpawnerProvider implements SpawnerProvider {

    @Override
    public boolean owns(Block block) {
        return block.getType() == Material.SPAWNER;
    }

    @Override
    public Optional<EntityType> getEntityType(Block block) {
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            return Optional.empty();
        }
        return Optional.ofNullable(spawner.getSpawnedType());
    }

    @Override
    public int getStackSize(Block block) {
        return 1;
    }

    @Override
    public Optional<UUID> getOwner(Block block) {
        return Optional.empty();
    }

    @Override
    public boolean isEnabled(Block block) {
        return true;
    }
}
