package codes.castled.allium.tradingcards.item;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.spawnercraft.MobHeadFactory;
import codes.castled.allium.spawnercraft.SpawnerHeadConfig;
import java.util.Optional;
import java.util.EnumMap;
import java.util.Map;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

/**
 * Resolves the head a card trades for.
 *
 * <p>Explicit card items take priority, then configured spawner heads, then
 * bundled trading rewards and vanilla skulls. Reward availability is independent
 * of which mobs have head drops enabled.
 */
public final class HeadResolver {

    private record Defaults(Map<EntityType, SpawnerHeadConfig.MobHead> heads, Map<EntityType, String> names) {}
    private static final Defaults DEFAULTS = loadDefaults();

    private HeadResolver() {}

    /**
     * The head this card pays out, or empty when the mob has none configured.
     *
     * @param configuredHead the definition's head reference, or null
     */
    public static Optional<ItemStack> resolve(Plugin plugin, ItemResolverChain items,
                                              EntityType mob, ItemRef configuredHead) {
        if (configuredHead != null) {
            Optional<ItemStack> explicit = items.create(configuredHead, 1);
            if (explicit.isPresent()) {
                return explicit;
            }
        }
        return resolveDefault(plugin, mob);
    }

    /** The configured spawner head, then a bundled card reward or vanilla skull. */
    public static Optional<ItemStack> resolveDefault(Plugin plugin, EntityType mob) {
        SpawnerHeadConfig.MobHead headData = headDataFor(mob);
        if (headData == null) {
            Material vanilla = vanillaHeadFor(mob);
            return vanilla == null ? Optional.empty() : Optional.of(new ItemStack(vanilla));
        }
        ItemStack head = MobHeadFactory.create(plugin, mob, headData);
        String itemName = defaultNameFor(mob);
        if (head != null && itemName != null && SpawnerHeadConfig.get(mob) == null) {
            var meta = head.getItemMeta();
            // The datapack's item name identifies mobs that borrow another mob's sound.
            meta.setItemName(itemName);
            head.setItemMeta(meta);
        }
        return Optional.ofNullable(head);
    }

    static SpawnerHeadConfig.MobHead headDataFor(EntityType mob) {
        var configured = SpawnerHeadConfig.get(mob);
        return configured != null ? configured : DEFAULTS.heads().get(mob);
    }

    static Material vanillaHeadFor(EntityType mob) {
        return switch (mob) {
            case SKELETON -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case ZOMBIE -> Material.ZOMBIE_HEAD;
            case PIGLIN, PIGLIN_BRUTE -> Material.PIGLIN_HEAD;
            default -> null;
        };
    }

    /** Availability without constructing a profile or touching a player's inventory. */
    public static boolean isAvailable(ItemResolverChain items, EntityType mob, ItemRef configuredHead) {
        return (configuredHead != null && items.exists(configuredHead))
            || hasDefaultHead(mob);
    }

    public static boolean hasDefaultHead(EntityType mob) {
        return headDataFor(mob) != null || vanillaHeadFor(mob) != null;
    }

    static String defaultNameFor(EntityType mob) {
        return DEFAULTS.names().get(mob);
    }

    private static Defaults loadDefaults() {
        Map<EntityType, SpawnerHeadConfig.MobHead> heads = new EnumMap<>(EntityType.class);
        Map<EntityType, String> names = new EnumMap<>(EntityType.class);
        // Keep existing spawner head skins as defaults, then cover the rest of the card roster.
        for (String resource : new String[]{"/spawner_heads.yml", "/tradingcards/heads.yml"}) {
            try (var stream = HeadResolver.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing head definitions: " + resource);
                var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                var section = yaml.getConfigurationSection("heads");
                if (section == null) throw new IllegalStateException("No heads in " + resource);
                for (String key : section.getKeys(false)) {
                    String sound = section.getString(key + ".sound", "");
                    String texture = section.getString(key + ".texture", "");
                    if (sound.isBlank() || texture.isBlank()) continue;
                    EntityType mob = EntityType.valueOf(key);
                    heads.putIfAbsent(mob, new SpawnerHeadConfig.MobHead(0, 0, 0, sound, texture));
                    String name = section.getString(key + ".name");
                    if (name != null && !name.isBlank()) names.putIfAbsent(mob, name);
                }
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Could not read head definitions: " + resource, error);
            }
        }
        return new Defaults(Map.copyOf(heads), Map.copyOf(names));
    }

    /** True when this mob has a tradeable head in either source. */
    public static boolean hasHead(Plugin plugin, ItemResolverChain items,
                                  EntityType mob, ItemRef configuredHead) {
        return resolve(plugin, items, mob, configuredHead).isPresent();
    }

    /**
     * A plain player head standing in for an icon, for the trade button before
     * the mob is resolved. Not given to the player — it is a menu decoration, so
     * it deliberately carries no card state and no trade value.
     */
    public static ItemStack iconFor(EntityType mob, int amount) {
        ItemStack icon = new ItemStack(Material.PLAYER_HEAD, Math.max(1, amount));
        if (icon.getItemMeta() instanceof SkullMeta meta) {
            try {
                meta.setOwnerProfile(Bukkit.createPlayerProfile(
                    java.util.UUID.nameUUIDFromBytes(
                        ("alliumcardicon:" + mob.name())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                icon.setItemMeta(meta);
            } catch (Throwable ignored) {
                // A bare skull is a perfectly good fallback icon.
            }
        }
        return icon;
    }
}
