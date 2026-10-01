package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.tradingcards.trade.PendingPayoutStore;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store holds owed heads as item references rather than serialised stacks,
 * so these tests need no running server — which is the other reason for that
 * choice, alongside surviving a retexture.
 */
class PendingPayoutStoreTest {

    @TempDir
    File dataFolder;

    private static final ItemRef CHICKEN_HEAD = ItemRef.parse("nexo:chicken_head");

    @Test
    void nothingIsOwedToAFreshPlayer() {
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.isEmpty(player));
        assertEquals(0, store.totalFor(player));
        assertTrue(store.takeAll(player).isEmpty());
    }

    @Test
    void anUncollectedPayoutIsHeldRatherThanDropped() {
        // The player closed the trade window without taking the heads. Nothing
        // is lost: the debt is recorded and paid on their next visit.
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.add(player, CHICKEN_HEAD, 7);
        assertFalse(store.isEmpty(player));
        assertEquals(7, store.totalFor(player));
        assertEquals(CHICKEN_HEAD, store.peek(player).get(0).item());
    }

    @Test
    void takingClearsTheDebtAndPaysOnlyOnce() {
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.add(player, CHICKEN_HEAD, 3);
        assertEquals(3, store.takeAll(player).get(0).amount());
        assertTrue(store.isEmpty(player));
        assertTrue(store.takeAll(player).isEmpty(),
            "a second collection must not pay the same debt twice");
    }

    @Test
    void twoInterruptedTradesAccumulateRatherThanOverwrite() {
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.add(player, CHICKEN_HEAD, 2);
        store.add(player, CHICKEN_HEAD, 5);
        List<PendingPayoutStore.Payout> owed = store.takeAll(player);
        assertEquals(2, owed.size());
        assertEquals(2, owed.get(0).amount());
        assertEquals(5, owed.get(1).amount());
    }

    @Test
    void aDebtSurvivesARestart() {
        // A pending payout is a debt the server owes. A restart must not
        // quietly write it off, so the store reloads from the same folder.
        UUID player = UUID.randomUUID();
        new PendingPayoutStore(dataFolder).add(player, CHICKEN_HEAD, 4);

        PendingPayoutStore reloaded = new PendingPayoutStore(dataFolder);
        assertFalse(reloaded.isEmpty(player));
        assertEquals(4, reloaded.totalFor(player));
        assertEquals(CHICKEN_HEAD, reloaded.peek(player).get(0).item());
    }

    @Test
    void theItemReferenceIsStoredNotTheRawItemData() {
        // Held as a reference, so a retexture changes what a player is owed
        // rather than freezing them on last season's head.
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.add(player, CHICKEN_HEAD, 2);
        assertEquals("nexo:chicken_head", store.peek(player).get(0).item().toString());
    }

    @Test
    void playersAreTrackedSeparately() {
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.add(a, CHICKEN_HEAD, 1);
        assertFalse(store.isEmpty(a));
        assertTrue(store.isEmpty(b));
        assertEquals(1, store.totalPendingPlayers());
    }

    @Test
    void aZeroOrNegativeAmountRecordsNothing() {
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.add(player, CHICKEN_HEAD, 0);
        store.add(player, CHICKEN_HEAD, -5);
        store.add(player, null, 3);
        assertTrue(store.isEmpty(player));
    }

    @Test
    void aCorruptRowIsSkippedWithoutCostingTheValidOnes() throws java.io.IOException {
        // A row that parses as YAML but is not a usable payout — no item, or a
        // non-positive amount — must be dropped on its own. Hand-editing or a
        // partially-written file is how this happens, and one bad row must not
        // write off the whole player's debt.
        UUID player = UUID.randomUUID();
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        store.add(player, CHICKEN_HEAD, 6);
        store.add(player, ItemRef.parse("nexo:cow_head"), 4);

        String id = player.toString();
        String contents = Files.readString(store.file().toPath());
        // Two unusable rows alongside the two real ones.
        contents = contents.replace("pending." + id,
            "pending." + id + "\n    - item: ''\n      amount: 3");
        contents = contents.replace("pending." + id,
            "pending." + id + "\n    - item: nexo:broken\n      amount: 0");
        Files.writeString(store.file().toPath(), contents);

        PendingPayoutStore reloaded = new PendingPayoutStore(dataFolder);
        assertEquals(10, reloaded.totalFor(player),
            "only the two valid debts should survive");
    }

    @Test
    void aCorruptPlayerKeyIsSkippedWithoutFailingTheLoad() throws java.io.IOException {
        UUID player = UUID.randomUUID();
        PendingPayoutStore store = new PendingPayoutStore(dataFolder);
        store.add(player, CHICKEN_HEAD, 2);

        String contents = Files.readString(store.file().toPath());
        Files.writeString(store.file().toPath(),
            contents + "\n  not-a-uuid:\n    - item: nexo:x\n      amount: 1");

        PendingPayoutStore reloaded = new PendingPayoutStore(dataFolder);
        assertEquals(2, reloaded.totalFor(player));
    }
}
