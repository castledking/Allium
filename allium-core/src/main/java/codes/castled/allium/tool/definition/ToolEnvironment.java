package codes.castled.allium.tool.definition;

import java.util.Optional;

/**
 * What a definition is validated against that only the server knows. The plugin answers from
 * Bukkit's registries, the lang files and the core pack manifest; tests use fakes.
 */
public interface ToolEnvironment {

    /** Whether {@code material} (e.g. {@code golden_hoe}) is a vanilla item. */
    boolean isItemMaterial(String material);

    /** Whether {@code entityType} (namespaced, e.g. {@code minecraft:zombie}) exists. */
    boolean isEntityType(String entityType);

    /** Whether the default or active lang file has {@code key}. */
    boolean hasLangKey(String key);

    /** Whether {@code allium:<localId>} is a core pack item. */
    boolean isCoreItem(String localId);

    /**
     * The behavior a core pack item is reserved for, e.g. {@code allium:disarm} for
     * {@code mob_disarmer}. Empty for core items whose tool is not implemented yet.
     */
    Optional<String> coreItemBehavior(String localId);
}
