package codes.castled.allium.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Coordinates two-phase configuration reloads: every participant prepares and validates its
 * candidate against the staged root config before any live state is mutated. If any candidate
 * fails validation, nothing is published and every rejection is reported truthfully.
 */
public final class ReloadCoordinator {

    @FunctionalInterface
    public interface Prepared {
        /** Publishes an already-validated candidate and returns any restart-required notices. */
        List<ReloadReport.Entry> publish() throws Exception;

        static Prepared of(Runnable action) {
            Objects.requireNonNull(action, "action");
            return () -> {
                action.run();
                return List.of();
            };
        }
    }

    public interface Participant {
        String name();
        default int order() { return 100; }
        Prepared prepare(FileConfiguration candidateRoot) throws Exception;

        static Participant of(String name, CandidatePreparer preparer) {
            return of(name, 100, preparer);
        }

        static Participant of(String name, int order, CandidatePreparer preparer) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(preparer, "preparer");
            return new Participant() {
                @Override public String name() { return name; }
                @Override public int order() { return order; }
                @Override public Prepared prepare(FileConfiguration candidateRoot) throws Exception {
                    return preparer.prepare(candidateRoot);
                }
            };
        }
    }

    @FunctionalInterface
    public interface CandidatePreparer {
        Prepared prepare(FileConfiguration candidateRoot) throws Exception;
    }

    public interface RootConfigSource {
        /** Reads and validates candidate root config.yml without mutating live configuration. */
        FileConfiguration prepareRoot() throws Exception;

        /**
         * Publishes the validated root config.yml (called only after every participant prepares
         * cleanly) and returns any restart-only notices detected in root config.yml.
         */
        List<ReloadReport.Entry> publishRoot(FileConfiguration candidateRoot) throws Exception;
    }

    private ReloadCoordinator() {}

    /**
     * Executes a targeted reload for a single named participant (or {@code "config"} for root-only),
     * preserving the same two-phase validation, single root publication point, and truthful report
     * as a full reload.
     */
    public static ReloadReport executeTargeted(
            RootConfigSource rootSource,
            List<Participant> participants,
            String targetName,
            @Nullable Consumer<String> reply,
            @Nullable Consumer<String> warnLog
    ) {
        Objects.requireNonNull(targetName, "targetName");
        String normalized = targetName.trim().toLowerCase(java.util.Locale.ROOT);
        Consumer<String> out = reply != null ? reply : ignored -> {};
        if (normalized.equals("config")) {
            return execute(rootSource, List.of(), reply, warnLog);
        }
        List<Participant> matched = participants.stream()
                .filter(p -> p.name().equalsIgnoreCase(normalized))
                .toList();
        if (matched.isEmpty()) {
            String reason = "unknown reload target '" + targetName + "'";
            out.accept("Rejected " + targetName + ": " + reason);
            return new ReloadReport(false, List.of(), List.of(new ReloadReport.Entry(targetName, reason)), List.of());
        }
        return execute(rootSource, matched, reply, warnLog);
    }

    public static ReloadReport execute(
            RootConfigSource rootSource,
            List<Participant> participants,
            @Nullable Consumer<String> reply,
            @Nullable Consumer<String> warnLog
    ) {
        Objects.requireNonNull(rootSource, "rootSource");
        Objects.requireNonNull(participants, "participants");
        Consumer<String> out = reply != null ? reply : ignored -> {};
        Consumer<String> warn = warnLog != null ? warnLog : ignored -> {};

        FileConfiguration candidateRoot;
        try {
            candidateRoot = rootSource.prepareRoot();
        } catch (Exception failure) {
            String reason = describe(failure);
            warn.accept("Reload rejected (config.yml): " + reason);
            out.accept("Rejected config.yml: " + reason);
            out.accept("No configuration changes were applied; previous settings remain active.");
            return new ReloadReport(false, List.of(), List.of(new ReloadReport.Entry("config.yml", reason)), List.of());
        }

        // Order participants deterministically by declared reloadOrder (stable on registration order).
        List<Participant> ordered = new ArrayList<>(participants);
        ordered.sort(java.util.Comparator.comparingInt(Participant::order));

        // Phase 1: Prepare & validate every participant with zero live mutations.
        Map<String, Prepared> prepared = new LinkedHashMap<>();
        List<ReloadReport.Entry> rejected = new ArrayList<>();
        for (Participant participant : ordered) {
            try {
                Prepared candidate = participant.prepare(candidateRoot);
                if (candidate != null) {
                    prepared.put(participant.name(), candidate);
                }
            } catch (Exception failure) {
                String reason = describe(failure);
                rejected.add(new ReloadReport.Entry(participant.name(), reason));
                warn.accept("Reload candidate rejected (" + participant.name() + "): " + reason);
            }
        }

        if (!rejected.isEmpty()) {
            for (ReloadReport.Entry entry : rejected) {
                out.accept("Rejected " + entry.target() + ": " + entry.reason());
            }
            out.accept("No configuration changes were applied; previous settings remain active.");
            return new ReloadReport(false, List.of(), rejected, List.of());
        }

        // Phase 2: All candidates validated cleanly — publish root config and each participant.
        List<String> applied = new ArrayList<>();
        List<ReloadReport.Entry> restartRequired = new ArrayList<>();
        try {
            List<ReloadReport.Entry> rootNotices = rootSource.publishRoot(candidateRoot);
            applied.add("config");
            if (rootNotices != null) restartRequired.addAll(rootNotices);
        } catch (Exception failure) {
            String reason = describe(failure);
            warn.accept("Reload failed publishing config.yml: " + reason);
            out.accept("Rejected config.yml: " + reason);
            out.accept("No configuration changes were applied; previous settings remain active.");
            return new ReloadReport(false, List.of(), List.of(new ReloadReport.Entry("config.yml", reason)), List.of());
        }

        for (var entry : prepared.entrySet()) {
            String target = entry.getKey();
            try {
                List<ReloadReport.Entry> notices = entry.getValue().publish();
                applied.add(target);
                if (notices != null) restartRequired.addAll(notices);
            } catch (Exception failure) {
                String reason = describe(failure);
                rejected.add(new ReloadReport.Entry(target, reason));
                warn.accept("Reload failed publishing " + target + ": " + reason);
            }
        }

        if (!applied.isEmpty()) {
            out.accept("Applied: " + String.join(", ", applied) + ".");
        }
        for (ReloadReport.Entry entry : rejected) {
            out.accept("Rejected " + entry.target() + ": " + entry.reason());
        }
        for (ReloadReport.Entry entry : restartRequired) {
            out.accept("Requires restart (" + entry.target() + "): " + entry.reason());
        }

        return new ReloadReport(rejected.isEmpty(), applied, rejected, restartRequired);
    }

    private static String describe(Throwable failure) {
        String msg = failure.getMessage();
        if (msg == null || msg.isBlank()) return failure.getClass().getSimpleName();
        return msg.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse(failure.getClass().getSimpleName());
    }
}
