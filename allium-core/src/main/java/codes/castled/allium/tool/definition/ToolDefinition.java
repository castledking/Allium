package codes.castled.allium.tool.definition;

import codes.castled.allium.item.ProviderKey;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One validated tool definition file, such as {@code tools/mob_disarmer.yml}.
 *
 * <p>{@code id} is the tool's signed item identity: the behavior ID {@code ItemService} stamps
 * and signs on every stack. It never changes for existing tools. {@code behavior} names the core
 * behavior that acts for it ({@code allium:disarm}), and {@code parameters} are that behavior's
 * validated settings. Several definitions may bind the same behavior; each keeps its own identity
 * and state.
 *
 * @param file        the file name relative to the plugin folder, e.g. {@code tools/mob_disarmer.yml}
 * @param item        the built-in item this definition declares, or {@code null} for a core pack item
 * @param appearance  what new tools look like
 * @param aliases     provider items that act as this tool when their provider issues them
 * @param permissions permission node per behavior slot
 * @param messages    lang key per behavior message slot
 * @param sounds      sound per behavior sound slot
 */
public record ToolDefinition(
        String file,
        String id,
        String name,
        String behavior,
        boolean enabled,
        @Nullable BuiltInItemSpec item,
        ProviderKey appearance,
        List<ProviderKey> aliases,
        Map<String, String> permissions,
        Map<String, String> messages,
        Map<String, ToolSound> sounds,
        ToolParameters parameters
) {
    public ToolDefinition {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(parameters, "parameters");
        aliases = List.copyOf(aliases);
        permissions = Map.copyOf(new LinkedHashMap<>(permissions));
        messages = Map.copyOf(new LinkedHashMap<>(messages));
        sounds = Map.copyOf(new LinkedHashMap<>(sounds));
    }

    /** The {@code <id>} of {@code allium:<id>}. */
    public String localId() {
        return id.substring(id.indexOf(':') + 1);
    }

    public <P extends ToolParameters> P parameters(Class<P> type) {
        return type.cast(parameters);
    }

    /** Every appearance that identifies as this tool: the default, the aliases and the behavior's extras. */
    public Set<ProviderKey> boundAppearances() {
        Set<ProviderKey> keys = new LinkedHashSet<>();
        keys.add(appearance);
        keys.addAll(aliases);
        keys.addAll(parameters.extraAppearances());
        return keys;
    }

    public String permission(String slot) {
        String node = permissions.get(slot);
        if (node == null) {
            throw new IllegalArgumentException(id + " has no permission slot " + slot);
        }
        return node;
    }

    public String message(String slot) {
        String key = messages.get(slot);
        if (key == null) {
            throw new IllegalArgumentException(id + " has no message slot " + slot);
        }
        return key;
    }

    public ToolSound sound(String slot) {
        ToolSound sound = sounds.get(slot);
        if (sound == null) {
            throw new IllegalArgumentException(id + " has no sound slot " + slot);
        }
        return sound;
    }
}
