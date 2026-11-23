package codes.castled.allium.config;

import java.util.List;
import java.util.Objects;

/**
 * Truthful summary of a configuration reload, distinguishing which targets were applied,
 * which were rejected, and which applied changes require a server restart to take full effect.
 */
public record ReloadReport(
        boolean success,
        List<String> applied,
        List<Entry> rejected,
        List<Entry> restartRequired
) {
    public record Entry(String target, String reason) {
        public Entry {
            target = Objects.requireNonNull(target, "target");
            reason = reason == null || reason.isBlank() ? "unknown error" : reason.trim();
        }
    }

    public ReloadReport {
        applied = List.copyOf(applied);
        rejected = List.copyOf(rejected);
        restartRequired = List.copyOf(restartRequired);
    }
}
