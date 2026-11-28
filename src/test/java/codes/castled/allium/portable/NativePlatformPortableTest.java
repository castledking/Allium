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
                NativeEconomy.class, NativeWorth.class
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
}
