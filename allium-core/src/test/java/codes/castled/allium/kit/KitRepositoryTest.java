package codes.castled.allium.kit;

import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KitRepositoryTest {

    @TempDir
    Path dir;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private KitRepository sqliteRepository() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve(UUID.randomUUID() + ".db").toAbsolutePath();
        ConnectionSource source = () -> {
            Connection c = DriverManager.getConnection(url);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout = 10000");
            }
            return c;
        };
        KitRepository repository = new KitRepository(source, SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(1, repository.migrate());
        assertEquals(1, repository.migrate(), "migrating again changes nothing");
        return repository;
    }

    @Test
    void claimStateMachineEnforcesInFlightCooldownAndOneTimeRulesAndReleaseNeverBurnsCooldown() throws Exception {
        KitRepository repo = sqliteRepository();
        long now = 1_000_000L;

        UUID id1 = UUID.randomUUID();
        UUID token1 = UUID.randomUUID();
        KitDeliveryRecord first = new KitDeliveryRecord(
                id1, ALICE, "starter", token1, "server-a", "minecraft:stone_sword 1", 1,
                300_000L, false, false, now);

        // 1. Begin claim succeeds
        KitRepository.BeginOutcome b1 = repo.beginClaim(first, now);
        assertInstanceOf(KitRepository.BeginOutcome.Claiming.class, b1);
        assertEquals(List.of(id1), repo.claimingFor(ALICE, "server-a").stream().map(KitDeliveryRecord::id).toList());
        assertTrue(repo.claimingFor(ALICE, "server-b").isEmpty());

        // 2. Concurrent claim while first is in flight is rejected
        KitDeliveryRecord concurrent = new KitDeliveryRecord(
                UUID.randomUUID(), ALICE, "starter", UUID.randomUUID(), "server-a", "minecraft:stone_sword 1", 1,
                300_000L, false, false, now + 10);
        assertInstanceOf(KitRepository.BeginOutcome.AlreadyClaiming.class, repo.beginClaim(concurrent, now + 10));

        // 3. Releasing an undelivered claim does NOT record usage or burn cooldown
        assertFalse(repo.releaseClaim(id1, UUID.randomUUID()), "wrong token cannot release");
        assertTrue(repo.releaseClaim(id1, token1));
        assertTrue(repo.usage(ALICE, "starter").isEmpty(), "released claim never starts cooldown");

        // 4. Begin and finish a claim starts cooldown
        UUID id2 = UUID.randomUUID();
        UUID token2 = UUID.randomUUID();
        KitDeliveryRecord second = new KitDeliveryRecord(
                id2, ALICE, "starter", token2, "server-a", "minecraft:stone_sword 1", 1,
                300_000L, false, false, now + 100);
        assertInstanceOf(KitRepository.BeginOutcome.Claiming.class, repo.beginClaim(second, now + 100));
        assertFalse(repo.finishClaim(id2, UUID.randomUUID(), now + 200), "wrong token cannot finish");
        assertTrue(repo.finishClaim(id2, token2, now + 200));
        assertTrue(repo.finishClaim(id2, token2, now + 500), "idempotent replay succeeds without double-counting");
        assertTrue(repo.claimAcknowledged(id2, token2));

        KitUsageRecord usage = repo.usage(ALICE, "starter").orElseThrow();
        assertEquals(1L, usage.claimCount());
        assertEquals(now + 200, usage.lastClaimedAt());
        assertFalse(usage.oneTimeClaimed());

        // 5. Claiming again before cooldown expires is rejected with remaining millis
        KitDeliveryRecord duringCooldown = new KitDeliveryRecord(
                UUID.randomUUID(), ALICE, "starter", UUID.randomUUID(), "server-a", "minecraft:stone_sword 1", 1,
                300_000L, false, false, now + 100_200);
        KitRepository.BeginOutcome cd = repo.beginClaim(duringCooldown, now + 100_200);
        assertInstanceOf(KitRepository.BeginOutcome.OnCooldown.class, cd);
        assertEquals(200_000L, ((KitRepository.BeginOutcome.OnCooldown) cd).remainingMillis());

        // 6. Bypass cooldown succeeds during cooldown window
        UUID idBypass = UUID.randomUUID();
        UUID tokenBypass = UUID.randomUUID();
        KitDeliveryRecord bypass = new KitDeliveryRecord(
                idBypass, ALICE, "starter", tokenBypass, "server-a", "minecraft:stone_sword 1", 1,
                300_000L, false, true, now + 100_500);
        assertInstanceOf(KitRepository.BeginOutcome.Claiming.class, repo.beginClaim(bypass, now + 100_500));
        assertTrue(repo.finishClaim(idBypass, tokenBypass, now + 100_600));
        assertEquals(2L, repo.usage(ALICE, "starter").orElseThrow().claimCount());

        // 7. One-time kit can only be claimed once (even with bypassCooldown = true)
        UUID idOnce = UUID.randomUUID();
        UUID tokenOnce = UUID.randomUUID();
        KitDeliveryRecord once = new KitDeliveryRecord(
                idOnce, BOB, "founder", tokenOnce, "server-a", "minecraft:diamond 1", 1,
                0L, true, false, now);
        assertInstanceOf(KitRepository.BeginOutcome.Claiming.class, repo.beginClaim(once, now));
        assertTrue(repo.finishClaim(idOnce, tokenOnce, now + 50));
        assertTrue(repo.usage(BOB, "founder").orElseThrow().oneTimeClaimed());

        KitDeliveryRecord onceAgain = new KitDeliveryRecord(
                UUID.randomUUID(), BOB, "founder", UUID.randomUUID(), "server-a", "minecraft:diamond 1", 1,
                0L, true, true, now + 999_999L);
        assertInstanceOf(KitRepository.BeginOutcome.OneTimeClaimed.class, repo.beginClaim(onceAgain, now + 999_999L));
    }

    @Test
    void kitReceiptsRoundTripAndPreserveUnknownEntries() {
        UUID deliveryId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID token = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        KitReceipts.Claimed claimed = new KitReceipts.Claimed(deliveryId, token, "Starter");
        String encoded = KitReceipts.format(List.of(claimed, new KitReceipts.Unknown("FUTURE/1")));
        assertEquals("K/11111111-2222-3333-4444-555555555555/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/starter;FUTURE/1", encoded);
        List<KitReceipts.Entry> parsed = KitReceipts.parse(encoded);
        assertEquals(List.of(claimed, new KitReceipts.Unknown("FUTURE/1")), parsed);
        assertEquals("allium:kit_receipts", PlayerDataReceiptProof.KIT_RECEIPTS);
    }

    @Test
    void kitDefinitionValidationRejectsInvalidBoundsAndFlagsPaidKits() {
        KitDefinition free = new KitDefinition("Starter", true, 300L, false, 0.0D, List.of("minecraft:stone_sword 1"), "kits/starter.yml");
        assertEquals("starter", free.name());
        assertFalse(free.paid());
        assertEquals("allium.kits.starter", free.permissionNode());

        KitDefinition paid = new KitDefinition("vip", true, 3600L, true, 50.0D, List.of("minecraft:diamond 4"), "kits/vip.yml");
        assertTrue(paid.paid());
        assertTrue(paid.oneTime());

        assertThrows(IllegalArgumentException.class, () ->
                new KitDefinition("bad name!", true, 0L, false, 0.0D, List.of("minecraft:stone 1"), "kits/bad.yml"));
        assertThrows(IllegalArgumentException.class, () ->
                new KitDefinition("empty", true, 0L, false, 0.0D, List.of(), "kits/empty.yml"));
        assertThrows(IllegalArgumentException.class, () ->
                new KitDefinition("negcost", true, 0L, false, -1.0D, List.of("minecraft:stone 1"), "kits/neg.yml"));
    }
}
