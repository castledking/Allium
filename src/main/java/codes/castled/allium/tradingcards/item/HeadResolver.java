package codes.castled.allium.tradingcards.item;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.spawnercraft.MobHeadFactory;
import codes.castled.allium.spawnercraft.SpawnerHeadConfig;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

/**
 * Resolves the head a card trades for.
 *
 * <p>Two sources, in order: an explicit {@code head:} in the card definition,
 * then Allium's own mob head from {@code spawner_heads.yml}. A card can only be
 * traded in for a head that exists in one of them, so a card whose mob has no
 * head anywhere refuses the trade with a message rather than handing over a
 * bare skull the player cannot use.
 */
public final class HeadResolver {

    private HeadResolver() {}

    /**
     * The head this card pays out, or empty when the mob has none configured.
     *
     * @param definition the card's own definition, which may name a head item
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
        SpawnerHeadConfig.MobHead headData = SpawnerHeadConfig.get(mob);
        if (headData == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MobHeadFactory.create(plugin, mob, headData));
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
