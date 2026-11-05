package codes.castled.allium.command.spec;

import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral locale message keys associated with a command specification.
 */
public record CommandMessageKeys(
        String noPermissionKey,
        String invalidArgsKey,
        String playerNotFoundKey,
        String playerOnlyKey,
        String moduleDisabledKey,
        Map<String, String> outcomeKeys
) {

    public static final String DEFAULT_NO_PERMISSION_KEY = "error.no-permission";
    public static final String DEFAULT_INVALID_ARGS_KEY = "error.invalid-args";
    public static final String DEFAULT_PLAYER_NOT_FOUND_KEY = "error.player-not-found";
    public static final String DEFAULT_PLAYER_ONLY_KEY = "error.player-only";
    public static final String DEFAULT_MODULE_DISABLED_KEY = "error.module-disabled";

    public CommandMessageKeys {
        Objects.requireNonNull(noPermissionKey, "noPermissionKey");
        Objects.requireNonNull(invalidArgsKey, "invalidArgsKey");
        Objects.requireNonNull(playerNotFoundKey, "playerNotFoundKey");
        Objects.requireNonNull(playerOnlyKey, "playerOnlyKey");
        Objects.requireNonNull(moduleDisabledKey, "moduleDisabledKey");
        outcomeKeys = outcomeKeys == null ? Map.of() : Map.copyOf(outcomeKeys);
    }

    public static CommandMessageKeys ofOutcomes(Map<String, String> outcomeKeys) {
        return new CommandMessageKeys(
                DEFAULT_NO_PERMISSION_KEY,
                DEFAULT_INVALID_ARGS_KEY,
                DEFAULT_PLAYER_NOT_FOUND_KEY,
                DEFAULT_PLAYER_ONLY_KEY,
                DEFAULT_MODULE_DISABLED_KEY,
                outcomeKeys
        );
    }

    public String outcomeKey(String name) {
        return outcomeKeys.get(name);
    }
}
