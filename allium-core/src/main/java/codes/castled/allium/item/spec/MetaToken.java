package codes.castled.allium.item.spec;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A human metadata token: {@code key:value} (for example {@code sharpness:5}, {@code name:&6Blade})
 * or a bare word (for example {@code unbreakable}, with a {@code null} value).
 *
 * @param quoted whether the value was written in quotes, so it is taken literally
 */
public record MetaToken(String key, @Nullable String value, boolean quoted) {

    public MetaToken {
        Objects.requireNonNull(key, "key");
    }
}
