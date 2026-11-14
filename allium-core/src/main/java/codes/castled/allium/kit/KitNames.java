package codes.castled.allium.kit;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Strict kit name validation ({@code ^[a-z0-9_-]{1,32}$}).
 *
 * <p>Validated everywhere a kit name becomes a filesystem path ({@code kits/<name>.yml}) or a
 * permission node ({@code allium.kits.<name>}): creation, deletion, YAML loading, claiming, and
 * tab completion.
 */
public final class KitNames {

    public static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private KitNames() {
    }

    public static boolean isValid(@Nullable String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public static String validate(@Nullable String raw) {
        if (!isValid(raw)) {
            throw new IllegalArgumentException(
                    "Invalid kit name '" + raw + "': must match ^[a-z0-9_-]{1,32}$");
        }
        return raw;
    }
}
