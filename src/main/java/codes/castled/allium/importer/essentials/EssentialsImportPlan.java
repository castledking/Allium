package codes.castled.allium.importer.essentials;

import codes.castled.allium.economy.Money;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.moderation.JailAssignment;
import codes.castled.allium.moderation.JailRecord;
import codes.castled.allium.moderation.MuteRecord;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.travel.TravelNames;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure dry-run planner that maps an {@link EssentialsSnapshot} against Allium's current database
 * state and {@link codes.castled.allium.importer.ImportLedger}. Performs zero writes.
 */
public record EssentialsImportPlan(
        String sourceSha256,
        String planSha256,
        List<Record> records,
        List<String> supportedSigns,
        List<String> unsupportedSigns,
        List<String> notes,
        int npcSkipped,
        int nonUuidFiles
) {
    public static final String SOURCE = "essentials";
    public static final UUID SERVER_SPAWN_OWNER = new UUID(0L, 0L);

    public enum Domain {
        BALANCE("balance"),
        HOME("home"),
        WARP("warp"),
        SPAWN("spawn"),
        LOGOUT("logout"),
        MAIL("mail"),
        NICKNAME("nickname"),
        IGNORE("ignore"),
        PREFERENCE("preference"),
        JAIL("jail"),
        MUTE("mute"),
        JAILED("jailed"),
        NAME("name");

        private final String id;
        Domain(String id) { this.id = id; }
        public String id() { return id; }
    }

    public enum Status {
        IMPORT,
        PRESENT,
        REPLAYED,
        CONFLICT,
        BLOCKED,
        SKIPPED
    }

    public record MailPayload(
            @Nullable UUID senderUuid,
            String senderName,
            String body,
            boolean unread,
            long createdAt,
            long expiresAt,
            String sourceRef
    ) {}

    public record MutePayload(
            String playerName,
            String reason,
            long createdAt,
            @Nullable Long expiresAt
    ) {}

    public record JailedPayload(
            String playerName,
            String jailName,
            SavedLocation returnLocation,
            long createdAt,
            @Nullable Long expiresAt
    ) {}

    public record Record(
            Domain domain,
            String key,
            Status status,
            String reason,
            String payloadHash,
            @Nullable UUID owner,
            @Nullable String name,
            @Nullable SavedLocation location,
            @Nullable Money money,
            @Nullable MailPayload mail,
            @Nullable String textValue,
            @Nullable UUID targetUuid,
            @Nullable PreferenceKey preferenceKey,
            @Nullable Boolean preferenceBool,
            @Nullable MutePayload mute,
            @Nullable JailedPayload jailed
    ) {
        static Record simple(Domain d, String key, Status st, String reason, String hash, @Nullable UUID owner) {
            return new Record(d, key, st, reason, hash, owner, null, null, null, null, null, null, null, null, null, null);
        }
    }

    @FunctionalInterface
    public interface Worlds {
        Optional<ResolvedWorld> resolve(@Nullable String worldUuidOrName, @Nullable String worldName);
    }

    public record ExistingAccount(long balanceMinor, long reservedMinor) {}

    public record Destination(
            Map<UUID, ExistingAccount> balances,
            Map<UUID, Map<String, SavedLocation>> homes,
            Map<String, SavedLocation> warps,
            Map<String, SavedLocation> spawns,
            Map<UUID, SavedLocation> logouts,
            Set<String> mailSourceRefs,
            Map<UUID, String> nicknames,
            Map<UUID, Set<UUID>> ignores,
            Map<UUID, StoredPreferences> preferences,
            Map<String, JailRecord> jails,
            Map<UUID, MuteRecord> mutes,
            Map<UUID, JailAssignment> jailed,
            Map<UUID, String> names,
            Map<String, String> ledgerHashes
    ) {
        public static Destination empty() {
            return new Destination(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    public static EssentialsImportPlan build(
            EssentialsSnapshot snapshot,
            Worlds worlds,
            Destination dest,
            long nowMillis,
            Money minBalance,
            Money maxBalance
    ) {
        List<Record> records = new ArrayList<>();
        List<String> notes = new ArrayList<>(snapshot.notes());
        int npcSkipped = 0;

        for (EssentialsSnapshot.SkippedFile skipped : snapshot.skippedUserFiles()) {
            notes.add("Skipped userdata/" + skipped.fileName() + ": " + skipped.reason());
        }

        // 1. Warps
        Map<String, String> seenWarps = new HashMap<>();
        for (EssentialsSnapshot.WarpFile wf : snapshot.warps()) {
            if (wf.error() != null || wf.location() == null) {
                records.add(Record.simple(Domain.WARP, wf.name(), Status.BLOCKED,
                        wf.error() == null ? "invalid warp" : wf.error(), hash("warp:" + wf.fileName()), null));
                continue;
            }
            String norm;
            try {
                norm = TravelNames.normalize(wf.name());
            } catch (IllegalArgumentException bad) {
                records.add(Record.simple(Domain.WARP, wf.name(), Status.BLOCKED, bad.getMessage(), hash("warp:" + wf.name()), null));
                continue;
            }
            if (seenWarps.putIfAbsent(norm, wf.name()) != null) {
                records.add(Record.simple(Domain.WARP, norm, Status.BLOCKED,
                        "normalized warp name collides with " + seenWarps.get(norm), hash("warp:" + wf.name()), null));
                continue;
            }
            Optional<ResolvedWorld> world = worlds.resolve(wf.location().world(), wf.location().worldName());
            if (world.isEmpty()) {
                records.add(Record.simple(Domain.WARP, norm, Status.BLOCKED,
                        "unresolved world: " + worldLabel(wf.location()), hash("warp:" + norm), null));
                continue;
            }
            SavedLocation loc = toSaved(world.get(), wf.location(), nowMillis);
            String ph = hashLocation("warp", norm, loc);
            Status st = ledgerOrDestLocation(dest.ledgerHashes(), Domain.WARP, norm, ph, dest.warps().get(norm), loc);
            String reason = switch (st) {
                case IMPORT -> "new warp";
                case PRESENT -> "matches existing warp";
                case REPLAYED -> "already imported";
                case CONFLICT -> "different warp already exists";
                default -> "";
            };
            records.add(new Record(Domain.WARP, norm, st, reason, ph, null, norm, loc,
                    null, null, null, null, null, null, null, null));
        }

        // 2. Jails (imported before jailed players so jail references can be checked)
        Set<String> availableJails = new HashSet<>();
        for (String existing : dest.jails().keySet()) availableJails.add(existing.toLowerCase(Locale.ROOT));
        for (EssentialsSnapshot.JailEntry je : snapshot.jails()) {
            String norm = je.name() == null ? "" : je.name().trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty() || !norm.matches("[a-z0-9_-]{1,32}")) {
                records.add(Record.simple(Domain.JAIL, String.valueOf(je.name()), Status.BLOCKED,
                        "invalid jail name", hash("jail:" + je.name()), null));
                continue;
            }
            if (je.error() != null || je.location() == null) {
                records.add(Record.simple(Domain.JAIL, norm, Status.BLOCKED,
                        je.error() == null ? "invalid jail location" : je.error(), hash("jail:" + norm), null));
                continue;
            }
            Optional<ResolvedWorld> world = worlds.resolve(je.location().world(), je.location().worldName());
            if (world.isEmpty()) {
                records.add(Record.simple(Domain.JAIL, norm, Status.BLOCKED,
                        "unresolved world: " + worldLabel(je.location()), hash("jail:" + norm), null));
                continue;
            }
            SavedLocation loc = toSaved(world.get(), je.location(), nowMillis);
            String ph = hashLocation("jail", norm, loc);
            JailRecord existing = dest.jails().get(norm);
            Status st = ledgerOrDestLocation(dest.ledgerHashes(), Domain.JAIL, norm, ph,
                    existing == null ? null : existing.location(), loc);
            if (st == Status.IMPORT || st == Status.PRESENT || st == Status.REPLAYED) {
                availableJails.add(norm);
            }
            records.add(new Record(Domain.JAIL, norm, st, st == Status.CONFLICT ? "different jail already exists" : st.name().toLowerCase(Locale.ROOT),
                    ph, null, norm, loc, null, null, null, null, null, null, null, null));
        }

        // 2b. Spawns (spawn.yml)
        for (EssentialsSnapshot.SpawnEntry se : snapshot.spawns()) {
            String group = se.group() == null ? "" : se.group().trim().toLowerCase(Locale.ROOT);
            if (group.isEmpty() || !group.matches("[a-z0-9_-]{1,32}")) {
                records.add(Record.simple(Domain.SPAWN, String.valueOf(se.group()), Status.BLOCKED,
                        "invalid spawn group name", hash("spawn:" + se.group()), SERVER_SPAWN_OWNER));
                continue;
            }
            if (se.error() != null || se.location() == null) {
                records.add(Record.simple(Domain.SPAWN, group, Status.BLOCKED,
                        se.error() == null ? "invalid spawn location" : se.error(), hash("spawn:" + group), SERVER_SPAWN_OWNER));
                continue;
            }
            Optional<ResolvedWorld> world = worlds.resolve(se.location().world(), se.location().worldName());
            if (world.isEmpty()) {
                records.add(Record.simple(Domain.SPAWN, group, Status.BLOCKED,
                        "unresolved world: " + worldLabel(se.location()), hash("spawn:" + group), SERVER_SPAWN_OWNER));
                continue;
            }
            SavedLocation loc = toSaved(world.get(), se.location(), nowMillis);
            String slot = group.equals("default") ? "spawn" : "spawn:" + group;
            String ph = hashLocation("spawn", slot, loc);
            Status st = ledgerOrDestLocation(dest.ledgerHashes(), Domain.SPAWN, slot, ph, dest.spawns().get(slot), loc);
            records.add(new Record(Domain.SPAWN, slot, st,
                    st == Status.CONFLICT ? "different spawn already exists" : st.name().toLowerCase(Locale.ROOT),
                    ph, SERVER_SPAWN_OWNER, slot, loc, null, null, null, null, null, null, null, null));
        }

        List<String> supportedSigns = new ArrayList<>();
        List<String> unsupportedSigns = new ArrayList<>();
        Set<String> seenSigns = new HashSet<>();
        for (String rawSign : snapshot.enabledSigns()) {
            try {
                String canonical = codes.castled.allium.sign.SignIdentity.Type.parse(rawSign).key();
                if (seenSigns.add(canonical)) supportedSigns.add(canonical);
            } catch (IllegalArgumentException unsupported) {
                if (seenSigns.add("!" + rawSign)) {
                    unsupportedSigns.add(rawSign);
                    notes.add("Essentials enabledSign '" + rawSign + "' is not an Allium sign type (skipped)");
                }
            }
        }

        // 3. Per-user domains
        for (EssentialsSnapshot.UserFile user : snapshot.users()) {
            if (user.npc()) {
                npcSkipped++;
                continue;
            }
            for (String err : user.parseErrors()) {
                notes.add("userdata/" + user.fileName() + ": " + err);
            }
            UUID uuid = user.uuid();
            String displayName = user.lastAccountName() != null ? user.lastAccountName() : uuid.toString();

            // 3a. Player account name (never nickname; accepts Floodgate '.' prefix up to 16 chars)
            if (codes.castled.allium.naming.NameCache.isValidName(user.lastAccountName())) {
                String key = uuid.toString();
                String ph = hash("name:" + uuid + ":" + user.lastAccountName());
                String existingName = dest.names().get(uuid);
                Status st = checkLedger(dest.ledgerHashes(), Domain.NAME, key, ph);
                String reason = "replayed";
                if (st == null) {
                    if (existingName == null) {
                        st = Status.IMPORT;
                        reason = "new account name";
                    } else if (existingName.equalsIgnoreCase(user.lastAccountName())) {
                        st = Status.PRESENT;
                        reason = "already recorded";
                    } else {
                        st = Status.SKIPPED;
                        reason = "newer/different name already recorded (" + existingName + ")";
                    }
                }
                records.add(new Record(Domain.NAME, key, st, reason, ph, uuid, user.lastAccountName(),
                        null, null, null, user.lastAccountName(), null, null, null, null, null));
            }

            // 3b. Balance
            if (!user.moneyPresent()) {
                records.add(Record.simple(Domain.BALANCE, uuid.toString(), Status.SKIPPED,
                        "missing money field (not treated as zero)", hash("money:missing:" + uuid), uuid));
            } else {
                planBalance(user, dest, minBalance, maxBalance, records);
            }

            // 3c. Homes
            planHomes(user, worlds, dest, nowMillis, records);

            // 3d. Logout location
            if (user.logoutLocation() != null) {
                Optional<ResolvedWorld> world = worlds.resolve(user.logoutLocation().world(), user.logoutLocation().worldName());
                if (world.isEmpty()) {
                    records.add(Record.simple(Domain.LOGOUT, uuid.toString(), Status.BLOCKED,
                            "unresolved world: " + worldLabel(user.logoutLocation()), hash("logout:" + uuid), uuid));
                } else {
                    long ts = user.logoutTimestamp() > 0 ? user.logoutTimestamp() : nowMillis;
                    SavedLocation loc = toSaved(world.get(), user.logoutLocation(), ts);
                    String ph = hashLocation("logout", uuid.toString(), loc);
                    SavedLocation existing = dest.logouts().get(uuid);
                    Status st = checkLedger(dest.ledgerHashes(), Domain.LOGOUT, uuid.toString(), ph);
                    String reason = "already imported";
                    if (st == null) {
                        if (existing == null) {
                            st = Status.IMPORT;
                            reason = "new logout position";
                        } else if (sameCoords(existing, loc)) {
                            st = Status.PRESENT;
                            reason = "matches existing logout position";
                        } else {
                            st = Status.SKIPPED;
                            reason = "server already has a logout position";
                        }
                    }
                    records.add(new Record(Domain.LOGOUT, uuid.toString(), st, reason, ph, uuid, "logout", loc,
                            null, null, null, null, null, null, null, null));
                }
            }

            // 3e. Mail
            planMail(user, dest, nowMillis, records);

            // 3f. Nickname
            if (user.nickname() != null) {
                String key = uuid.toString();
                String ph = hash("nick:" + uuid + ":" + user.nickname());
                String existing = dest.nicknames().get(uuid);
                Status st = checkLedger(dest.ledgerHashes(), Domain.NICKNAME, key, ph);
                String reason = "already imported";
                if (st == null) {
                    if (existing == null || existing.isBlank()) {
                        st = Status.IMPORT;
                        reason = "new nickname";
                    } else if (existing.equals(user.nickname())) {
                        st = Status.PRESENT;
                        reason = "matches existing nickname";
                    } else {
                        st = Status.CONFLICT;
                        reason = "different nickname already set";
                    }
                }
                records.add(new Record(Domain.NICKNAME, key, st, reason, ph, uuid, null, null, null, null,
                        user.nickname(), null, null, null, null, null));
            }

            // 3g. Ignores
            planIgnores(user, dest, records);

            // 3h. Preferences
            planPreferences(user, dest, records);

            // 3i. Mute
            if (user.muted()) {
                planMute(user, displayName, dest, nowMillis, records);
            }

            // 3j. Jailed
            if (user.jailed()) {
                planJailed(user, displayName, worlds, dest, availableJails, nowMillis, records);
            }
        }

        records.sort(Comparator.comparing((Record r) -> r.domain().ordinal()).thenComparing(Record::key));
        String planSha = hashPlan(snapshot.sourceSha256(), records);
        return new EssentialsImportPlan(snapshot.sourceSha256(), planSha, List.copyOf(records),
                List.copyOf(supportedSigns), List.copyOf(unsupportedSigns), List.copyOf(notes),
                npcSkipped, snapshot.skippedUserFiles().size());
    }

    private static void planBalance(EssentialsSnapshot.UserFile user, Destination dest,
                                    Money minBalance, Money maxBalance, List<Record> records) {
        UUID uuid = user.uuid();
        String key = uuid.toString();
        Money parsed;
        try {
            parsed = Money.parse(user.moneyRaw(), true);
        } catch (IllegalArgumentException | ArithmeticException bad) {
            records.add(Record.simple(Domain.BALANCE, key, Status.BLOCKED,
                    "malformed money value: " + user.moneyRaw(), hash("money:bad:" + uuid + ":" + user.moneyRaw()), uuid));
            return;
        }
        if (parsed.compareTo(minBalance) < 0 || parsed.compareTo(maxBalance) > 0) {
            records.add(Record.simple(Domain.BALANCE, key, Status.BLOCKED,
                    "balance " + user.moneyRaw() + " outside configured bounds", hash("money:bounds:" + uuid + ":" + parsed.minor()), uuid));
            return;
        }
        String ph = hash("balance:" + uuid + ":" + parsed.minor());
        Status st = checkLedger(dest.ledgerHashes(), Domain.BALANCE, key, ph);
        String reason = "already imported";
        if (st == null) {
            ExistingAccount existing = dest.balances().get(uuid);
            if (existing == null) {
                st = Status.IMPORT;
                reason = "new balance";
            } else if (existing.reservedMinor() > 0) {
                st = Status.CONFLICT;
                reason = "account has active reservation holds";
            } else if (existing.balanceMinor() == parsed.minor()) {
                st = Status.PRESENT;
                reason = "matches existing balance";
            } else {
                st = Status.CONFLICT;
                reason = "account already has different balance";
            }
        }
        records.add(new Record(Domain.BALANCE, key, st, reason, ph, uuid, null, null, parsed,
                null, null, null, null, null, null, null));
    }

    private static void planHomes(EssentialsSnapshot.UserFile user, Worlds worlds, Destination dest,
                                  long nowMillis, List<Record> records) {
        UUID uuid = user.uuid();
        Map<String, SavedLocation> existingHomes = dest.homes().getOrDefault(uuid, Map.of());
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<String, EssentialsSnapshot.RawLocation> entry : user.homes().entrySet()) {
            String rawName = entry.getKey();
            String norm;
            try {
                norm = TravelNames.normalize(rawName);
            } catch (IllegalArgumentException bad) {
                records.add(Record.simple(Domain.HOME, uuid + ":" + rawName, Status.BLOCKED,
                        bad.getMessage(), hash("home:" + uuid + ":" + rawName), uuid));
                continue;
            }
            String key = uuid + ":" + norm;
            if (seen.putIfAbsent(norm, rawName) != null) {
                records.add(Record.simple(Domain.HOME, key, Status.BLOCKED,
                        "normalized home name collides with " + seen.get(norm), hash("home:dup:" + key), uuid));
                continue;
            }
            Optional<ResolvedWorld> world = worlds.resolve(entry.getValue().world(), entry.getValue().worldName());
            if (world.isEmpty()) {
                records.add(Record.simple(Domain.HOME, key, Status.BLOCKED,
                        "unresolved world: " + worldLabel(entry.getValue()), hash("home:world:" + key), uuid));
                continue;
            }
            SavedLocation loc = toSaved(world.get(), entry.getValue(), nowMillis);
            String ph = hashLocation("home", key, loc);
            Status st = ledgerOrDestLocation(dest.ledgerHashes(), Domain.HOME, key, ph, existingHomes.get(norm), loc);
            String reason = switch (st) {
                case IMPORT -> "new home";
                case PRESENT -> "matches existing home";
                case REPLAYED -> "already imported";
                case CONFLICT -> "different home already exists";
                default -> "";
            };
            records.add(new Record(Domain.HOME, key, st, reason, ph, uuid, norm, loc,
                    null, null, null, null, null, null, null, null));
        }
    }

    private static void planMail(EssentialsSnapshot.UserFile user, Destination dest,
                                 long nowMillis, List<Record> records) {
        UUID uuid = user.uuid();
        for (EssentialsSnapshot.RawMail raw : user.mail()) {
            if (raw.expire() > 0 && raw.expire() <= nowMillis) {
                records.add(Record.simple(Domain.MAIL, uuid + ":" + raw.index(), Status.SKIPPED,
                        "expired mail", hash("mail:expired:" + uuid + ":" + raw.index()), uuid));
                continue;
            }
            UUID senderUuid = null;
            if (raw.senderUuid() != null) {
                try {
                    senderUuid = UUID.fromString(raw.senderUuid());
                } catch (IllegalArgumentException ignored) {
                    // Never invent or fail over an unreadable sender UUID; keep null provenance.
                }
            }
            String senderName = raw.senderName() != null ? raw.senderName() : (raw.legacy() ? "Legacy" : "Unknown");
            if (senderName.length() > 32) senderName = senderName.substring(0, 32);
            long createdAt = raw.timestamp() > 0 ? raw.timestamp() : nowMillis;
            long expiresAt = Math.max(0L, raw.expire());
            String ph = hash("mail:" + uuid + ":" + raw.index() + ":" + senderUuid + ":" + senderName
                    + ":" + raw.read() + ":" + createdAt + ":" + expiresAt + ":" + raw.message());
            String sourceRef = uuid + ":" + raw.index() + ":" + ph.substring(0, 16);
            String key = sourceRef;
            Status st = checkLedger(dest.ledgerHashes(), Domain.MAIL, key, ph);
            String reason = "already imported";
            if (st == null) {
                if (dest.mailSourceRefs().contains(sourceRef)) {
                    st = Status.PRESENT;
                    reason = "mail source_ref already present";
                } else {
                    st = Status.IMPORT;
                    reason = raw.legacy() ? "legacy text mail" : "structured text mail";
                }
            }
            var payload = new MailPayload(senderUuid, senderName, raw.message(), !raw.read(), createdAt, expiresAt, sourceRef);
            records.add(new Record(Domain.MAIL, key, st, reason, ph, uuid, null, null, null,
                    payload, null, null, null, null, null, null));
        }
    }

    private static void planIgnores(EssentialsSnapshot.UserFile user, Destination dest, List<Record> records) {
        UUID owner = user.uuid();
        Set<UUID> existing = dest.ignores().getOrDefault(owner, Set.of());
        Set<UUID> seen = new HashSet<>();
        for (String raw : user.ignores()) {
            UUID target;
            try {
                target = UUID.fromString(raw);
            } catch (IllegalArgumentException bad) {
                records.add(Record.simple(Domain.IGNORE, owner + ":" + raw, Status.SKIPPED,
                        "invalid ignored UUID", hash("ignore:bad:" + owner + ":" + raw), owner));
                continue;
            }
            if (target.equals(owner)) {
                records.add(Record.simple(Domain.IGNORE, owner + ":" + target, Status.SKIPPED,
                        "self-ignore skipped", hash("ignore:self:" + owner), owner));
                continue;
            }
            if (!seen.add(target)) continue;
            String key = owner + ":" + target;
            String ph = hash("ignore:" + key);
            Status st = checkLedger(dest.ledgerHashes(), Domain.IGNORE, key, ph);
            String reason = "already imported";
            if (st == null) {
                st = existing.contains(target) ? Status.PRESENT : Status.IMPORT;
                reason = st == Status.PRESENT ? "already ignored" : "new ignore entry";
            }
            records.add(new Record(Domain.IGNORE, key, st, reason, ph, owner, null, null, null,
                    null, null, target, null, null, null, null));
        }
    }

    private static void planPreferences(EssentialsSnapshot.UserFile user, Destination dest, List<Record> records) {
        UUID owner = user.uuid();
        StoredPreferences existing = dest.preferences().get(owner);
        Map<PreferenceKey, Boolean> mapped = new LinkedHashMap<>();
        if (user.acceptingPay() != null) mapped.put(PreferenceKey.ACCEPT_PAYMENTS, user.acceptingPay());
        if (user.teleportEnabled() != null) mapped.put(PreferenceKey.ACCEPT_TELEPORT_REQUESTS, user.teleportEnabled());
        if (user.teleportAuto() != null) mapped.put(PreferenceKey.TP_AUTO, user.teleportAuto());
        if (user.confirmPay() != null) mapped.put(PreferenceKey.PAY_CONFIRM, user.confirmPay());
        if (user.socialSpy() != null) mapped.put(PreferenceKey.SOCIAL_SPY, user.socialSpy());
        if (user.msgToggle() != null) mapped.put(PreferenceKey.ACCEPT_MESSAGES, !user.msgToggle());

        for (Map.Entry<PreferenceKey, Boolean> entry : mapped.entrySet()) {
            PreferenceKey prefKey = entry.getKey();
            Boolean value = entry.getValue();
            String key = owner + ":" + prefKey.configName();
            String ph = hash("pref:" + key + ":" + value);
            Status st = checkLedger(dest.ledgerHashes(), Domain.PREFERENCE, key, ph);
            String reason = "already imported";
            if (st == null) {
                Boolean current = existing == null ? null : currentBool(existing, prefKey);
                if (current == null) {
                    st = Status.IMPORT;
                    reason = "new preference override";
                } else if (current.equals(value)) {
                    st = Status.PRESENT;
                    reason = "matches existing preference";
                } else {
                    st = Status.CONFLICT;
                    reason = "different preference override already set (" + current + ")";
                }
            }
            records.add(new Record(Domain.PREFERENCE, key, st, reason, ph, owner, prefKey.configName(),
                    null, null, null, null, null, prefKey, value, null, null));
        }
    }

    private static void planMute(EssentialsSnapshot.UserFile user, String displayName, Destination dest,
                                 long nowMillis, List<Record> records) {
        UUID owner = user.uuid();
        String key = owner.toString();
        Long expiresAt = user.muteTimestamp() > 0 ? user.muteTimestamp() : null;
        if (expiresAt != null && expiresAt <= nowMillis) {
            records.add(Record.simple(Domain.MUTE, key, Status.SKIPPED,
                    "expired mute", hash("mute:expired:" + owner + ":" + expiresAt), owner));
            return;
        }
        String reasonText = MuteRecord.normalizeReason(user.muteReason());
        String ph = hash("mute:" + owner + ":" + expiresAt + ":" + reasonText);
        Status st = checkLedger(dest.ledgerHashes(), Domain.MUTE, key, ph);
        String reason = "already imported";
        if (st == null) {
            MuteRecord existing = dest.mutes().get(owner);
            if (existing == null || existing.isExpired(nowMillis)) {
                st = Status.IMPORT;
                reason = expiresAt == null ? "permanent mute" : "timed mute";
            } else if (Objects.equals(existing.expiresAt(), expiresAt) && existing.reason().equals(reasonText)) {
                st = Status.PRESENT;
                reason = "matches existing mute";
            } else {
                st = Status.CONFLICT;
                reason = "different active mute already exists";
            }
        }
        records.add(new Record(Domain.MUTE, key, st, reason, ph, owner, displayName, null, null,
                null, null, null, null, null, new MutePayload(displayName, reasonText, nowMillis, expiresAt), null));
    }

    private static void planJailed(EssentialsSnapshot.UserFile user, String displayName, Worlds worlds,
                                   Destination dest, Set<String> availableJails, long nowMillis, List<Record> records) {
        UUID owner = user.uuid();
        String key = owner.toString();
        if (user.jailName() == null || user.jailName().isBlank()) {
            records.add(Record.simple(Domain.JAILED, key, Status.BLOCKED,
                    "jailed flag set without jail name", hash("jailed:noname:" + owner), owner));
            return;
        }
        String normJail = user.jailName().trim().toLowerCase(Locale.ROOT);
        if (!availableJails.contains(normJail)) {
            records.add(Record.simple(Domain.JAILED, key, Status.BLOCKED,
                    "unresolved jail reference: " + normJail, hash("jailed:nojail:" + owner + ":" + normJail), owner));
            return;
        }
        if (user.onlineJailMillis() > 0 && user.jailTimestamp() <= 0) {
            records.add(Record.simple(Domain.JAILED, key, Status.BLOCKED,
                    "online-only jail timer cannot convert losslessly to wall-clock expiry", hash("jailed:online:" + owner), owner));
            return;
        }
        Long expiresAt = user.jailTimestamp() > 0 ? user.jailTimestamp() : null;
        if (expiresAt != null && expiresAt <= nowMillis) {
            records.add(Record.simple(Domain.JAILED, key, Status.SKIPPED,
                    "expired jail assignment", hash("jailed:expired:" + owner + ":" + expiresAt), owner));
            return;
        }
        EssentialsSnapshot.RawLocation rawReturn = user.logoutLocation() != null ? user.logoutLocation() : user.lastLocation();
        if (rawReturn == null) {
            records.add(Record.simple(Domain.JAILED, key, Status.BLOCKED,
                    "no valid return location (missing logoutlocation/lastlocation)", hash("jailed:noreturn:" + owner), owner));
            return;
        }
        Optional<ResolvedWorld> world = worlds.resolve(rawReturn.world(), rawReturn.worldName());
        if (world.isEmpty()) {
            records.add(Record.simple(Domain.JAILED, key, Status.BLOCKED,
                    "unresolved return location world: " + worldLabel(rawReturn), hash("jailed:world:" + owner), owner));
            return;
        }
        SavedLocation returnLoc = toSaved(world.get(), rawReturn, nowMillis);
        String ph = hash("jailed:" + owner + ":" + normJail + ":" + expiresAt + ":" + locFingerprint(returnLoc));
        Status st = checkLedger(dest.ledgerHashes(), Domain.JAILED, key, ph);
        String reason = "already imported";
        if (st == null) {
            JailAssignment existing = dest.jailed().get(owner);
            if (existing == null || existing.isExpired(nowMillis)) {
                st = Status.IMPORT;
                reason = "active jail assignment";
            } else if (existing.jailName().equalsIgnoreCase(normJail) && Objects.equals(existing.expiresAt(), expiresAt)) {
                st = Status.PRESENT;
                reason = "matches existing jail assignment";
            } else {
                st = Status.CONFLICT;
                reason = "different active jail assignment exists";
            }
        }
        records.add(new Record(Domain.JAILED, key, st, reason, ph, owner, normJail, returnLoc, null,
                null, null, null, null, null, null, new JailedPayload(displayName, normJail, returnLoc, nowMillis, expiresAt)));
    }

    @Nullable
    private static Boolean currentBool(StoredPreferences prefs, PreferenceKey key) {
        return switch (key) {
            case ACCEPT_MESSAGES -> prefs.acceptMessages();
            case ACCEPT_PAYMENTS -> prefs.acceptPayments();
            case ACCEPT_TELEPORT_REQUESTS -> prefs.acceptTeleportRequests();
            case SOCIAL_SPY -> prefs.socialSpy();
            case PAY_CONFIRM -> prefs.payConfirm();
            case TP_AUTO -> prefs.tpAuto();
            default -> null;
        };
    }

    @Nullable
    private static Status checkLedger(Map<String, String> ledgerHashes, Domain domain, String key, String payloadHash) {
        String existing = ledgerHashes.get(domain.id() + ":" + key);
        if (existing == null) return null;
        return existing.equals(payloadHash) ? Status.REPLAYED : Status.CONFLICT;
    }

    private static Status ledgerOrDestLocation(Map<String, String> ledgerHashes, Domain domain, String key,
                                               String payloadHash, @Nullable SavedLocation existing, SavedLocation target) {
        Status ledger = checkLedger(ledgerHashes, domain, key, payloadHash);
        if (ledger != null) return ledger;
        if (existing == null) return Status.IMPORT;
        return sameCoords(existing, target) ? Status.PRESENT : Status.CONFLICT;
    }

    public static boolean sameCoords(SavedLocation a, SavedLocation b) {
        return a.worldId().equals(b.worldId())
                && Double.compare(a.x(), b.x()) == 0
                && Double.compare(a.y(), b.y()) == 0
                && Double.compare(a.z(), b.z()) == 0
                && Float.compare(a.yaw(), b.yaw()) == 0
                && Float.compare(a.pitch(), b.pitch()) == 0;
    }

    public Map<Status, Long> counts(Domain domain) {
        Map<Status, Long> out = new EnumMap<>(Status.class);
        for (Status s : Status.values()) out.put(s, 0L);
        for (Record r : records) {
            if (r.domain() == domain) out.merge(r.status(), 1L, Long::sum);
        }
        return out;
    }

    public long count(Status status) {
        return records.stream().filter(r -> r.status() == status).count();
    }

    private static SavedLocation toSaved(ResolvedWorld world, EssentialsSnapshot.RawLocation raw, long updatedAt) {
        return new SavedLocation(world.id(), world.name(), raw.x(), raw.y(), raw.z(), raw.yaw(), raw.pitch());
    }

    private static String worldLabel(EssentialsSnapshot.RawLocation raw) {
        if (raw.worldName() != null && raw.world() != null) return raw.worldName() + " (" + raw.world() + ")";
        return raw.worldName() != null ? raw.worldName() : String.valueOf(raw.world());
    }

    private static String hashLocation(String domain, String key, SavedLocation loc) {
        return hash(domain + ":" + key + ":" + locFingerprint(loc));
    }

    private static String locFingerprint(SavedLocation loc) {
        return loc.worldId() + ":" + Double.doubleToLongBits(loc.x()) + ":" + Double.doubleToLongBits(loc.y())
                + ":" + Double.doubleToLongBits(loc.z()) + ":" + Float.floatToIntBits(loc.yaw())
                + ":" + Float.floatToIntBits(loc.pitch());
    }

    private static String hashPlan(String sourceSha, List<Record> records) {
        StringBuilder sb = new StringBuilder(sourceSha);
        for (Record r : records) {
            sb.append('\n').append(r.domain().id()).append('|').append(r.key()).append('|')
                    .append(r.status().name()).append('|').append(r.payloadHash());
        }
        return hash(sb.toString());
    }

    static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
