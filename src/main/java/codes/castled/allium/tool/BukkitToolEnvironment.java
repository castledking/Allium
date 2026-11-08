package codes.castled.allium.tool;

import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.disarmer.DisarmBehaviorType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Answers definition checks from Bukkit's registries, the lang files and the core pack manifest. */
public final class BukkitToolEnvironment implements ToolEnvironment {

    /**
     * The behavior each core pack tool is reserved for. Core tools that are not implemented yet
     * (handcuffs, the Phantom Obliterator, the item renamer) are absent, so no definition can
     * claim their identities.
     */
    static final Map<String, String> CORE_TOOL_BEHAVIORS = Map.of("mob_disarmer", DisarmBehaviorType.ID);

    private final Set<String> coreItems;
    private final Predicate<String> langKeys;

    /**
     * @param coreItems the core pack's item IDs, e.g. {@code mob_disarmer}
     * @param langKeys  whether the lang files have a key
     */
    public BukkitToolEnvironment(Set<String> coreItems, Predicate<String> langKeys) {
        this.coreItems = Set.copyOf(coreItems);
        this.langKeys = langKeys;
    }

    @Override
    public boolean isItemMaterial(String material) {
        Material match = Material.matchMaterial(material);
        return match != null && match.isItem() && !match.isAir();
    }

    @Override
    public boolean isEntityType(String entityType) {
        NamespacedKey key = NamespacedKey.fromString(entityType);
        if (key == null) {
            return false;
        }
        try {
            return Registry.ENTITY_TYPE.get(key) != null;
        } catch (RuntimeException | LinkageError e) {
            for (EntityType type : EntityType.values()) {
                try {
                    if (type != EntityType.UNKNOWN && key.equals(type.getKey())) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // UNKNOWN and similar have no key
                }
            }
            return false;
        }
    }

    @Override
    public boolean hasLangKey(String key) {
        return Messages.fromKey(key) != null || langKeys.test(key);
    }

    @Override
    public boolean isCoreItem(String localId) {
        return coreItems.contains(localId);
    }

    @Override
    public Optional<String> coreItemBehavior(String localId) {
        return Optional.ofNullable(CORE_TOOL_BEHAVIORS.get(localId));
    }
}
