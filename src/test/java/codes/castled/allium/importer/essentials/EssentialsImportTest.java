package codes.castled.allium.importer.essentials;

import codes.castled.allium.chat.ChatRepository;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Destination;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Domain;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Status;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.mail.MailRepository;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Optional;
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
                Destination.empty(), 1_800_000_000_000L, new Money(0), new Money(Money.HARD));
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
                snapshot, worlds, Destination.empty(), now, new Money(0), new Money(Money.HARD));
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
}
