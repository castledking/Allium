package codes.castled.curios;

import codes.castled.allium.api.AddonEconomy;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class CuriosRecoveryStoreTest {

    private static final NamespacedKey CLAIMS_KEY = new NamespacedKey("curios", "pending_trade_claims");
    private static final NamespacedKey ROLLS_KEY = new NamespacedKey("curios", "pending_roll_settlements");

    private UUID playerId;
    private Map<NamespacedKey, Object> savedPdc;
    private Map<NamespacedKey, Object> livePdc;
    private Player player;
    private PlayerInventory inventory;
    private AtomicInteger freeItemCapacity;
    private List<String> deliveredItems;
    private CuriosRecoveryStore.ItemCodec testCodec;
    private CuriosRecoveryStore store;

    @BeforeEach
    void setUp() {
        playerId = UUID.randomUUID();
        savedPdc = new HashMap<>();
        livePdc = new HashMap<>();
        freeItemCapacity = new AtomicInteger(64);
        deliveredItems = new ArrayList<>();

        testCodec = new CuriosRecoveryStore.ItemCodec() {
            @Override
            public String encode(ItemStack stack) {
                return stack.getType().name() + ":" + stack.getAmount();
            }

            @Override
            public ItemStack decode(String encoded) {
                if (encoded == null || !encoded.contains(":")) return null;
                String[] parts = encoded.split(":");
                return mockItem(Material.valueOf(parts[0]), Integer.parseInt(parts[1]));
            }
        };

        player = buildPlayer(livePdc);
        store = new CuriosRecoveryStore(CLAIMS_KEY, ROLLS_KEY, testCodec);
    }

    private Player buildPlayer(Map<NamespacedKey, Object> pdcBacking) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcBacking.get(inv.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcBacking.containsKey(inv.getArgument(0)));
        doAnswer(inv -> {
            pdcBacking.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        doAnswer(inv -> {
            pdcBacking.remove(inv.getArgument(0));
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));

        inventory = mock(PlayerInventory.class);
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(inv -> {
            Object[] raw = inv.getArguments();
            HashMap<Integer, ItemStack> overflow = new HashMap<>();
            for (int i = 0; i < raw.length; i++) {
                ItemStack stack = (ItemStack) raw[i];
                int space = freeItemCapacity.get();
                int accepted = Math.min(space, stack.getAmount());
                if (accepted > 0) {
                    freeItemCapacity.addAndGet(-accepted);
                    deliveredItems.add(stack.getType().name() + "x" + accepted);
                }
                int left = stack.getAmount() - accepted;
                if (left > 0) {
                    overflow.put(i, mockItem(stack.getType(), left));
                }
            }
            return overflow;
        });

        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(playerId);
        when(p.isOnline()).thenReturn(true);
        when(p.getPersistentDataContainer()).thenReturn(pdc);
        when(p.getInventory()).thenReturn(inventory);
        doAnswer(inv -> {
            savedPdc = new HashMap<>(pdcBacking);
            return null;
        }).when(p).saveData();
        return p;
    }

    private static ItemStack mockItem(Material material, int amount) {
        AtomicInteger amt = new AtomicInteger(amount);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(inv -> amt.get());
        doAnswer(inv -> {
            amt.set(inv.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(inv -> mockItem(material, amt.get()));
        return stack;
    }

    @Test
    void tradeInSurvivesHardStopAfterCardRemovalAndRecoversOnReconnect() {
        AtomicBoolean cardRemoved = new AtomicBoolean(false);
        ItemStack reward = mockItem(Material.ZOMBIE_HEAD, 5);

        UUID claimId = store.issueTradeClaim(player, "zombie_simple", reward, () -> cardRemoved.set(true));
        assertNotNull(claimId);
        assertTrue(cardRemoved.get());
        assertEquals(1, store.pendingClaims(player).size());

        // Simulate hard stop: discard in-memory GUI & live state, reboot with only savedPdc
        Map<NamespacedKey, Object> restartedPdc = new HashMap<>(savedPdc);
        Player reconnected = buildPlayer(restartedPdc);
        CuriosRecoveryStore restartedStore = new CuriosRecoveryStore(CLAIMS_KEY, ROLLS_KEY, testCodec);

        assertEquals(1, restartedStore.pendingClaims(reconnected).size());
        CuriosRecoveryStore.RecoverySummary summary = restartedStore.claimAllPending(reconnected);
        assertEquals(1, summary.claimsCompleted());
        assertEquals(0, summary.claimsRemaining());
        assertTrue(restartedStore.pendingClaims(reconnected).isEmpty());
        assertEquals(List.of("ZOMBIE_HEADx5"), deliveredItems);
    }

    @Test
    void fullAndPartialInventoryPreserveExactRemainderUntilSpaceIsFreed() {
        freeItemCapacity.set(0);
        ItemStack reward = mockItem(Material.DIAMOND, 10);
        UUID claimId = store.issueTradeClaim(player, "creeper_fabled", reward, () -> {});
        assertNotNull(claimId);

        // Attempt collect with full inventory -> nothing delivered, all 10 remain saved
        CuriosRecoveryStore.CollectResult full = store.collectTradeClaim(player, claimId);
        assertFalse(full.deliveredAll());
        assertEquals(10, full.remaining().getAmount());
        assertTrue(deliveredItems.isEmpty());
        assertEquals(10, store.pendingClaims(player).get(0).reward().getAmount());

        // Free space for 4 items -> 4 delivered, 6 remain durably persisted
        freeItemCapacity.set(4);
        CuriosRecoveryStore.CollectResult partial = store.collectTradeClaim(player, claimId);
        assertFalse(partial.deliveredAll());
        assertEquals(6, partial.remaining().getAmount());
        assertEquals(List.of("DIAMONDx4"), deliveredItems);
        assertEquals(6, store.pendingClaims(player).get(0).reward().getAmount());

        // Free space for the rest and recover via claimAllPending -> remaining 6 delivered, claim cleared
        freeItemCapacity.set(64);
        CuriosRecoveryStore.RecoverySummary done = store.claimAllPending(player);
        assertEquals(1, done.claimsCompleted());
        assertEquals(0, done.claimsRemaining());
        assertTrue(store.pendingClaims(player).isEmpty());
        assertEquals(List.of("DIAMONDx4", "DIAMONDx6"), deliveredItems);
    }

    @Test
    void paidBonusRollRecoversFailedCaptureAndFailedReleaseUsingStableOperationId() {
        class FaultyEconomy implements AddonEconomy {
            double balance = 1000;
            final Map<UUID, Double> holds = new LinkedHashMap<>();
            boolean failCapture;
            boolean failRelease;

            @Override public boolean available() { return true; }
            @Override public boolean has(UUID p, double amount) { return balance >= amount; }
            @Override public boolean withdraw(UUID p, double amount) { return false; }
            @Override public boolean deposit(UUID p, double amount) { return false; }
            @Override public String format(double amount) { return "$" + amount; }

            @Override
            public boolean reserve(UUID opId, UUID p, double amount) {
                if (holds.containsKey(opId)) return true;
                if (balance < amount) return false;
                balance -= amount;
                holds.put(opId, amount);
                return true;
            }

            @Override
            public boolean capture(UUID opId, UUID p, double amount) {
                if (failCapture) return false;
                return holds.remove(opId) != null;
            }

            @Override
            public boolean release(UUID opId, UUID p, double amount) {
                if (failRelease) return false;
                Double held = holds.remove(opId);
                if (held == null) return false;
                balance += held;
                return true;
            }

            @Override
            public boolean isHeld(UUID opId) {
                return holds.containsKey(opId);
            }
        }

        // Case 1: Card mutation succeeds, capture fails transiently -> receipt is persisted, reconcile captures it!
        FaultyEconomy econ = new FaultyEconomy();
        econ.failCapture = true;
        CuriosRecoveryStore.RollOutcome capturedDeferred = store.executePaidRoll(player, econ, 200, () -> true);
        assertEquals(CuriosRecoveryStore.RollStatus.CAPTURED_DEFERRED, capturedDeferred.status());
        assertTrue(capturedDeferred.applied());
        assertTrue(store.hasAddonReceipt(player, capturedDeferred.operationId()));
        assertTrue(econ.isHeld(capturedDeferred.operationId()));
        assertEquals(1, store.pendingSettlements(player).size());

        // Economy recovers -> reconcile captures using the saved receipt!
        econ.failCapture = false;
        assertEquals(1, store.reconcilePendingRolls(player, econ));
        assertFalse(econ.isHeld(capturedDeferred.operationId()));
        assertTrue(store.pendingSettlements(player).isEmpty());
        assertEquals(800, econ.balance);

        // Case 2: Card mutation fails, release fails transiently -> NO receipt persisted, reconcile releases & refunds!
        econ.failRelease = true;
        CuriosRecoveryStore.RollOutcome releaseDeferred = store.executePaidRoll(player, econ, 300, () -> false);
        assertEquals(CuriosRecoveryStore.RollStatus.RELEASE_DEFERRED, releaseDeferred.status());
        assertFalse(releaseDeferred.applied());
        assertFalse(store.hasAddonReceipt(player, releaseDeferred.operationId()));
        assertTrue(econ.isHeld(releaseDeferred.operationId()));
        assertEquals(500, econ.balance);

        // Economy recovers -> reconcile sees NO receipt and releases the hold, refunding 300!
        econ.failRelease = false;
        assertEquals(1, store.reconcilePendingRolls(player, econ));
        assertFalse(econ.isHeld(releaseDeferred.operationId()));
        assertTrue(store.pendingSettlements(player).isEmpty());
        assertEquals(800, econ.balance);
    }
}
