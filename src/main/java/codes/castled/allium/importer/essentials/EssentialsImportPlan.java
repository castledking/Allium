package codes.castled.allium.importer.essentials;

import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.item.spec.ItemSpecException;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.kit.KitDefinition;
import codes.castled.allium.kit.KitNames;
import codes.castled.allium.kit.KitUsageRecord;
import codes.castled.allium.moderation.JailAssignment;
import codes.castled.allium.moderation.JailRecord;
import codes.castled.allium.moderation.MuteRecord;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.travel.TravelNames;
import codes.castled.allium.worth.WorthSettings;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
        BACK("back"),
        MAIL("mail"),
        NICKNAME("nickname"),
        IGNORE("ignore"),
        PREFERENCE("preference"),
        JAIL("jail"),
        MUTE("mute"),
        JAILED("jailed"),
        NAME("name"),
        KIT("kit"),
        KIT_USAGE("kit_usage"),
        WORTH("worth");

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

    public record KitPayload(
            String name,
            long cooldownSeconds,
            boolean oneTime,
            List<String> items,
            List<String> reviewCommands
    ) {}

    public record KitUsagePayload(
            String kitName,
            long lastClaimedAt,
            boolean oneTimeClaimed
    ) {}

    public record WorthPayload(
            String materialKey,
            BigDecimal price
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
            @Nullable JailedPayload jailed,
            @Nullable KitPayload kit,
            @Nullable KitUsagePayload kitUsage,
            @Nullable WorthPayload worth
    ) {
        public Record(
                Domain domain, String key, Status status, String reason, String payloadHash,
                @Nullable UUID owner, @Nullable String name, @Nullable SavedLocation location,
                @Nullable Money money, @Nullable MailPayload mail, @Nullable String textValue,
                @Nullable UUID targetUuid, @Nullable PreferenceKey preferenceKey,
                @Nullable Boolean preferenceBool, @Nullable MutePayload mute, @Nullable JailedPayload jailed
        ) {
            this(domain, key, status, reason, payloadHash, owner, name, location, money, mail, textValue,
                    targetUuid, preferenceKey, preferenceBool, mute, jailed, null, null, null);
        }

        static Record simple(Domain d, String key, Status st, String reason, String hash, @Nullable UUID owner) {
            return new Record(d, key, st, reason, hash, owner, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
            Map<UUID, SavedLocation> backs,
            Set<String> mailSourceRefs,
            Map<UUID, String> nicknames,
            Map<UUID, Set<UUID>> ignores,
            Map<UUID, StoredPreferences> preferences,
            Map<String, JailRecord> jails,
            Map<UUID, MuteRecord> mutes,
            Map<UUID, JailAssignment> jailed,
            Map<UUID, String> names,
            Map<String, KitDefinition> kits,
            Map<String, KitUsageRecord> kitUsages,
            Map<String, BigDecimal> worthPrices,
            Map<String, String> ledgerHashes
    ) {
        public Destination(
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
            this(balances, homes, warps, spawns, logouts, Map.of(), mailSourceRefs, nicknames, ignores, preferences,
                    jails, mutes, jailed, names, Map.of(), Map.of(), Map.of(), ledgerHashes);
        }

        public static Destination empty() {
            return new Destination(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    public static EssentialsImportPlan build(
            EssentialsSnapshot snapshot,
            Worlds worlds,
            Destination dest,
            long nowMillis,
            @Nullable EconomySettings balanceSettings
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

        // 2c. Kit definitions (kits.yml) — planned before per-user kit usage so one-time status stays aligned.
        Map<String, Boolean> kitOneTimeFlags = new HashMap<>();
        for (var existingKit : dest.kits().entrySet()) {
            kitOneTimeFlags.put(existingKit.getKey().toLowerCase(Locale.ROOT), existingKit.getValue().oneTime());
        }
        planKits(snapshot.kits(), dest, kitOneTimeFlags, notes, records);

        // 2d. Worth prices (worth.yml)
        planWorth(snapshot.worth(), dest, notes, records);

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
                planBalance(user, dest, balanceSettings, records);
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

            // 3d-2. Last /back location (lastlocation)
            if (user.lastLocation() != null) {
                Optional<ResolvedWorld> world = worlds.resolve(user.lastLocation().world(), user.lastLocation().worldName());
                if (world.isEmpty()) {
                    records.add(Record.simple(Domain.BACK, uuid.toString(), Status.BLOCKED,
                            "unresolved world: " + worldLabel(user.lastLocation()), hash("back:" + uuid), uuid));
                } else {
                    SavedLocation loc = toSaved(world.get(), user.lastLocation(), nowMillis);
                    String ph = hashLocation("back", uuid.toString(), loc);
                    SavedLocation existing = dest.backs().get(uuid);
                    Status st = checkLedger(dest.ledgerHashes(), Domain.BACK, uuid.toString(), ph);
                    String reason = "already imported";
                    if (st == null) {
                        if (existing == null) {
                            st = Status.IMPORT;
                            reason = "new /back position";
                        } else if (sameCoords(existing, loc)) {
                            st = Status.PRESENT;
                            reason = "matches existing /back position";
                        } else {
                            st = Status.SKIPPED;
                            reason = "server already has a /back position";
                        }
                    }
                    records.add(new Record(Domain.BACK, uuid.toString(), st, reason, ph, uuid, "back", loc,
                            null, null, null, null, null, null, null, null));
                }
            }

            // 3d-3. Per-player kit usage timestamps
            planKitUsages(user, dest, kitOneTimeFlags, records);

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

    private static void planKits(
            List<EssentialsSnapshot.RawKit> rawKits,
            Destination dest,
            Map<String, Boolean> kitOneTimeFlags,
            List<String> notes,
            List<Record> records
    ) {
        if (rawKits.isEmpty()) return;
        ItemSpecParser specParser = new ItemSpecParser(true);
        Map<String, String> seen = new HashMap<>();
        for (EssentialsSnapshot.RawKit raw : rawKits) {
            if (raw.error() != null) {
                records.add(Record.simple(Domain.KIT, String.valueOf(raw.name()), Status.BLOCKED,
                        raw.error(), hash("kit:bad:" + raw.name()), null));
                continue;
            }
            String norm = raw.name() == null ? "" : raw.name().trim().toLowerCase(Locale.ROOT);
            if (!KitNames.isValid(norm)) {
                records.add(Record.simple(Domain.KIT, String.valueOf(raw.name()), Status.BLOCKED,
                        "invalid kit name (must match [a-z0-9_-]{1,32})", hash("kit:name:" + raw.name()), null));
                continue;
            }
            if (seen.putIfAbsent(norm, raw.name()) != null) {
                records.add(Record.simple(Domain.KIT, norm, Status.BLOCKED,
                        "normalized kit name collides with " + seen.get(norm), hash("kit:dup:" + raw.name()), null));
                continue;
            }
            boolean oneTime = raw.delaySeconds() < 0L;
            long cooldownSeconds = oneTime ? 0L : Math.min(raw.delaySeconds(), KitDefinition.MAX_COOLDOWN_SECONDS);
            List<String> translatedItems = new ArrayList<>();
            List<String> reviewCommands = new ArrayList<>();
            List<String> invalidItems = new ArrayList<>();
            for (String line : raw.rawItems()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("/") || trimmed.startsWith("$") || trimmed.startsWith("@")) {
                    reviewCommands.add(trimmed);
                    notes.add("Essentials kit '" + norm + "' contains embedded command/action '" + trimmed
                            + "' (not executed or imported; review manually)");
                    continue;
                }
                try {
                    var parsed = specParser.parse(trimmed);
                    if (parsed.overflow().isPresent()) {
                        invalidItems.add("'" + trimmed + "': --overflow flag is not allowed in kit item definitions");
                        continue;
                    }
                    String badRef = null;
                    for (var ref : parsed.items()) {
                        if (ref.key().isVanilla()) {
                            Material mat = Material.matchMaterial(ref.key().id());
                            if (mat == null || !mat.isItem() || mat.isAir()) {
                                badRef = "unknown vanilla item '" + ref.key().id() + "'";
                                break;
                            }
                        }
                    }
                    if (badRef != null) {
                        invalidItems.add("'" + trimmed + "': " + badRef);
                    } else {
                        translatedItems.add(trimmed);
                    }
                } catch (ItemSpecException bad) {
                    invalidItems.add("'" + trimmed + "': " + bad.getMessage());
                }
            }
            if (!invalidItems.isEmpty()) {
                records.add(Record.simple(Domain.KIT, norm, Status.BLOCKED,
                        "unsupported item spec(s): " + String.join("; ", invalidItems),
                        hash("kit:items:" + norm + ":" + String.join("\n", raw.rawItems())), null));
                continue;
            }
            if (translatedItems.size() > KitDefinition.MAX_ITEMS) {
                records.add(Record.simple(Domain.KIT, norm, Status.BLOCKED,
                        "kit has " + translatedItems.size() + " items (max " + KitDefinition.MAX_ITEMS + ")",
                        hash("kit:max:" + norm + ":" + translatedItems.size()), null));
                continue;
            }
            if (translatedItems.isEmpty()) {
                records.add(Record.simple(Domain.KIT, norm, Status.SKIPPED,
                        reviewCommands.isEmpty() ? "kit has no items" : "kit only contained embedded commands (see notes)",
                        hash("kit:empty:" + norm), null));
                continue;
            }
            kitOneTimeFlags.put(norm, oneTime);
            KitPayload payload = new KitPayload(norm, cooldownSeconds, oneTime,
                    List.copyOf(translatedItems), List.copyOf(reviewCommands));
            String ph = hash("kit:" + norm + ":" + cooldownSeconds + ":" + oneTime + ":" + String.join("\n", translatedItems));
            Status st = checkLedger(dest.ledgerHashes(), Domain.KIT, norm, ph);
            String reason = "already imported";
            if (st == null) {
                KitDefinition existing = dest.kits().get(norm);
                if (existing == null) {
                    st = Status.IMPORT;
                    reason = reviewCommands.isEmpty()
                            ? "new kit (" + translatedItems.size() + " item(s))"
                            : "new kit (" + translatedItems.size() + " item(s); " + reviewCommands.size() + " command(s) flagged for review)";
                } else if (existing.cooldownSeconds() == cooldownSeconds
                        && existing.oneTime() == oneTime
                        && existing.items().equals(translatedItems)) {
                    st = Status.PRESENT;
                    reason = "matches existing kit";
                } else {
                    st = Status.CONFLICT;
                    reason = "different kit definition already exists";
                }
            }
            records.add(new Record(Domain.KIT, norm, st, reason, ph, null, norm, null, null, null, null,
                    null, null, null, null, null, payload, null, null));
        }
    }

    private static void planKitUsages(
            EssentialsSnapshot.UserFile user,
            Destination dest,
            Map<String, Boolean> kitOneTimeFlags,
            List<Record> records
    ) {
        if (user.kitTimestamps().isEmpty()) return;
        UUID uuid = user.uuid();
        for (Map.Entry<String, Long> entry : user.kitTimestamps().entrySet()) {
            String kitName = entry.getKey();
            long ts = entry.getValue();
            if (!KitNames.isValid(kitName) || ts <= 0L) continue;
            String key = uuid + ":" + kitName;
            boolean oneTime = kitOneTimeFlags.getOrDefault(kitName, false);
            KitUsagePayload payload = new KitUsagePayload(kitName, ts, oneTime);
            String ph = hash("kit_usage:" + key + ":" + ts + ":" + oneTime);
            Status st = checkLedger(dest.ledgerHashes(), Domain.KIT_USAGE, key, ph);
            String reason = "already imported";
            if (st == null) {
                KitUsageRecord existing = dest.kitUsages().get(key);
                if (existing == null) {
                    st = Status.IMPORT;
                    reason = oneTime ? "one-time kit usage timestamp" : "kit cooldown timestamp";
                } else if (existing.lastClaimedAt() == ts && existing.oneTimeClaimed() == oneTime) {
                    st = Status.PRESENT;
                    reason = "matches existing kit usage";
                } else if (existing.lastClaimedAt() >= ts && (!oneTime || existing.oneTimeClaimed())) {
                    st = Status.SKIPPED;
                    reason = "newer/stricter kit usage already recorded";
                } else {
                    st = Status.IMPORT;
                    reason = "updates older kit usage timestamp";
                }
            }
            records.add(new Record(Domain.KIT_USAGE, key, st, reason, ph, uuid, kitName, null, null, null, null,
                    null, null, null, null, null, null, payload, null));
        }
    }

    private static void planWorth(
            List<EssentialsSnapshot.RawWorth> rawWorth,
            Destination dest,
            List<String> notes,
            List<Record> records
    ) {
        if (rawWorth.isEmpty()) return;
        Map<String, Material> materials = new HashMap<>();
        for (Material m : Material.values()) {
            if (!m.name().startsWith("LEGACY_") && m.isItem() && !m.isAir()) {
                materials.put(WorthSettings.key(m), m);
            }
        }
        Map<String, String> seen = new HashMap<>();
        for (EssentialsSnapshot.RawWorth raw : rawWorth) {
            Material mat = materials.get(WorthSettings.key(raw.rawKey()));
            if (mat == null) {
                records.add(Record.simple(Domain.WORTH, raw.rawKey(), Status.SKIPPED,
                        "unknown vanilla item key '" + raw.rawKey() + "'", hash("worth:unknown:" + raw.rawKey()), null));
                continue;
            }
            String canonicalKey = mat.name().toLowerCase(Locale.ROOT);
            String normalizedLookup = WorthSettings.key(mat);
            if (seen.putIfAbsent(canonicalKey, raw.rawKey()) != null) {
                records.add(Record.simple(Domain.WORTH, raw.rawKey(), Status.SKIPPED,
                        "duplicate worth entry for " + canonicalKey + " (already mapped from " + seen.get(canonicalKey) + ")",
                        hash("worth:dup:" + raw.rawKey()), null));
                continue;
            }
            BigDecimal price;
            try {
                BigDecimal parsed = new BigDecimal(raw.rawPrice());
                if (parsed.signum() < 0) {
                    price = BigDecimal.ONE.negate();
                } else if (parsed.compareTo(WorthSettings.MAX_PRICE) > 0) {
                    records.add(Record.simple(Domain.WORTH, canonicalKey, Status.BLOCKED,
                            "worth price " + raw.rawPrice() + " exceeds max " + WorthSettings.MAX_PRICE.toPlainString(),
                            hash("worth:max:" + canonicalKey + ":" + raw.rawPrice()), null));
                    continue;
                } else {
                    price = parsed.setScale(2, RoundingMode.HALF_UP);
                    if (price.compareTo(parsed) != 0) {
                        notes.add("worth.yml " + raw.rawKey() + " (" + raw.rawPrice() + ") rounded to " + price.toPlainString());
                    }
                }
            } catch (NumberFormatException bad) {
                records.add(Record.simple(Domain.WORTH, canonicalKey, Status.BLOCKED,
                        "malformed worth price: " + raw.rawPrice(), hash("worth:bad:" + canonicalKey + ":" + raw.rawPrice()), null));
                continue;
            }
            WorthPayload payload = new WorthPayload(canonicalKey, price);
            String ph = hash("worth:" + canonicalKey + ":" + price.toPlainString());
            Status st = checkLedger(dest.ledgerHashes(), Domain.WORTH, canonicalKey, ph);
            String reason = "already imported";
            if (st == null) {
                BigDecimal existing = dest.worthPrices().get(normalizedLookup);
                if (existing == null) {
                    st = Status.IMPORT;
                    reason = "new worth price " + price.toPlainString();
                } else if (existing.compareTo(price) == 0) {
                    st = Status.PRESENT;
                    reason = "matches existing worth price";
                } else {
                    st = Status.CONFLICT;
                    reason = "Allium worth.yml already sets " + existing.toPlainString() + " (Essentials: " + price.toPlainString() + ")";
                }
            }
            records.add(new Record(Domain.WORTH, canonicalKey, st, reason, ph, null, canonicalKey, null, null, null, null,
                    null, null, null, null, null, null, null, payload));
        }
    }

    private static void planBalance(EssentialsSnapshot.UserFile user, Destination dest,
                                    @Nullable EconomySettings balanceSettings, List<Record> records) {
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
        if (balanceSettings != null && (parsed.compareTo(balanceSettings.minimum()) < 0
                || parsed.compareTo(balanceSettings.maximum()) > 0)) {
            records.add(Record.simple(Domain.BALANCE, key, Status.BLOCKED,
                    "balance " + user.moneyRaw() + " outside configured bounds", hash("money:bounds:" + uuid + ":" + parsed.minor()), uuid));
            return;
        }
        String ph = hash("balance:" + uuid + ":" + parsed.minor());
        Status st = checkLedger(dest.ledgerHashes(), Domain.BALANCE, key, ph);
        String reason = "already imported";
        if (st == null) {
            ExistingAccount existing = dest.balances().get(uuid);
            if (balanceSettings == null) {
                st = Status.BLOCKED;
                reason = "Allium economy is unavailable";
            } else if (existing == null) {
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
                    "no valid return location (check logoutlocation or lastlocation)", hash("jailed:noreturn:" + owner), owner));
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
