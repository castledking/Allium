package codes.castled.allium.compat;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

public final class MaterialCompat {

    private static final Map<String, Material> LEGACY_MATERIALS = new HashMap<>();

    static {
        LEGACY_MATERIALS.put("PISTON_BASE", Material.PISTON);
        LEGACY_MATERIALS.put("PISTON_STICKY_BASE", Material.STICKY_PISTON);
        LEGACY_MATERIALS.put("WOOD", Material.OAK_PLANKS);
        LEGACY_MATERIALS.put("LOG", Material.OAK_LOG);
        LEGACY_MATERIALS.put("LEAVES", Material.OAK_LEAVES);
        LEGACY_MATERIALS.put("SKULL", Material.PLAYER_HEAD);
        LEGACY_MATERIALS.put("GOLD_PLATE", MechanismMaterialResolver.resolve("LIGHT_WEIGHTED_PRESSURE_PLATE", Material.HEAVY_WEIGHTED_PRESSURE_PLATE));
        LEGACY_MATERIALS.put("IRON_PLATE", MechanismMaterialResolver.resolve("HEAVY_WEIGHTED_PRESSURE_PLATE", Material.LIGHT_WEIGHTED_PRESSURE_PLATE));
        LEGACY_MATERIALS.put("STONE_PLATE", MechanismMaterialResolver.resolve("STONE_PRESSURE_PLATE", Material.OAK_PRESSURE_PLATE));
        LEGACY_MATERIALS.put("WOOD_PLATE", MechanismMaterialResolver.resolve("OAK_PRESSURE_PLATE", Material.OAK_PRESSURE_PLATE));
        LEGACY_MATERIALS.put("CROPS", MechanismMaterialResolver.resolve("WHEAT", Material.WHEAT));
        LEGACY_MATERIALS.put("SUGAR_CANE_BLOCK", MechanismMaterialResolver.resolve("SUGAR_CANE", Material.SUGAR_CANE));
        LEGACY_MATERIALS.put("CAULDRON_ITEM", MechanismMaterialResolver.resolve("CAULDRON", Material.CAULDRON));
        LEGACY_MATERIALS.put("BREWING_STAND_ITEM", MechanismMaterialResolver.resolve("BREWING_STAND", Material.BREWING_STAND));
        LEGACY_MATERIALS.put("FLINT_AND_STEEL", MechanismMaterialResolver.resolve("FLINT_AND_STEEL", Material.FLINT_AND_STEEL));
        LEGACY_MATERIALS.put("FLOWER_POT_ITEM", MechanismMaterialResolver.resolve("FLOWER_POT", Material.FLOWER_POT));
        LEGACY_MATERIALS.put("CARROT_ITEM", MechanismMaterialResolver.resolve("CARROT", Material.CARROT));
        LEGACY_MATERIALS.put("POTATO_ITEM", MechanismMaterialResolver.resolve("POTATO", Material.POTATO));
        LEGACY_MATERIALS.put("RED_ROSE", MechanismMaterialResolver.resolve("POPPY", Material.POPPY));
        LEGACY_MATERIALS.put("YELLOW_FLOWER", MechanismMaterialResolver.resolve("DANDELION", Material.DANDELION));
        LEGACY_MATERIALS.put("DOUBLE_PLANT", MechanismMaterialResolver.resolve("LILAC", Material.LILAC));
        LEGACY_MATERIALS.put("MONSTER_EGG", MechanismMaterialResolver.resolve("SPAWNER", Material.SPAWNER));
        LEGACY_MATERIALS.put("WEB", MechanismMaterialResolver.resolve("COBWEB", Material.COBWEB));
        LEGACY_MATERIALS.put("WORKBENCH", MechanismMaterialResolver.resolve("CRAFTING_TABLE", Material.CRAFTING_TABLE));
    }

    private MaterialCompat() {
    }

    public static Material getMaterial(String name) {
        Material material = Material.getMaterial(name);
        if (material != null) {
            return material;
        }

        Material legacy = LEGACY_MATERIALS.get(name);
        if (legacy != null) {
            return legacy;
        }

        return Material.AIR;
    }

    public static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static final class MechanismMaterialResolver {

        static Material resolve(String modern, Material fallback) {
            Material m = Material.getMaterial(modern);
            return m != null ? m : fallback;
        }
    }
}
