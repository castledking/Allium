package codes.castled.allium.importer.essentials;

import codes.castled.allium.chat.ChatRepository;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomySchema;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Destination;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Domain;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Status;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.kit.KitDefinition;
import codes.castled.allium.kit.KitDefinitionLoader;
import codes.castled.allium.kit.KitDeliveryRecord;
import codes.castled.allium.kit.KitRepository;
import codes.castled.allium.mail.MailRepository;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import codes.castled.allium.worth.WorthSettings;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EssentialsImportTest {

    @Test
    void malformedLocationsAreOmittedAndDryRunIdentifiesTheirSourceFields(@TempDir Path source) throws Exception {
        Path userdata = Files.createDirectory(source.resolve("userdata"));
        Path warps = Files.createDirectory(source.resolve("warps"));
        UUID player = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        Files.writeString(warps.resolve("bad.yml"), "world: world\nx: not-a-number\ny: 64\nz: 24\n");
        Files.writeString(warps.resolve("missing.yml"), "world: world\nx: 0\nz: -24\n");
        Files.writeString(warps.resolve("valid.yml"), "world: world\nx: 0\ny: 64\nz: -24.5\n");
        Files.writeString(source.resolve("jail.yml"), """
                jails:
                  broken:
                    world: world
                    x: 0
                    y: 64
                    z: 0
                    yaw: '90'
                  cell:
                    world: world
                    x: 0
                    y: 64
                    z: 0
                """);
        Files.writeString(source.resolve("spawn.yml"), """
                spawns:
                  default:
                    world: world
                    x: 0
                    y: 64
                    z: 24
                    pitch: 1.0E39
                """);
        Files.writeString(userdata.resolve(player + ".yml"), """
                jailed: true
                jail: cell
                homes:
                  good:
                    world-name: world
                    x: -10
                    y: 64.5
                    z: 0
                  broken:
                    world: world
                    x: 0
                    y: 64
                    z: .inf
                logoutlocation:
                  world: world
                  x: 0
                  y: false
                  z: 0
                lastlocation:
                  world: world
                  x: 0
                  y: 64
                  z: 0
                  pitch: .nan
                """);

        var snapshot = EssentialsSnapshot.read(source);
        var plan = EssentialsImportPlan.build(snapshot, (id, name) -> Optional.of(new ResolvedWorld(worldId, "world")),
                Destination.empty(), 1_800_000_000_000L, EconomySettings.defaults());
        var blocked = plan.records().stream().filter(record -> record.status() == Status.BLOCKED).toList();
        assertTrue(blocked.stream().anyMatch(r -> r.domain() == Domain.WARP
                && r.reason().equals("warps/bad.yml: x must be a finite number")));
        assertTrue(blocked.stream().anyMatch(r -> r.domain() == Domain.WARP
                && r.reason().equals("warps/missing.yml: y is required")));
        assertTrue(blocked.stream().anyMatch(r -> r.domain() == Domain.JAIL
                && r.reason().equals("jail.yml: jails.broken.yaw must be a finite number")));
        assertTrue(blocked.stream().anyMatch(r -> r.domain() == Domain.SPAWN
                && r.reason().equals("spawn.yml: spawns.default.pitch is outside the finite float range")));
        assertTrue(blocked.stream().anyMatch(r -> r.domain() == Domain.JAILED
                && r.reason().startsWith("no valid return location")));

        String userFile = "userdata/" + player + ".yml: ";
        assertTrue(plan.notes().contains(userFile + "homes.broken.z must be a finite number"));
        assertTrue(plan.notes().contains(userFile + "logoutlocation.y must be a finite number"));
        assertTrue(plan.notes().contains(userFile + "lastlocation.pitch must be a finite number"));
        assertEquals(1L, plan.counts(Domain.HOME).get(Status.IMPORT));
        assertEquals(0L, plan.counts(Domain.LOGOUT).get(Status.IMPORT));

        var valid = plan.records().stream().filter(r -> r.domain() == Domain.WARP && r.status() == Status.IMPORT)
                .findFirst().orElseThrow().location();
        assertEquals(0.0, valid.x());
        assertEquals(-24.5, valid.z());
        assertEquals(0.0f, valid.yaw());
        assertEquals(0.0f, valid.pitch());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void balanceImportRecoversFromAnInterruptedCommit(boolean commitBeforeFailure, @TempDir Path dir) throws Exception {
        UUID player = UUID.randomUUID();
        Path userdata = Files.createDirectory(dir.resolve("userdata"));
        Files.writeString(userdata.resolve(player + ".yml"), "money: '1234.56'\n");
        ConnectionSource db = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("allium.db"));
        ImportLedger ledger = new ImportLedger(db, SqlDialect.SQLITE, System::currentTimeMillis);
        ledger.migrate();
        var settings = EconomySettings.defaults();
        var engine = new EconomyEngine(db, settings, (c, from, to) -> true, (c, operation) -> {
            assertTrue(ledger.hash(c, EssentialsImportPlan.SOURCE, Domain.BALANCE.id(), player.toString()).isPresent());
            if (commitBeforeFailure) {
                try (var statement = c.createStatement()) { statement.execute("COMMIT"); }
            }
            throw new SQLException("injected commit interruption");
        });
        var plan = balancePlan(dir, settings);
        assertEquals(1L, plan.counts(Domain.BALANCE).get(Status.IMPORT));

        if (commitBeforeFailure) {
            var outcome = EssentialsImportApplier.apply(plan, "interrupted", ledger, balanceTarget(engine)).get(0);
            assertEquals(EssentialsImportApplier.Result.REPLAYED, outcome.result());
        } else {
            assertThrows(SQLException.class,
                    () -> EssentialsImportApplier.apply(plan, "interrupted", ledger, balanceTarget(engine)));
            assertInstanceOf(EconomyResult.Missing.class, engine.balance(player));
            assertTrue(ledger.hashes(EssentialsImportPlan.SOURCE).isEmpty());
        }

        var reopened = new EconomyEngine(db, settings, (c, from, to) -> true);
        var retry = EssentialsImportApplier.apply(balancePlan(dir, settings), "retry", ledger, balanceTarget(reopened)).get(0);
        assertEquals(commitBeforeFailure ? EssentialsImportApplier.Result.REPLAYED : EssentialsImportApplier.Result.APPLIED,
                retry.result());
        assertEquals(new Money(123456), assertInstanceOf(EconomyResult.Found.class, reopened.balance(player)).book());
        assertEquals(plan.records().get(0).payloadHash(), ledger.hashes(EssentialsImportPlan.SOURCE).get("balance:" + player));
        try (var c = db.connection(); var statement = c.createStatement();
             var operations = statement.executeQuery("SELECT kind, amount_minor FROM " + EconomySchema.OPERATIONS)) {
            assertTrue(operations.next());
            assertEquals("OPEN_IMPORTED", operations.getString("kind"));
            assertEquals(123456L, operations.getLong("amount_minor"));
            assertFalse(operations.next());
        }
    }

    @Test
    void dryRunBlocksBalancesWhenEconomyIsUnavailable(@TempDir Path dir) throws Exception {
        Path userdata = Files.createDirectory(dir.resolve("userdata"));
        Files.writeString(userdata.resolve(UUID.randomUUID() + ".yml"), "last-account-name: Alice\nmoney: 200\n");
        var plan = balancePlan(dir, null);
        assertEquals(1L, plan.counts(Domain.BALANCE).get(Status.BLOCKED));
        assertEquals(1L, plan.counts(Domain.NAME).get(Status.IMPORT));
        assertTrue(plan.records().stream().anyMatch(r -> r.domain() == Domain.BALANCE
                && r.reason().equals("Allium economy is unavailable")));
    }

    @Test
    void balanceApplyPreservesAccountsCreatedAfterReview(@TempDir Path dir) throws Exception {
        Path userdata = Files.createDirectory(dir.resolve("userdata"));
        UUID matching = UUID.randomUUID();
        UUID different = UUID.randomUUID();
        Files.writeString(userdata.resolve(matching + ".yml"), "money: 100\n");
        Files.writeString(userdata.resolve(different + ".yml"), "money: 500\n");
        var settings = EconomySettings.defaults();
        var reviewed = balancePlan(dir, settings);
        ConnectionSource db = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("allium.db"));
        ImportLedger ledger = new ImportLedger(db, SqlDialect.SQLITE, System::currentTimeMillis);
        ledger.migrate();
        var engine = new EconomyEngine(db, settings, (c, from, to) -> true);
        for (UUID id : List.of(matching, different)) {
            var open = engine.request(UUID.randomUUID(), "test", id.toString(), EconomyRequest.Kind.OPEN,
                    "test", id, null, settings.starting());
            assertInstanceOf(EconomyResult.Committed.class, engine.execute(open));
        }

        var outcomes = EssentialsImportApplier.counts(EssentialsImportApplier.apply(reviewed, "apply", ledger, balanceTarget(engine)));
        assertEquals(1L, outcomes.get(EssentialsImportApplier.Result.ADOPTED));
        assertEquals(1L, outcomes.get(EssentialsImportApplier.Result.CONFLICT));
        assertEquals(settings.starting(), assertInstanceOf(EconomyResult.Found.class, engine.balance(different)).book());
        assertEquals(Set.of("balance:" + matching), ledger.hashes(EssentialsImportPlan.SOURCE).keySet());
    }

    private static EssentialsImportPlan balancePlan(Path dir, EconomySettings settings) throws Exception {
        return EssentialsImportPlan.build(EssentialsSnapshot.read(dir), (id, name) -> Optional.empty(), Destination.empty(),
                1_800_000_000_000L, settings);
    }

    private static EssentialsImportApplier.Targets balanceTarget(EconomyEngine engine) {
        return new EssentialsImportApplier.Targets("test", engine, null, null, null, null, null, null, null, null);
    }

    @Test
    void fullEssentialsSnapshotPlansAppliesAndReplaysIdempotently(@TempDir Path dir) throws Exception {
        Path ess = dir.resolve("Essentials");
        Files.createDirectories(ess.resolve("userdata"));
        Files.createDirectories(ess.resolve("warps"));

        UUID worldUuid = UUID.randomUUID();
        UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID npc = UUID.fromString("33333333-3333-4333-8333-333333333333");
        long now = 1_800_000_000_000L;
        long future = now + 3_600_000L;
        long past = now - 3_600_000L;

        Files.writeString(ess.resolve("warps/spawn.yml"), """
                world: %s
                world-name: world
                x: 10.5
                y: 64.0
                z: -20.25
                yaw: 90.0
                pitch: 0.0
                name: Spawn
                """.formatted(worldUuid));

        Files.writeString(ess.resolve("jails.yml"), """
                jails:
                  cell1:
                    world: %s
                    world-name: world
                    x: 100.0
                    y: 70.0
                    z: 100.0
                    yaw: 0.0
                    pitch: 0.0
                """.formatted(worldUuid));

        Files.writeString(ess.resolve("userdata/" + alice + ".yml"), """
                last-account-name: Alice
                money: '1234.56'
                nickname: '&dAli'
                accepting-pay: false
                teleportenabled: false
                msgtoggle: true
                ignore:
                  - '%s'
                  - '%s'
                  - 'not-a-uuid'
                homes:
                  Base:
                    world: %s
                    world-name: world
                    x: 1.0
                    y: 65.0
                    z: 2.0
                    yaw: 180.0
                    pitch: 5.0
                logoutlocation:
                  world: %s
                  world-name: world
                  x: 5.0
                  y: 66.0
                  z: 6.0
                  yaw: 0.0
                  pitch: 0.0
                timestamps:
                  logout: %d
                  mute: %d
                  jail: %d
                muted: true
                mute-reason: 'Spamming'
                jailed: true
                jail: cell1
                mail:
                  - 'Legacy plain note'
                  - legacy: false
                    read: false
                    sender-name: Bob
                    sender-uuid: '%s'
                    timestamp: %d
                    expire: %d
                    message: 'Hello Alice'
                  - legacy: false
                    read: false
                    sender-name: Bob
                    sender-uuid: '%s'
                    timestamp: %d
                    expire: %d
                    message: 'Expired message'
                """.formatted(bob, alice, worldUuid, worldUuid, now - 1000, future, future, bob, now - 500, future, bob, past - 500, past));

        // Bob has no money field (must skip, not treat as zero) and an online-only jail timer (must block).
        Files.writeString(ess.resolve("userdata/" + bob + ".yml"), """
                last-account-name: Bob
                jailed: true
                jail: cell1
                timestamps:
                  onlinejail: 60000
                """);

        // NPC must be excluded entirely.
        Files.writeString(ess.resolve("userdata/" + npc + ".yml"), """
                npc: true
                money: '99999'
                """);

        // Non-UUID filename must be reported and skipped, never guessed.
        Files.writeString(ess.resolve("userdata/oldplayer.yml"), "money: '50'\n");

        EssentialsSnapshot snapshot = EssentialsSnapshot.read(ess);
        assertEquals(1, snapshot.skippedUserFiles().size());

        Path dbPath = dir.resolve("allium.db");
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        TravelRepository travel = new TravelRepository(cs, SqlDialect.SQLITE, () -> now);
        TeleportRepository teleports = new TeleportRepository(cs, SqlDialect.SQLITE, () -> now);
        MailRepository mail = new MailRepository(cs, SqlDialect.SQLITE, () -> now);
        ChatRepository chat = new ChatRepository(cs, SqlDialect.SQLITE);
        IgnoreRepository ignores = new IgnoreRepository(cs, SqlDialect.SQLITE, () -> now);
        PreferenceRepository prefs = new PreferenceRepository(cs, SqlDialect.SQLITE, () -> now);
        ModerationRepository moderation = new ModerationRepository(cs, SqlDialect.SQLITE, () -> now);
        PlayerNameRepository names = new PlayerNameRepository(cs, SqlDialect.SQLITE, () -> now);
        ImportLedger ledger = new ImportLedger(cs, SqlDialect.SQLITE, () -> now);
        travel.migrate();
        teleports.migrate();
        mail.migrate();
        chat.migrate();
        ignores.migrate();
        prefs.migrate();
        moderation.migrate();
        names.migrate();
        ledger.migrate();
        EconomyEngine economy = new EconomyEngine(cs, EconomySettings.defaults(), (c, s, r) -> true);

        EssentialsImportPlan.Worlds worlds = (id, name) ->
                ("world".equals(name) || worldUuid.toString().equals(id))
                        ? Optional.of(new ResolvedWorld(worldUuid, "world"))
                        : Optional.empty();

        EssentialsImportPlan plan = EssentialsImportPlan.build(
                snapshot, worlds, Destination.empty(), now, economy.settings());
        assertEquals(1, plan.npcSkipped());
        assertEquals(1, plan.nonUuidFiles());
        assertEquals(1L, plan.counts(Domain.BALANCE).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.BALANCE).get(Status.SKIPPED)); // Bob missing money
        assertEquals(1L, plan.counts(Domain.HOME).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.WARP).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.LOGOUT).get(Status.IMPORT));
        assertEquals(2L, plan.counts(Domain.MAIL).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.MAIL).get(Status.SKIPPED)); // expired mail
        assertEquals(1L, plan.counts(Domain.NICKNAME).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.IGNORE).get(Status.IMPORT));
        assertEquals(2L, plan.counts(Domain.IGNORE).get(Status.SKIPPED)); // self-ignore + invalid UUID
        assertEquals(3L, plan.counts(Domain.PREFERENCE).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.JAIL).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.MUTE).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.JAILED).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.JAILED).get(Status.BLOCKED)); // Bob online-only jail

        var targets = new EssentialsImportApplier.Targets("test-server", economy, travel, teleports, mail, chat,
                ignores, prefs, moderation, names);
        var outcomes = EssentialsImportApplier.apply(plan, "essentials-20261122-080000", ledger, targets);
        var counts = EssentialsImportApplier.counts(outcomes);
        assertEquals(0L, counts.get(EssentialsImportApplier.Result.CONFLICT));
        assertTrue(counts.get(EssentialsImportApplier.Result.APPLIED) >= 15L);

        // Verify destination state
        var bal = economy.balance(alice);
        assertInstanceOf(EconomyResult.Found.class, bal);
        assertEquals(123_456L, ((EconomyResult.Found) bal).book().minor());
        assertInstanceOf(EconomyResult.Missing.class, economy.balance(bob));
        assertTrue(travel.home(alice, "base").isPresent());
        assertTrue(travel.warp("spawn").isPresent());
        assertTrue(teleports.load(alice, "logout").isPresent());
        assertEquals("&dAli", chat.load(alice).nickname());
        assertTrue(ignores.load(alice).contains(bob));
        var storedPrefs = prefs.load(alice).orElseThrow();
        assertEquals(Boolean.FALSE, storedPrefs.acceptPayments());
        assertEquals(Boolean.FALSE, storedPrefs.acceptTeleportRequests());
        assertEquals(Boolean.FALSE, storedPrefs.acceptMessages()); // msgtoggle: true -> accept_messages: false
        assertTrue(moderation.loadJail("cell1").isPresent());
        assertTrue(moderation.loadActiveMute(alice).isPresent());
        assertTrue(moderation.loadJailed(alice).isPresent());
        assertEquals("Alice", names.byUuid(alice).orElseThrow().name());

        // Second apply must replay every record without duplicating anything.
        var replay = EssentialsImportApplier.apply(plan, "essentials-20261122-080001", ledger, targets);
        assertEquals(0L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.APPLIED));
        assertTrue(EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.REPLAYED) >= 15L);
        assertEquals(123_456L, ((EconomyResult.Found) economy.balance(alice)).book().minor());
    }

    @Test
    void enabledSignsImportRecognizesBuyAndSellAsSupported(@TempDir Path source) throws Exception {
        Files.writeString(source.resolve("config.yml"), """
                enabledSigns:
                  - color
                  - buy
                  - sell
                  - warp
                  - trade
                """);
        var snapshot = EssentialsSnapshot.read(source);
        var plan = EssentialsImportPlan.build(snapshot, (id, name) -> Optional.empty(),
                Destination.empty(), 1_800_000_000_000L, EconomySettings.defaults());
        assertEquals(List.of("color", "buy", "sell", "warp"), plan.supportedSigns());
        assertEquals(List.of("trade"), plan.unsupportedSigns());
    }

    @Test
    void lastLocationImportsToBackSlotAndReplaysIdempotently(@TempDir Path dir) throws Exception {
        Path ess = dir.resolve("Essentials");
        Files.createDirectories(ess.resolve("userdata"));
        UUID worldUuid = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        long now = 1_800_000_000_000L;

        Files.writeString(ess.resolve("userdata/" + alice + ".yml"), """
                lastlocation:
                  world: %s
                  world-name: world
                  x: 12.5
                  y: 70.0
                  z: -4.25
                  yaw: 45.0
                  pitch: -10.0
                """.formatted(worldUuid));
        Files.writeString(ess.resolve("userdata/" + bob + ".yml"), """
                lastlocation:
                  world: %s
                  world-name: world
                  x: 99.0
                  y: 64.0
                  z: 99.0
                  yaw: 0.0
                  pitch: 0.0
                """.formatted(worldUuid));

        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("allium.db"));
        TeleportRepository teleports = new TeleportRepository(cs, SqlDialect.SQLITE, () -> now);
        ImportLedger ledger = new ImportLedger(cs, SqlDialect.SQLITE, () -> now);
        teleports.migrate();
        ledger.migrate();

        SavedLocation bobExisting = new SavedLocation(worldUuid, "world", 1.0, 64.0, 1.0, 0.0f, 0.0f);
        teleports.save(bob, "back", bobExisting);

        Destination dest = new Destination(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), teleports.slot("back"), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), ledger.hashes(EssentialsImportPlan.SOURCE));

        EssentialsSnapshot snapshot = EssentialsSnapshot.read(ess);
        EssentialsImportPlan plan = EssentialsImportPlan.build(snapshot,
                (id, name) -> Optional.of(new ResolvedWorld(worldUuid, "world")), dest, now, null);

        assertEquals(1L, plan.counts(Domain.BACK).get(Status.IMPORT));
        assertEquals(1L, plan.counts(Domain.BACK).get(Status.SKIPPED));

        var targets = new EssentialsImportApplier.Targets("test", null, null, teleports, null, null, null, null, null, null);
        var applied = EssentialsImportApplier.apply(plan, "essentials-20261123-010000", ledger, targets);
        assertEquals(1L, EssentialsImportApplier.counts(applied).get(EssentialsImportApplier.Result.APPLIED));

        SavedLocation imported = teleports.load(alice, "back").orElseThrow();
        assertEquals(12.5, imported.x());
        assertEquals(-4.25, imported.z());
        assertEquals(1.0, teleports.load(bob, "back").orElseThrow().x());

        var replay = EssentialsImportApplier.apply(plan, "essentials-20261123-010001", ledger, targets);
        assertEquals(0L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.APPLIED));
        assertEquals(1L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.REPLAYED));
    }

    @Test
    void kitsAndKitUsageImportSafelyWithoutExecutingEmbeddedCommands(@TempDir Path dir) throws Exception {
        Path ess = dir.resolve("Essentials");
        Path dataFolder = dir.resolve("Allium");
        Files.createDirectories(ess.resolve("userdata"));
        Files.createDirectories(dataFolder);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        long now = 1_800_000_000_000L;

        Files.writeString(ess.resolve("kits.yml"), """
                kits:
                  Starter:
                    delay: -1
                    items:
                      - stone_pickaxe 1
                      - bread 8
                      - /give {USERNAME} diamond 64
                      - $100
                      - '@msg hi'
                  Tools:
                    delay: 3600
                    items:
                      - iron_shovel 1
                  Broken:
                    delay: 60
                    items:
                      - not_a_real_material 1
                  CommandOnly:
                    delay: 60
                    items:
                      - /say hello
                  Existing:
                    delay: 60
                    items:
                      - apple 2
                """);
        Files.writeString(ess.resolve("userdata/" + alice + ".yml"), """
                timestamps:
                  kits:
                    Starter: 1799999000000
                    Tools: 1799999500000
                """);
        Files.writeString(ess.resolve("userdata/" + bob + ".yml"), """
                timestamps:
                  kits:
                    Tools: 1799990000000
                """);

        KitDefinitionLoader kitLoader = new KitDefinitionLoader(dataFolder.toFile(), ignored -> null, ignored -> {}, ignored -> {});
        KitDefinition preExisting = new KitDefinition("existing", true, 300L, false, KitDefinition.ZERO_COST,
                List.of("golden_apple 1"), "kits/existing.yml");
        kitLoader.save(preExisting);

        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("allium.db"));
        KitRepository kitsRepo = new KitRepository(cs, SqlDialect.SQLITE, () -> now);
        ImportLedger ledger = new ImportLedger(cs, SqlDialect.SQLITE, () -> now);
        kitsRepo.migrate();
        ledger.migrate();

        // Bob already has a newer local usage for 'tools' that must not be overwritten.
        try (var c = cs.connection()) {
            kitsRepo.importUsage(c, bob, "tools", 1_799_999_999_000L, false);
        }

        var parser = new ItemSpecParser(true);
        Destination dest = new Destination(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                kitLoader.load(parser).definitions(), kitsRepo.loadAllUsages(), Map.of(),
                ledger.hashes(EssentialsImportPlan.SOURCE));

        EssentialsSnapshot snapshot = EssentialsSnapshot.read(ess);
        EssentialsImportPlan plan = EssentialsImportPlan.build(snapshot, (id, name) -> Optional.empty(), dest, now, null);

        assertEquals(2L, plan.counts(Domain.KIT).get(Status.IMPORT)); // starter + tools
        assertEquals(1L, plan.counts(Domain.KIT).get(Status.BLOCKED)); // broken
        assertEquals(1L, plan.counts(Domain.KIT).get(Status.SKIPPED)); // commandonly
        assertEquals(1L, plan.counts(Domain.KIT).get(Status.CONFLICT)); // existing
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("/give {USERNAME} diamond 64")));
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("$100")));
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("@msg hi")));

        assertEquals(2L, plan.counts(Domain.KIT_USAGE).get(Status.IMPORT)); // alice starter + tools
        assertEquals(1L, plan.counts(Domain.KIT_USAGE).get(Status.SKIPPED)); // bob newer tools

        var targets = new EssentialsImportApplier.Targets("test", null, null, null, null, null, null, null, null, null,
                kitsRepo, kitLoader, null);
        var applied = EssentialsImportApplier.apply(plan, "essentials-20261123-020000", ledger, targets);
        assertEquals(4L, EssentialsImportApplier.counts(applied).get(EssentialsImportApplier.Result.APPLIED));

        var loaded = kitLoader.load(parser);
        assertTrue(loaded.rejected().isEmpty(), () -> "Unexpected rejected kits: " + loaded.rejected());
        KitDefinition starter = loaded.definitions().get("starter");
        assertNotNull(starter);
        assertTrue(starter.oneTime());
        assertEquals(0L, starter.cooldownSeconds());
        assertEquals(List.of("stone_pickaxe 1", "bread 8"), starter.items());
        assertEquals(List.of("golden_apple 1"), loaded.definitions().get("existing").items());

        // One-time usage for Alice's starter kit must block claiming again.
        var claimAttempt = kitsRepo.beginClaim(new KitDeliveryRecord(
                UUID.randomUUID(), alice, "starter", UUID.randomUUID(), "srv", "[]", 2, 0L, true, false, now), now);
        assertInstanceOf(KitRepository.BeginOutcome.OneTimeClaimed.class, claimAttempt);
        // Bob's newer local timestamp must be preserved.
        assertEquals(1_799_999_999_000L, kitsRepo.loadAllUsages().get(bob + ":tools").lastClaimedAt());

        var replay = EssentialsImportApplier.apply(plan, "essentials-20261123-020001", ledger, targets);
        assertEquals(0L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.APPLIED));
        assertEquals(4L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.REPLAYED));
    }

    @Test
    void worthImportNormalizesVanillaKeysPreservesExistingAlliumPricesAndReplays(@TempDir Path dir) throws Exception {
        Path ess = dir.resolve("Essentials");
        Path worthFile = dir.resolve("Allium/worth.yml");
        Files.createDirectories(ess);
        Files.createDirectories(worthFile.getParent());
        long now = 1_800_000_000_000L;

        Files.writeString(worthFile, """
                allow-named-items: true
                multipliers:
                  default: 1.0
                  vip: 1.5
                worth:
                  diamond: 99.0
                  coal: 2.0
                """);
        Files.writeString(ess.resolve("worth.yml"), """
                worth:
                  diamondpickaxe: 12.345
                  iron_ingot:
                    '0': 5.0
                    '*': 4.5
                  gold_ingot:
                    '0': 8.25
                  diamond: 50.0
                  coal: 2.0
                  bedrock: -1
                  fake_custom_item: 10.0
                """);

        YamlConfiguration existingYaml = new YamlConfiguration();
        existingYaml.load(worthFile.toFile());
        WorthSettings before = WorthSettings.read(existingYaml, ignored -> null, ignored -> {});

        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("allium.db"));
        ImportLedger ledger = new ImportLedger(cs, SqlDialect.SQLITE, () -> now);
        ledger.migrate();

        Destination dest = new Destination(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), before.prices(), ledger.hashes(EssentialsImportPlan.SOURCE));

        EssentialsSnapshot snapshot = EssentialsSnapshot.read(ess);
        EssentialsImportPlan plan = EssentialsImportPlan.build(snapshot, (id, name) -> Optional.empty(), dest, now, null);

        assertEquals(4L, plan.counts(Domain.WORTH).get(Status.IMPORT)); // diamond_pickaxe, iron_ingot, gold_ingot, bedrock
        assertEquals(1L, plan.counts(Domain.WORTH).get(Status.PRESENT)); // coal
        assertEquals(1L, plan.counts(Domain.WORTH).get(Status.CONFLICT)); // diamond
        assertEquals(1L, plan.counts(Domain.WORTH).get(Status.SKIPPED)); // fake_custom_item
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("diamondpickaxe") && n.contains("12.35")));

        var targets = new EssentialsImportApplier.Targets("test", null, null, null, null, null, null, null, null, null,
                null, null, worthFile);
        var applied = EssentialsImportApplier.apply(plan, "essentials-20261123-030000", ledger, targets);
        var counts = EssentialsImportApplier.counts(applied);
        assertEquals(4L, counts.get(EssentialsImportApplier.Result.APPLIED));
        assertEquals(1L, counts.get(EssentialsImportApplier.Result.ADOPTED));
        assertTrue(Files.isRegularFile(worthFile.resolveSibling("worth.yml.before-edit")));

        YamlConfiguration afterYaml = new YamlConfiguration();
        afterYaml.load(worthFile.toFile());
        WorthSettings after = WorthSettings.read(afterYaml, ignored -> null, ignored -> {});
        assertTrue(after.allowNamedItems());
        assertEquals(new BigDecimal("1.50"), after.multipliers().get("vip"));
        assertEquals(new BigDecimal("99.00"), after.price(Material.DIAMOND).orElseThrow());
        assertEquals(new BigDecimal("2.00"), after.price(Material.COAL).orElseThrow());
        assertEquals(new BigDecimal("12.35"), after.price(Material.DIAMOND_PICKAXE).orElseThrow());
        assertEquals(new BigDecimal("4.50"), after.price(Material.IRON_INGOT).orElseThrow());
        assertEquals(new BigDecimal("8.25"), after.price(Material.GOLD_INGOT).orElseThrow());
        assertTrue(after.price(Material.BEDROCK).isEmpty());

        var replay = EssentialsImportApplier.apply(plan, "essentials-20261123-030001", ledger, targets);
        assertEquals(0L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.APPLIED));
        assertEquals(5L, EssentialsImportApplier.counts(replay).get(EssentialsImportApplier.Result.REPLAYED));
    }
}
