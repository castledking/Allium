package codes.castled.allium.permissions.command;

import codes.castled.allium.managers.DB.Database;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of {@link CommandPermissionOverride}s, backed by {@link Database}.
 * Consulted by {@link DefaultCommandPermissionResolver} as the last resort when no
 * adapter can classify a command's permission on its own.
 */
public final class CommandPermissionOverrideStore {

    private final Map<String, CommandPermissionOverride> overrides = new ConcurrentHashMap<>();

    /** Reloads every override from the database, replacing the current cache. */
    public void reload(@NotNull Database database) {
        List<CommandPermissionOverride> loaded = database.getAllCommandPermissionOverrides();
        overrides.clear();
        for (CommandPermissionOverride override : loaded) {
            overrides.put(override.commandLabel().toLowerCase(Locale.ROOT), override);
        }
    }

    @Nullable
    public CommandPermissionOverride get(@NotNull String commandLabel) {
        return overrides.get(commandLabel.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    @NotNull
    public Collection<CommandPermissionOverride> all() {
        return overrides.values();
    }
}
