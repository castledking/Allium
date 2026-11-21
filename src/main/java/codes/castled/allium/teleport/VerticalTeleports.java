package codes.castled.allium.teleport;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Where {@code /top} and {@code /bottom} land in the player's own block column: the highest or the
 * lowest block a player can stand on, with two free blocks above it. For {@code /top}, a water
 * surface (such as an ocean or lake above a cave) is also a valid surface landing when it has two
 * free air blocks above it. In a world with a roof (the Nether) {@code /top} stays under it unless
 * the player may stand on the roof ({@code allium.top.roof}). {@code /bottom} only looks below the
 * player.
 */
public final class VerticalTeleports {

    public static final String PERMISSION_ROOF = "allium.top.roof";

    private static final Set<Material> HAZARDS = Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.FIRE, Material.SOUL_FIRE, Material.COBWEB, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.LAVA, Material.WITHER_ROSE);

    private VerticalTeleports() {
    }

    /** One block column: can a player stand on {@code y}, and is {@code y} free to stand in? */
    interface Column {
        boolean ground(int y);

        boolean space(int y);

        default boolean water(int y) {
            return false;
        }
    }

    /** The standing height (feet) of the highest ground (or water surface) at or below {@code from}, down to {@code min}. */
    static OptionalInt highest(Column column, int from, int min) {
        for (int y = from; y >= min; y--) {
            if ((column.ground(y) || column.water(y)) && column.space(y + 1) && column.space(y + 2)) {
                return OptionalInt.of(y + 1);
            }
        }
        return OptionalInt.empty();
    }

    /** The standing height of the lowest ground from {@code min} whose feet stay below {@code below}. */
    static OptionalInt lowest(Column column, int min, int below) {
        for (int y = min; y + 1 < below; y++) {
            if (column.ground(y) && column.space(y + 1) && column.space(y + 2)) {
                return OptionalInt.of(y + 1);
            }
        }
        return OptionalInt.empty();
    }

    public static Optional<Location> top(Player player) {
        Location here = player.getLocation();
        World world = here.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        int max = world.getMaxHeight() - 3;
        int from;
        if (world.hasCeiling() && !player.hasPermission(PERMISSION_ROOF)) {
            // Feet and head must stay below the roof's top layer.
            from = Math.min(max, world.getLogicalHeight() - 3);
        } else {
            int highest = Math.max(
                    world.getHighestBlockYAt(here.getBlockX(), here.getBlockZ()),
                    world.getHighestBlockYAt(here.getBlockX(), here.getBlockZ(), HeightMap.WORLD_SURFACE));
            from = Math.min(max, highest);
        }
        return at(here, highest(column(world, here), from, world.getMinHeight()));
    }

    public static Optional<Location> bottom(Player player) {
        Location here = player.getLocation();
        World world = here.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        return at(here, lowest(column(world, here), world.getMinHeight(), here.getBlockY()));
    }

    private static Column column(World world, Location here) {
        int x = here.getBlockX();
        int z = here.getBlockZ();
        return new Column() {
            @Override
            public boolean ground(int y) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                Material type = world.getBlockAt(x, y, z).getType();
                return type.isSolid() && !HAZARDS.contains(type);
            }

            @Override
            public boolean water(int y) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                return world.getBlockAt(x, y, z).getType() == Material.WATER;
            }

            @Override
            public boolean space(int y) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                Block block = world.getBlockAt(x, y, z);
                return block.isPassable() && !block.isLiquid() && !HAZARDS.contains(block.getType());
            }
        };
    }

    private static Optional<Location> at(Location here, OptionalInt feet) {
        if (feet.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Location(here.getWorld(), here.getBlockX() + 0.5, feet.getAsInt(), here.getBlockZ() + 0.5,
                here.getYaw(), here.getPitch()));
    }
}
