package codes.castled.allium.portable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NativePlatformPortableTest {

    @TempDir
    Path tempDir;

    @Test
    void portableClassesTargetJava8() throws Exception {
        for (Class<?> type : new Class<?>[]{
                Homes.class, Warps.class, Spawns.class, NativeSettings.class,
                TeleportRequests.class, DirectMessages.class, UtilityMessages.class,
                NativeEconomy.class, NativeEconomyBackend.class, EconomyCraftBridge.class,
                AlliumEconomyApi.class, NativeWorth.class, NativePlayerInfo.class
        }) {
            String res = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getClassLoader().getResourceAsStream(res)) {
                assertNotNull(in, "Missing class " + res);
                DataInputStream data = new DataInputStream(in);
                assertEquals(0xCAFEBABE, data.readInt());
                data.readUnsignedShort();
                int major = data.readUnsignedShort();
                assertTrue(major <= 52, type.getSimpleName() + " must target Java 8 (<=52), got " + major);
            }
        }
    }

    @Test
    void warpsLifecyclePersistsAtomically() {
        Warps warps = new Warps(tempDir);
        List<String> replies = new ArrayList<>();
        Homes.Position shopPos = new Homes.Position("minecraft:overworld", 10.5, 64.0, -22.25, 90.0f, 15.0f);

        assertEquals(0, warps.set("bad name!", shopPos, replies::add));
        assertEquals(1, warps.set("Shop", shopPos, replies::add));
        assertEquals(List.of("shop"), warps.suggestions("sh"));

        AtomicReference<Homes.Position> moved = new AtomicReference<>();
        assertEquals(1, warps.visit("SHOP", dest -> {
            moved.set(dest);
            return true;
        }, replies::add));
        assertNotNull(moved.get());
        assertEquals("minecraft:overworld", moved.get().world);
        assertEquals(10.5, moved.get().x, 1e-6);
        assertEquals(-22.25, moved.get().z, 1e-6);

        assertEquals(1, warps.list(replies::add));
        assertTrue(replies.get(replies.size() - 1).contains("shop"));

        assertEquals(1, warps.delete("shop", replies::add));
        assertTrue(warps.suggestions("").isEmpty());
        assertEquals(0, warps.visit("shop", dest -> true, replies::add));
    }

    @Test
    void spawnsSaveAndFallbackToDefault() {
        Spawns spawns = new Spawns(tempDir);
        Homes.Position fallback = new Homes.Position("minecraft:overworld", 0.5, 64.0, 0.5, 0.0f, 0.0f);
        Homes.Position custom = new Homes.Position("minecraft:the_nether", 12.0, 70.0, 34.0, 180.0f, -5.0f);

        Homes.Position resolvedDefault = spawns.resolve(() -> fallback);
        assertEquals(fallback.world, resolvedDefault.world);

        List<String> replies = new ArrayList<>();
        assertEquals(1, spawns.set(custom, replies::add));
        AtomicReference<Homes.Position> moved = new AtomicReference<>();
        assertEquals(1, spawns.visit(() -> fallback, dest -> {
            moved.set(dest);
            return true;
        }, replies::add));
        assertEquals("minecraft:the_nether", moved.get().world);
        assertEquals(12.0, moved.get().x, 1e-6);
    }

    @Test
    void nativeSettingsAndIgnoresPersist() {
        NativeSettings settings = new NativeSettings(tempDir);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertTrue(settings.get(alice, NativeSettings.Toggle.TPA));
        assertFalse(settings.get(alice, NativeSettings.Toggle.TP_AUTO));
        assertTrue(settings.get(alice, NativeSettings.Toggle.MSG));
        assertFalse(settings.get(alice, NativeSettings.Toggle.REPLY_LAST_RECIPIENT));

        assertTrue(settings.set(alice, NativeSettings.Toggle.TPA, false));
        assertTrue(settings.set(alice, NativeSettings.Toggle.TP_AUTO, true));
        assertTrue(settings.set(alice, NativeSettings.Toggle.MSG, false));
        assertTrue(settings.set(alice, NativeSettings.Toggle.REPLY_LAST_RECIPIENT, true));

        NativeSettings reloaded = new NativeSettings(tempDir);
        assertFalse(reloaded.get(alice, NativeSettings.Toggle.TPA));
        assertTrue(reloaded.get(alice, NativeSettings.Toggle.TP_AUTO));
        assertFalse(reloaded.get(alice, NativeSettings.Toggle.MSG));
        assertTrue(reloaded.get(alice, NativeSettings.Toggle.REPLY_LAST_RECIPIENT));

        assertTrue(reloaded.ignore(alice, bob, "Bob"));
        assertFalse(reloaded.ignore(alice, bob, "Bob"));
        assertTrue(reloaded.isIgnoring(alice, bob));
        assertEquals("Bob", reloaded.ignoredPlayers(alice).get(bob));
        assertTrue(reloaded.unignore(alice, bob));
        assertFalse(reloaded.isIgnoring(alice, bob));
    }

    @Test
    void teleportRequestsExpireAndDirectMessagesTrackReplyMode() {
        AtomicLong clock = new AtomicLong(1_000L);
        TeleportRequests tpa = new TeleportRequests(clock::get, 5_000L);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();

        Homes.Position pos = new Homes.Position("minecraft:overworld", 1, 64, 2, 0, 0);
        tpa.recordBack(alice, pos);
        assertEquals(pos, tpa.backOf(alice));

        tpa.create(alice, bob, false);
        assertEquals(bob, tpa.cancelOutgoing(alice));
        assertNull(tpa.consumeIncoming(bob));

        tpa.create(alice, bob, true);
        clock.addAndGet(6_000L);
        assertNull(tpa.consumeIncoming(bob));

        tpa.create(alice, bob, true);
        TeleportRequests.Request active = tpa.consumeIncoming(bob);
        assertNotNull(active);
        assertTrue(active.here);
        assertEquals(alice, active.sender);

        DirectMessages dm = new DirectMessages();
        dm.recordExchange(alice, bob);
        dm.recordExchange(charlie, alice);
        assertEquals(charlie, dm.replyTarget(alice, false));
        assertEquals(bob, dm.replyTarget(alice, true));
        assertEquals(alice, dm.replyTarget(bob, false));
    }

    @Test
    void utilityMessagesFormatDepthAndCommands() {
        assertTrue(UtilityMessages.depth(63, 63).contains("at sea level"));
        assertTrue(UtilityMessages.depth(70, 63).contains("7 block(s) above"));
        assertTrue(UtilityMessages.depth(50, 63).contains("13 block(s) below"));
        assertTrue(UtilityMessages.COMMANDS.contains("/warp"));
        assertTrue(UtilityMessages.COMMANDS.contains("/tpa"));
        assertTrue(UtilityMessages.COMMANDS.contains("/bal"));
        assertTrue(UtilityMessages.COMMANDS.contains("/sell"));
        assertTrue(UtilityMessages.COMMANDS.contains("/heal"));
    }

    @Test
    void nativeEconomyHandlesShorthandTransfersBaltopAndConfirmations() {
        assertEquals(new BigDecimal("1500.00"), NativeEconomy.parseAmount("$1.5k"));
        assertEquals(new BigDecimal("2000000.00"), NativeEconomy.parseAmount("2m"));
        assertNull(NativeEconomy.parseAmount("-5"));
        assertNull(NativeEconomy.parseAmount("not-a-number"));
        assertEquals("$1,500.00", NativeEconomy.format(new BigDecimal("1500")));

        NativeEconomy eco = new NativeEconomy(tempDir);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertEquals(NativeEconomy.ZERO, eco.balance(alice));
        assertEquals(new BigDecimal("500.00"), eco.addBalance(alice, "Alice", new BigDecimal("500")));
        assertEquals(new BigDecimal("400.00"), eco.takeBalance(alice, "Alice", new BigDecimal("100")));
        assertNull(eco.takeBalance(alice, "Alice", new BigDecimal("999")));

        assertTrue(eco.transfer(alice, "Alice", bob, "Bob", new BigDecimal("150.25")));
        assertFalse(eco.transfer(alice, "Alice", bob, "Bob", new BigDecimal("1000")));

        NativeEconomy reloaded = new NativeEconomy(tempDir);
        assertEquals(new BigDecimal("249.75"), reloaded.balance(alice));
        assertEquals(new BigDecimal("150.25"), reloaded.balance(bob));
        assertEquals(bob, reloaded.findKnownId("bob"));
        assertEquals("Bob", reloaded.nameOf(bob));

        List<NativeEconomy.AccountEntry> top = reloaded.topAccounts();
        assertEquals(2, top.size());
        assertEquals(alice, top.get(0).id);
        assertEquals(bob, top.get(1).id);

        assertTrue(reloaded.acceptsPayments(bob));
        assertTrue(reloaded.setAcceptsPayments(bob, "Bob", false));
        assertFalse(reloaded.acceptsPayments(bob));

        assertFalse(reloaded.requiresPayConfirm(alice));
        assertTrue(reloaded.setRequiresPayConfirm(alice, "Alice", true));
        assertTrue(reloaded.requiresPayConfirm(alice));

        AtomicLong clock = new AtomicLong(1_000L);
        NativeEconomy.PendingConfirmations confirms = new NativeEconomy.PendingConfirmations(clock::get, 30_000L);
        assertFalse(confirms.confirmOrArm(alice, bob, new BigDecimal("50.00")));
        assertTrue(confirms.confirmOrArm(alice, bob, new BigDecimal("50.00")));
        assertFalse(confirms.confirmOrArm(alice, bob, new BigDecimal("50.00")));
        clock.addAndGet(31_000L);
        assertFalse(confirms.confirmOrArm(alice, bob, new BigDecimal("50.00")));

        assertEquals(NativeEconomy.ZERO, reloaded.resetBalance(alice, "Alice"));
        assertEquals(NativeEconomy.ZERO, reloaded.balance(alice));
    }

    @Test
    void nativeWorthNormalizesIdsAndPersistsPrices() {
        assertEquals("minecraft:diamond", NativeWorth.normalizeItemId("diamond"));
        assertEquals("minecraft:diamond", NativeWorth.normalizeItemId("Minecraft:Diamond"));
        assertEquals("modid:custom_gem", NativeWorth.normalizeItemId("modid:custom_gem"));
        assertEquals("", NativeWorth.normalizeItemId("bad item!"));

        NativeWorth worth = new NativeWorth(tempDir);
        assertNull(worth.priceOf("diamond"));
        assertTrue(worth.setPrice("diamond", new BigDecimal("100.5")));
        assertEquals(new BigDecimal("100.50"), new NativeWorth(tempDir).priceOf("minecraft:diamond"));
        assertEquals(List.of("minecraft:diamond"), worth.suggestions("dia"));
        assertTrue(worth.setPrice("minecraft:diamond", BigDecimal.ZERO));
        assertNull(new NativeWorth(tempDir).priceOf("diamond"));
    }

    @Test
    void alliumEconomyApiInteroperatesWithExternalMods() {
        UUID alice = UUID.randomUUID();
        assertEquals(0L, AlliumEconomyApi.getWholeBalance(tempDir, alice));
        assertTrue(AlliumEconomyApi.depositWhole(tempDir, alice, "Alice", 250L));
        assertEquals(250L, AlliumEconomyApi.getWholeBalance(tempDir, alice));
        assertEquals(new BigDecimal("250.00"), AlliumEconomyApi.getBalance(tempDir, alice));
        assertFalse(AlliumEconomyApi.withdrawWhole(tempDir, alice, "Alice", 300L));
        assertTrue(AlliumEconomyApi.withdrawWhole(tempDir, alice, "Alice", 75L));
        assertEquals(175L, AlliumEconomyApi.getWholeBalance(tempDir, alice));
        assertEquals("$175.00", AlliumEconomyApi.formatWhole(175L));
    }

    public static final class FakeEcResult {
        private final boolean ok;
        FakeEcResult(boolean ok) { this.ok = ok; }
        public boolean successful() { return ok; }
    }

    public static final class FakeEcEntry {
        private final UUID id;
        private final long bal;
        FakeEcEntry(UUID id, long bal) { this.id = id; this.bal = bal; }
        public UUID playerId() { return id; }
        public long balance() { return bal; }
    }

    public static final class FakeEcBalances {
        final java.util.Map<UUID, Long> map = new java.util.HashMap<>();
        public long getBalance(UUID id) { return map.getOrDefault(id, 0L); }
        public FakeEcResult addMoney(UUID id, long amount) {
            if (amount <= 0) return new FakeEcResult(false);
            map.put(id, getBalance(id) + amount);
            return new FakeEcResult(true);
        }
        public FakeEcResult removeMoney(UUID id, long amount) {
            long cur = getBalance(id);
            if (amount <= 0 || cur < amount) return new FakeEcResult(false);
            map.put(id, cur - amount);
            return new FakeEcResult(true);
        }
        public FakeEcResult setMoney(UUID id, long amount) {
            if (amount < 0) return new FakeEcResult(false);
            map.put(id, amount);
            return new FakeEcResult(true);
        }
        public FakeEcResult pay(UUID from, UUID to, long amount) {
            if (!removeMoney(from, amount).successful()) return new FakeEcResult(false);
            addMoney(to, amount);
            return new FakeEcResult(true);
        }
    }

    public static final class FakeEcLeaderboard {
        private final FakeEcBalances balances;
        FakeEcLeaderboard(FakeEcBalances balances) { this.balances = balances; }
        public List<FakeEcEntry> getLeaderboardEntries(int limit) {
            List<FakeEcEntry> list = new ArrayList<>();
            for (var e : balances.map.entrySet()) list.add(new FakeEcEntry(e.getKey(), e.getValue()));
            list.sort((a, b) -> Long.compare(b.balance(), a.balance()));
            return list.subList(0, Math.min(limit, list.size()));
        }
    }

    public static final class FakeEcApi {
        final FakeEcBalances balances = new FakeEcBalances();
        final FakeEcLeaderboard leaderboard = new FakeEcLeaderboard(balances);
        public FakeEcBalances balances() { return balances; }
        public FakeEcLeaderboard leaderboard() { return leaderboard; }
        public String formatMoney(long amount) { return amount + " coins"; }
        public String formatMoneyChat(long amount) { return "$" + amount; }
    }

    @Test
    void economyCraftBridgeDelegatesAllOperations() {
        FakeEcApi fakeApi = new FakeEcApi();
        NativeEconomy cache = new NativeEconomy(tempDir);
        EconomyCraftBridge bridge = EconomyCraftBridge.wrap(fakeApi, cache);
        assertNotNull(bridge);
        assertEquals("EconomyCraft", bridge.providerName());
        assertFalse(bridge.supportsDecimals());

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertEquals(new BigDecimal("500.00"), bridge.addBalance(alice, "Alice", new BigDecimal("500")));
        assertTrue(bridge.transfer(alice, "Alice", bob, "Bob", new BigDecimal("125")));
        assertEquals(new BigDecimal("375.00"), bridge.balance(alice));
        assertEquals(new BigDecimal("125.00"), bridge.balance(bob));
        assertEquals("$375", bridge.formatMoney(bridge.balance(alice)));

        List<NativeEconomy.AccountEntry> top = bridge.topAccounts();
        assertEquals(2, top.size());
        assertEquals("Alice", top.get(0).name);
        assertEquals("Bob", top.get(1).name);

        assertEquals(new BigDecimal("300.00"), bridge.takeBalance(alice, "Alice", new BigDecimal("75")));
        assertTrue(bridge.setBalance(bob, "Bob", new BigDecimal("50")));
        assertEquals(new BigDecimal("50.00"), bridge.balance(bob));
        assertEquals(NativeEconomy.ZERO, bridge.resetBalance(bob, "Bob"));
    }

    @Test
    void nativePlayerInfoPersistsMetadataNicknamesPlaytimeAfkAndMail() {
        AtomicLong clock = new AtomicLong(10_000L);
        NativePlayerInfo.SessionState sessions = new NativePlayerInfo.SessionState(clock::get);
        NativePlayerInfo info = new NativePlayerInfo(tempDir);
        UUID alice = UUID.randomUUID();
        Homes.Position pos = new Homes.Position("minecraft:overworld", 42.5, 65.0, -10.0, 0f, 0f);

        sessions.touchSession(alice);
        info.recordPresence(alice, "Alice", pos, clock.get());
        clock.addAndGet(5_000L);
        assertEquals(5_000L, sessions.currentSessionMillis(alice));
        long sessionMs = sessions.finishSession(alice);
        info.addPlaytime(alice, sessionMs);

        NativePlayerInfo reloaded = new NativePlayerInfo(tempDir);
        assertEquals(alice, reloaded.findKnownId("alice"));
        assertEquals("Alice", reloaded.nameOf(alice));
        assertEquals(10_000L, reloaded.lastSeenMillis(alice));
        assertEquals(5_000L, reloaded.savedPlaytimeMillis(alice));
        assertNotNull(reloaded.lastLocation(alice));
        assertEquals(42.5, reloaded.lastLocation(alice).x, 1e-6);

        // Nickname + color helpers
        assertTrue(reloaded.setNickname(alice, "Alice", "&dAli"));
        assertEquals("&dAli", new NativePlayerInfo(tempDir).nicknameOf(alice));
        assertEquals("Ali", NativePlayerInfo.stripColorCodes("&dAli"));
        assertEquals("\u00A7dAli", NativePlayerInfo.translateColorCodes("&dAli"));
        assertTrue(reloaded.setNickname(alice, "Alice", null));
        assertNull(new NativePlayerInfo(tempDir).nicknameOf(alice));

        // AFK toggle
        assertTrue(sessions.toggleAfk(alice, "brb"));
        assertTrue(sessions.isAfk(alice));
        assertEquals("brb", sessions.afkReason(alice));
        assertFalse(sessions.toggleAfk(alice, null));
        assertFalse(sessions.isAfk(alice));

        // Mail
        assertTrue(reloaded.sendMail(alice, "Bob", "Welcome back!"));
        assertTrue(reloaded.sendMail(alice, "Console", "Server notice"));
        List<String> mail = new NativePlayerInfo(tempDir).readMail(alice);
        assertEquals(2, mail.size());
        assertEquals("Bob: Welcome back!", mail.get(0));
        assertEquals("Console: Server notice", mail.get(1));
        assertEquals(2, reloaded.clearMail(alice));
        assertTrue(new NativePlayerInfo(tempDir).readMail(alice).isEmpty());

        assertEquals("1h 1m 5s", NativePlayerInfo.formatDuration(3_665_000L));
    }
}
