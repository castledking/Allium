package codes.castled.allium.protection;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Outcome of a protection check.
 *
 * @param source  the hook that decided, e.g. {@code GriefPrevention} or {@code event}
 * @param message optional denial text supplied by the protecting plugin
 */
public record ProtectionDecision(boolean allowed, String source, @Nullable String message) {

    public ProtectionDecision {
        Objects.requireNonNull(source, "source");
    }

    public static ProtectionDecision allow() {
        return new ProtectionDecision(true, "none", null);
    }

    public static ProtectionDecision deny(String source, @Nullable String message) {
        return new ProtectionDecision(false, source, message);
    }
}
