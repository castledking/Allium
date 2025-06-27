package net.survivalfun.core.managers.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.CreatureSpawner;

import java.util.HashMap;
import java.util.Map;

public class Spawner {

    private static PluginStart plugin;
    private static Lang lang;

    // Map common entity names to their EntityType
    private static final Map<String, EntityType> ENTITY_ALIASES = new HashMap<>();

    static {
        // Common mob aliases
        ENTITY_ALIASES.put("zombie", EntityType.ZOMBIE);
        ENTITY_ALIASES.put("skeleton", EntityType.SKELETON);
        ENTITY_ALIASES.put("creeper", EntityType.CREEPER);
        ENTITY_ALIASES.put("spider", EntityType.SPIDER);
        ENTITY_ALIASES.put("enderman", EntityType.ENDERMAN);
        ENTITY_ALIASES.put("witch", EntityType.WITCH);
        ENTITY_ALIASES.put("blaze", EntityType.BLAZE);
        ENTITY_ALIASES.put("ghast", EntityType.GHAST);
        ENTITY_ALIASES.put("pig", EntityType.PIG);
        ENTITY_ALIASES.put("cow", EntityType.COW);
        ENTITY_ALIASES.put("chicken", EntityType.CHICKEN);
        ENTITY_ALIASES.put("sheep", EntityType.SHEEP);
        ENTITY_ALIASES.put("horse", EntityType.HORSE);
        ENTITY_ALIASES.put("villager", EntityType.VILLAGER);
        ENTITY_ALIASES.put("iron_golem", EntityType.IRON_GOLEM);
        ENTITY_ALIASES.put("snow_golem", EntityType.SNOW_GOLEM);
        ENTITY_ALIASES.put("wither_skeleton", EntityType.WITHER_SKELETON);
        ENTITY_ALIASES.put("zombie_villager", EntityType.ZOMBIE_VILLAGER);
        ENTITY_ALIASES.put("husk", EntityType.HUSK);
        ENTITY_ALIASES.put("stray", EntityType.STRAY);
        ENTITY_ALIASES.put("drowned", EntityType.DROWNED);
        ENTITY_ALIASES.put("phantom", EntityType.PHANTOM);
        ENTITY_ALIASES.put("silverfish", EntityType.SILVERFISH);
        ENTITY_ALIASES.put("endermite", EntityType.ENDERMITE);
        ENTITY_ALIASES.put("slime", EntityType.SLIME);
        ENTITY_ALIASES.put("magma_cube", EntityType.MAGMA_CUBE);
        ENTITY_ALIASES.put("wither", EntityType.WITHER);
        ENTITY_ALIASES.put("ender_dragon", EntityType.ENDER_DRAGON);
        ENTITY_ALIASES.put("guardian", EntityType.GUARDIAN);
        ENTITY_ALIASES.put("elder_guardian", EntityType.ELDER_GUARDIAN);
        ENTITY_ALIASES.put("shulker", EntityType.SHULKER);
        ENTITY_ALIASES.put("vindicator", EntityType.VINDICATOR);
        ENTITY_ALIASES.put("evoker", EntityType.EVOKER);
        ENTITY_ALIASES.put("vex", EntityType.VEX);
        ENTITY_ALIASES.put("pillager", EntityType.PILLAGER);
        ENTITY_ALIASES.put("ravager", EntityType.RAVAGER);
        ENTITY_ALIASES.put("piglin", EntityType.PIGLIN);
        ENTITY_ALIASES.put("piglin_brute", EntityType.PIGLIN_BRUTE);
        ENTITY_ALIASES.put("zombified_piglin", EntityType.ZOMBIFIED_PIGLIN);
        ENTITY_ALIASES.put("hoglin", EntityType.HOGLIN);
        ENTITY_ALIASES.put("zoglin", EntityType.ZOGLIN);
        ENTITY_ALIASES.put("strider", EntityType.STRIDER);
        ENTITY_ALIASES.put("axolotl", EntityType.AXOLOTL);
        ENTITY_ALIASES.put("glow_squid", EntityType.GLOW_SQUID);
        ENTITY_ALIASES.put("goat", EntityType.GOAT);
        ENTITY_ALIASES.put("allay", EntityType.ALLAY);
        ENTITY_ALIASES.put("frog", EntityType.FROG);
        ENTITY_ALIASES.put("tadpole", EntityType.TADPOLE);
        ENTITY_ALIASES.put("warden", EntityType.WARDEN);
        ENTITY_ALIASES.put("camel", EntityType.CAMEL);
        ENTITY_ALIASES.put("sniffer", EntityType.SNIFFER);
        
        // Alternative names
        ENTITY_ALIASES.put("zombie_pigman", EntityType.ZOMBIFIED_PIGLIN);
        ENTITY_ALIASES.put("pig_zombie", EntityType.ZOMBIFIED_PIGLIN);
        ENTITY_ALIASES.put("magma", EntityType.MAGMA_CUBE);
        ENTITY_ALIASES.put("mooshroom", EntityType.MOOSHROOM);
        ENTITY_ALIASES.put("squid", EntityType.SQUID);
        ENTITY_ALIASES.put("bat", EntityType.BAT);
        ENTITY_ALIASES.put("wolf", EntityType.WOLF);
        ENTITY_ALIASES.put("cat", EntityType.CAT);
        ENTITY_ALIASES.put("ocelot", EntityType.OCELOT);
        ENTITY_ALIASES.put("parrot", EntityType.PARROT);
        ENTITY_ALIASES.put("rabbit", EntityType.RABBIT);
        ENTITY_ALIASES.put("llama", EntityType.LLAMA);
        ENTITY_ALIASES.put("mule", EntityType.MULE);
        ENTITY_ALIASES.put("donkey", EntityType.DONKEY);
        ENTITY_ALIASES.put("polar_bear", EntityType.POLAR_BEAR);
        ENTITY_ALIASES.put("dolphin", EntityType.DOLPHIN);
        ENTITY_ALIASES.put("turtle", EntityType.TURTLE);
        ENTITY_ALIASES.put("cod", EntityType.COD);
        ENTITY_ALIASES.put("salmon", EntityType.SALMON);
        ENTITY_ALIASES.put("pufferfish", EntityType.PUFFERFISH);
        ENTITY_ALIASES.put("tropical_fish", EntityType.TROPICAL_FISH);
    }

    public static void initialize(PluginStart pluginInstance) {
        plugin = pluginInstance;
        lang = plugin.getLangManager();
    }

    public static void applySpawnerMeta(CommandSender sender, ItemStack item, String[] parts) {
        if (!(item.getItemMeta() instanceof BlockStateMeta blockStateMeta)) {
            return;
        }

        if (!(blockStateMeta.getBlockState() instanceof CreatureSpawner spawnerState)) {
            return;
        }

        // Parse entity type from parts
        String entityName = null;
        String displayName = null;
        
        if (parts.length > 1) {
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) continue;
                
                if (part.toLowerCase().startsWith("name:")) {
                    displayName = part.substring(5);
                } else if (entityName == null) {
                    // First non-name parameter is the entity type
                    entityName = part;
                }
            }
        }

        if (entityName == null || entityName.isEmpty()) {
            // Show usage guide
            String usageMessage = lang.get("command-usage")
                .replace("{cmd}", "i")
                .replace("{args}", "spawner;<entity>");
            sender.sendMessage(usageMessage);
            sender.sendMessage("§eExample: §f/i spawner;zombie");
            sender.sendMessage("§eWith Name: §f/i spawner;skeleton;name:Custom Spawner");
            return;
        }

        EntityType entityType = getEntityType(entityName);
        if (entityType == null) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "entity type: " + entityName);
            return;
        }

        // Set the spawner entity type
        spawnerState.setSpawnedType(entityType);
        blockStateMeta.setBlockState(spawnerState);

        // Set display name
        if (displayName != null) {
            Meta.applyDisplayName(blockStateMeta, displayName);
        } else {
            // Generate display name based on entity type
            String formattedEntityName = formatEntityName(entityType);
            String generatedName = formattedEntityName + " Spawner";
            blockStateMeta.displayName(Component.text(generatedName).decoration(TextDecoration.ITALIC, false));
        }

        item.setItemMeta(blockStateMeta);
    }

    private static EntityType getEntityType(String entityName) {
        // First try direct EntityType lookup
        try {
            return EntityType.valueOf(entityName.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            // Fall back to aliases
        }

        // Try aliases
        return ENTITY_ALIASES.get(entityName.toLowerCase());
    }

    private static String formatEntityName(EntityType entityType) {
        // Convert entity type name to display format
        String name = entityType.name().toLowerCase().replace("_", " ");
        String[] words = name.split(" ");
        StringBuilder formatted = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            if (i > 0) formatted.append(" ");
            String word = words[i];
            if (!word.isEmpty()) {
                formatted.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    formatted.append(word.substring(1));
                }
            }
        }
        
        return formatted.toString();
    }

    public static boolean isValidEntity(String entityName) {
        return getEntityType(entityName) != null;
    }
}
