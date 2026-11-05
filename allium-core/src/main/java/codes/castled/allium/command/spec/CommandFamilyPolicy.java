package codes.castled.allium.command.spec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Platform-neutral command family ownership and {@code disabled-commands: []} policy engine.
 *
 * <ul>
 *   <li>Normalizes case and strips optional leading {@code /} from {@code disabled-commands} entries.</li>
 *   <li>Resolves canonical names, aliases (e.g. {@code gm}), and namespaced labels (e.g. {@code allium:gm})
 *       to their canonical command family.</li>
 *   <li>Rejects unknown entries with bounded diagnostics without creating a global command blocker.</li>
 *   <li>Tracks startup registration state vs. live reload state and reports when a server restart is
 *       required to yield or reclaim Bukkit {@code CommandMap} labels.</li>
 * </ul>
 */
public final class CommandFamilyPolicy {

    private final Map<String, CommandSpec> specsByCanonical = new LinkedHashMap<>();
    private final Map<String, String> labelToCanonical = new LinkedHashMap<>();

    private Set<String> startupDisabledFamilies = Set.of();
    private Set<String> activeDisabledFamilies = Set.of();
    private boolean startupInitialized = false;

    public void registerSpec(CommandSpec spec) {
        Objects.requireNonNull(spec, "spec");
        specsByCanonical.put(spec.canonicalName(), spec);
        for (String label : spec.allOwnedLabels()) {
            labelToCanonical.put(label.toLowerCase(Locale.ROOT), spec.canonicalName());
        }
    }

    public void registerSpecs(Collection<CommandSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        for (CommandSpec spec : specs) {
            registerSpec(spec);
        }
    }

    public Optional<CommandSpec> findSpec(String labelOrAlias) {
        return resolveCanonicalFamily(labelOrAlias).map(specsByCanonical::get);
    }

    public Map<String, CommandSpec> registeredSpecs() {
        return Collections.unmodifiableMap(specsByCanonical);
    }

    /**
     * Normalizes a raw command label or config entry and resolves it to a registered canonical
     * command family name if it belongs to Allium.
     */
    public Optional<String> resolveCanonicalFamily(String rawEntry) {
        String normalized = normalizeEntry(rawEntry);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String canonical = labelToCanonical.get(normalized);
        return Optional.ofNullable(canonical);
    }

    /**
     * Initializes the disabled-command policy at server startup.
     * Families disabled at startup yield their {@code CommandMap} labels so other plugins can own them.
     */
    public PolicyEvaluation initializeStartup(Collection<String> rawDisabledCommands) {
        ParsedConfig parsed = parseDisabledConfig(rawDisabledCommands);
        this.startupDisabledFamilies = parsed.disabledCanonicalFamilies();
        this.activeDisabledFamilies = parsed.disabledCanonicalFamilies();
        this.startupInitialized = true;
        return new PolicyEvaluation(
                this.activeDisabledFamilies,
                Set.of(),
                Set.of(),
                parsed.unknownEntries()
        );
    }

    /**
     * Evaluates updated {@code disabled-commands} on configuration reload.
     * Immediately blocks newly disabled families at dispatch time and reports any families whose
     * {@code CommandMap} ownership change requires a server restart.
     */
    public PolicyEvaluation applyReload(Collection<String> rawDisabledCommands) {
        if (!startupInitialized) {
            return initializeStartup(rawDisabledCommands);
        }
        ParsedConfig parsed = parseDisabledConfig(rawDisabledCommands);
        Set<String> newActive = parsed.disabledCanonicalFamilies();

        Set<String> newlyDisabledSinceStartup = new LinkedHashSet<>(newActive);
        newlyDisabledSinceStartup.removeAll(this.startupDisabledFamilies);

        Set<String> newlyEnabledSinceStartup = new LinkedHashSet<>(this.startupDisabledFamilies);
        newlyEnabledSinceStartup.removeAll(newActive);

        this.activeDisabledFamilies = newActive;

        return new PolicyEvaluation(
                this.activeDisabledFamilies,
                Collections.unmodifiableSet(newlyDisabledSinceStartup),
                Collections.unmodifiableSet(newlyEnabledSinceStartup),
                parsed.unknownEntries()
        );
    }

    /**
     * Returns true if the command family identified by the given canonical name, alias, or
     * {@code allium:<label>} is currently disabled.
     */
    public boolean isDisabled(String labelOrAlias) {
        return resolveCanonicalFamily(labelOrAlias)
                .map(activeDisabledFamilies::contains)
                .orElse(false);
    }

    /**
     * Returns true if the canonical family was disabled at startup (meaning its labels were yielded
     * from Bukkit's {@code CommandMap}).
     */
    public boolean isDisabledAtStartup(String canonicalName) {
        if (canonicalName == null) {
            return false;
        }
        return startupDisabledFamilies.contains(canonicalName.trim().toLowerCase(Locale.ROOT));
    }

    public Set<String> activeDisabledFamilies() {
        return activeDisabledFamilies;
    }

    public Set<String> startupDisabledFamilies() {
        return startupDisabledFamilies;
    }

    public static String normalizeEntry(String rawEntry) {
        if (rawEntry == null) {
            return "";
        }
        String trimmed = rawEntry.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private ParsedConfig parseDisabledConfig(Collection<String> rawDisabledCommands) {
        if (rawDisabledCommands == null || rawDisabledCommands.isEmpty()) {
            return new ParsedConfig(Set.of(), List.of());
        }
        Set<String> disabledCanonical = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        Set<String> seenUnknown = new LinkedHashSet<>();

        for (String raw : rawDisabledCommands) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Optional<String> resolved = resolveCanonicalFamily(raw);
            if (resolved.isPresent()) {
                disabledCanonical.add(resolved.get());
            } else {
                String normalized = normalizeEntry(raw);
                if (!normalized.isEmpty() && seenUnknown.add(normalized)) {
                    unknown.add(raw.trim());
                }
            }
        }
        return new ParsedConfig(
                Collections.unmodifiableSet(disabledCanonical),
                Collections.unmodifiableList(unknown)
        );
    }

    private record ParsedConfig(
            Set<String> disabledCanonicalFamilies,
            List<String> unknownEntries
    ) {
    }

    public record PolicyEvaluation(
            Set<String> activeDisabledFamilies,
            Set<String> newlyDisabledRequiringRestartToYield,
            Set<String> newlyEnabledRequiringRestartToClaim,
            List<String> unknownEntries
    ) {
        public boolean requiresRestartForCommandMap() {
            return !newlyDisabledRequiringRestartToYield.isEmpty()
                    || !newlyEnabledRequiringRestartToClaim.isEmpty();
        }
    }
}
