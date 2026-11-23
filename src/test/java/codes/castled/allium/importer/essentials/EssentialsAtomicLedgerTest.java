package codes.castled.allium.importer.essentials;

import codes.castled.allium.chat.ChatRepository;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Destination;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Domain;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EssentialsAtomicLedgerTest {

    @ParameterizedTest
    @EnumSource(value = Domain.class, names = {"NICKNAME", "IGNORE", "PREFERENCE", "JAIL", "MUTE", "JAILED", "NAME"})
    void crashBeforeLedgerWriteRollsBackDestinationAndReceiptAtomically(Domain failingDomain, @TempDir Path dir) throws Exception {
        Path ess = Files.createDirectory(dir.resolve("Essentials"));
        Path userdata = Files.createDirectory(ess.resolve("userdata"));
        UUID worldId = UUID.randomUUID();
        UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");
        long now = 1_800_000_000_000L;
        long future = now + 3_600_000L;

        Files.writeString(ess.resolve("jail.yml"), """
                jails:
                  cell:
                    world: world
                    x: 1.0
                    y: 64.0
                    z: 2.0
                """);
        Files.writeString(userdata.resolve(alice + ".yml"), """
                last-account-name: Alice
                nickname: '&bAli'
                accepting-pay: false
                muted: true
                mute-reason: spam
                timestamps:
                  mute: %d
                  jail: %d
                jailed: true
                jail: cell
                logoutlocation:
                  world: world
                  x: 5.0
                  y: 64.0
                  z: 5.0
                ignore:
                - %s
                """.formatted(future, future, bob));

        ConnectionSource db = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("allium.db"));
        TravelRepository travel = new TravelRepository(db, SqlDialect.SQLITE, () -> now);
        TeleportRepository teleports = new TeleportRepository(db, SqlDialect.SQLITE, () -> now);
        MailRepository mail = new MailRepository(db, SqlDialect.SQLITE, () -> now);
        ChatRepository chat = new ChatRepository(db, SqlDialect.SQLITE);
        IgnoreRepository ignores = new IgnoreRepository(db, SqlDialect.SQLITE, () -> now);
        PreferenceRepository prefs = new PreferenceRepository(db, SqlDialect.SQLITE, () -> now);
        ModerationRepository moderation = new ModerationRepository(db, SqlDialect.SQLITE, () -> now);
        PlayerNameRepository names = new PlayerNameRepository(db, SqlDialect.SQLITE, () -> now);
        ImportLedger ledger = new ImportLedger(db, SqlDialect.SQLITE, () -> now);
        travel.migrate();
        teleports.migrate();
        mail.migrate();
        chat.migrate();
        ignores.migrate();
        prefs.migrate();
        moderation.migrate();
        names.migrate();
        ledger.migrate();

        var snapshot = EssentialsSnapshot.read(ess);
        var plan = EssentialsImportPlan.build(snapshot, (id, name) -> Optional.of(new ResolvedWorld(worldId, "world")),
                Destination.empty(), now, EconomySettings.defaults());
        var targets = new EssentialsImportApplier.Targets("test", null, travel, teleports, mail, chat,
                ignores, prefs, moderation, names);

        // Inject a failure immediately after the destination table write and right before ledger.record().
        assertThrows(SQLException.class, () -> EssentialsImportApplier.apply(plan, "crashed-run", ledger, targets,
                (c, record, result) -> {
                    if (record.domain() == failingDomain) {
                        throw new SQLException("simulated crash before ledger write for " + failingDomain);
                    }
                }));

        // Because every shared-DB domain shares one transaction with ImportLedger, the entire chunk rolled back:
        // neither destination rows nor ledger receipts were committed.
        assertTrue(ledger.hashes(EssentialsImportPlan.SOURCE).isEmpty(),
                "no ledger receipts may survive a rollback for " + failingDomain);
        assertNull(chat.load(alice).nickname());
        assertTrue(ignores.load(alice).isEmpty());
        assertTrue(prefs.load(alice).isEmpty());
        assertTrue(moderation.loadJail("cell").isEmpty());
        assertTrue(moderation.loadActiveMute(alice).isEmpty());
        assertTrue(moderation.loadJailed(alice).isEmpty());
        assertTrue(names.byUuid(alice).isEmpty());
        assertTrue(moderation.loadRecentAudit(10).isEmpty());

        // Retrying cleanly applies all records and writes matching ledger receipts atomically.
        var retry = EssentialsImportApplier.apply(plan, "clean-run", ledger, targets);
        assertEquals(0L, EssentialsImportApplier.counts(retry).get(EssentialsImportApplier.Result.CONFLICT));
        assertEquals("&bAli", chat.load(alice).nickname());
        assertTrue(ignores.load(alice).contains(bob));
        assertEquals(Boolean.FALSE, prefs.load(alice).orElseThrow().acceptPayments());
        assertTrue(moderation.loadJail("cell").isPresent());
        assertTrue(moderation.loadActiveMute(alice).isPresent());
        assertTrue(moderation.loadJailed(alice).isPresent());
        assertEquals("Alice", names.byUuid(alice).orElseThrow().name());

        // Replaying the same plan is 100% idempotent (all REPLAYED, zero conflicts).
        var replay = EssentialsImportApplier.apply(plan, "replay-run", ledger, targets);
        var replayCounts = EssentialsImportApplier.counts(replay);
        assertEquals(0L, replayCounts.get(EssentialsImportApplier.Result.CONFLICT));
        assertEquals(0L, replayCounts.get(EssentialsImportApplier.Result.APPLIED));
        assertTrue(replayCounts.get(EssentialsImportApplier.Result.REPLAYED) >= 7L);
    }
}
