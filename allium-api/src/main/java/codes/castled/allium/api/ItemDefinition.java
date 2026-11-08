package codes.castled.allium.api;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * An item an addon defines. It becomes {@code allium:<id>}: {@code /give}, kits and every other
 * item-bearing feature can create it, and {@link AddonItems#identify} recognizes it by its stamped
 * identity, never by its carrier material.
 *
 * @param id           local id ({@code a-z0-9_}); must not collide with Allium's or another addon's
 * @param carrier      the vanilla material the item is built on; never air
 * @param displayName  Allium-formatted name ({@code &} codes, hex, gradients), or {@code null}
 * @param customModelData model data for a resource pack, or {@code null}
 * @param defaultState initial state values, readable and writable through Allium's item service
 * @param signed       whether stacks carry an instance UUID and a server-secret MAC, so edited
 *                     state is rejected
 */
public record ItemDefinition(String id, Material carrier, @Nullable String displayName, @Nullable Integer customModelData,
                             Map<String, String> defaultState, boolean signed) {

    public ItemDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(carrier, "carrier");
        if (!id.matches("[a-z0-9_]{1,48}")) {
            throw new IllegalArgumentException("item id must be 1-48 characters of a-z, 0-9 and _: '" + id + "'");
        }
        defaultState = defaultState == null ? Map.of() : Map.copyOf(defaultState);
    }
}
