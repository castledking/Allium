package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TradeSignServiceTest {

    @TempDir
    Path tempDir;

    private static Player mockPlayer(UUID id, String name, ItemStack[] storage, ItemStack mainHand, String... perms) {
        Player player = mock(Player.class);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inv);
        when(inv.getStorageContents()).thenReturn(storage);
        when(inv.getItemInMainHand()).thenReturn(mainHand);
        doAnswer(call -> {
            int slot = call.getArgument(0);
            ItemStack item = call.getArgument(1);
            storage[slot] = item;
            return null;
        }).when(inv).setItem(anyInt(), any());
        when(inv.addItem(any())).thenAnswer(call -> {
            HashMap<Integer, ItemStack> overflow = new HashMap<>();
            int index = 0;
            for (Object raw : call.getArguments()) {
                ItemStack[] batch = raw instanceof ItemStack[] arr ? arr
                        : (raw instanceof ItemStack single ? new ItemStack[]{single} : new ItemStack[0]);
                for (ItemStack toAdd : batch) {
                    if (toAdd == null || toAdd.getType().isAir() || toAdd.getAmount() <= 0) {
                        continue;
                    }
                    int remaining = toAdd.getAmount();
                    int max = Math.max(1, toAdd.getMaxStackSize());
                    for (int i = 0; i < storage.length && remaining > 0; i++) {
                        if (storage[i] != null && storage[i].getType() == toAdd.getType() && storage[i].getAmount() < max) {
                            int put = Math.min(remaining, max - storage[i].getAmount());
                            storage[i].setAmount(storage[i].getAmount() + put);
                            remaining -= put;
                        }
                    }
                    for (int i = 0; i < storage.length && remaining > 0; i++) {
                        if (storage[i] == null || storage[i].getType().isAir()) {
                            int put = Math.min(remaining, max);
                            storage[i] = new ItemStack(toAdd.getType(), put);
                            remaining -= put;
                        }
                    }
                    if (remaining > 0) {
                        overflow.put(index++, new ItemStack(toAdd.getType(), remaining));
                    }
                }
            }
            return overflow;
        });
        var permSet = java.util.Set.of(perms);
        when(player.hasPermission(anyString())).thenAnswer(call -> permSet.contains(call.getArgument(0)));
        return player;
    }

    @Test
    void moneyForItemBuyCollectRestockAndBreakReclaimWorkEndToEnd() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("allium.db"));
        TradeSignRepository repo = new TradeSignRepository(cs, SqlDialect.SQLITE, System::currentTimeMillis);
        repo.migrate();

        EconomySettings settings = EconomySettings.defaults();
        EconomyEngine engine = new EconomyEngine(cs, settings, (c, from, to) -> true);
        EconomyRuntime runtime = mock(EconomyRuntime.class);
        when(runtime.ready()).thenReturn(true);
        when(runtime.engine()).thenReturn(engine);
        when(runtime.reservations()).thenReturn(engine.reservations());
        when(runtime.synchronous(any(EconomyRequest.class))).thenAnswer(inv -> engine.execute(inv.getArgument(0)));

        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        ModuleManager modules = mock(ModuleManager.class);
        EconomyModule economyModule = mock(EconomyModule.class);
        MessageManager messages = mock(MessageManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        YamlConfiguration config = new YamlConfiguration();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getConfig()).thenReturn(config);
        when(scheduler.isServerThread()).thenReturn(true);
        when(scheduler.runOnServer(any())).thenAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return TaskHandle.NONE; });
        when(modules.getModule("economy")).thenReturn(economyModule);
        when(economyModule.isEnabled()).thenReturn(true);
        when(economyModule.runtime()).thenReturn(runtime);

        UUID ownerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();

        // Seed economy accounts: buyer has $100, owner has $10
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open-buyer", EconomyRequest.Kind.OPEN_IMPORTED, "test",
                buyerId, null, new Money(10_000))));
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open-owner", EconomyRequest.Kind.OPEN_IMPORTED, "test",
                ownerId, null, new Money(1_000))));

        ItemStack[] ownerStorage = new ItemStack[4];
        ownerStorage[0] = new ItemStack(Material.COAL, 16);
        ItemStack[] buyerStorage = new ItemStack[4];
        ItemStack[] strangerStorage = new ItemStack[4];

        Player owner = mockPlayer(ownerId, "Alice", ownerStorage, new ItemStack(Material.AIR), "allium.signs.break.trade");
        Player buyer = mockPlayer(buyerId, "Bob", buyerStorage, new ItemStack(Material.AIR));
        Player stranger = mockPlayer(strangerId, "Eve", strangerStorage, new ItemStack(Material.AIR));
        when(server.getPlayer(ownerId)).thenReturn(owner);
        when(server.getPlayer(buyerId)).thenReturn(buyer);
        when(server.getPlayer(strangerId)).thenReturn(stranger);

        Block block = mock(Block.class);
        World world = mock(World.class);
        Sign sign = mock(Sign.class);
        SignSide face = mock(SignSide.class);
        when(block.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(block.getState()).thenReturn(sign);
        when(sign.getSide(Side.FRONT)).thenReturn(face);

        UUID signId = UUID.randomUUID();
        repo.create(new TradeSignRepository.TradeSignRecord(
                signId, ownerId, "Alice", "world", 0, 64, 0, "FRONT",
                TradeSignSpec.Kind.MONEY, "", 5_000L,
                TradeSignSpec.Kind.ITEM, "coal", 16L,
                16L, 0L, 1L, 1_000L
        ));
        SignIdentity identity = new SignIdentity(signId, SignIdentity.Type.TRADE,
                TradeSignSpec.encodeArgument(ownerId, TradeSignSpec.Offer.money(new Money(5_000)),
                        TradeSignSpec.Offer.item("coal", 16)),
                false, new Money(0), false);

        TradeSignService service = new TradeSignService(plugin, repo, Runnable::run);

        // 1. Buyer purchases 16 coal for $50 -> buyer gets 16 coal, pays $50, stock becomes 0, earned becomes $50
        service.interact(buyer, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.COAL, 1));
        assertEquals(16, PaidSignService.countMatching(buyerStorage, new ItemStack(Material.COAL, 1)));
        var buyerBal = assertInstanceOf(EconomyResult.Found.class, engine.balance(buyerId));
        assertEquals(5_000L, buyerBal.book().minor());
        var afterBuy = repo.load(signId).orElseThrow();
        assertEquals(0L, afterBuy.stockAmount());
        assertEquals(5_000L, afterBuy.earnedAmount());

        // 2. Second purchase fails out-of-stock without charging buyer
        service.interact(buyer, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.COAL, 1));
        verify(messages).send(buyer, Messages.SIGN_TRADE_OUT_OF_STOCK);
        assertEquals(5_000L, assertInstanceOf(EconomyResult.Found.class, engine.balance(buyerId)).book().minor());

        // 3. Owner collects $50 earnings -> owner balance increases from $10 to $60
        when(owner.isSneaking()).thenReturn(false);
        service.interact(owner, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.COAL, 1));
        assertEquals(6_000L, assertInstanceOf(EconomyResult.Found.class, engine.balance(ownerId)).book().minor());
        assertEquals(0L, repo.load(signId).orElseThrow().earnedAmount());

        // 4. Owner sneaks + right-clicks to restock 16 coal from inventory
        when(owner.isSneaking()).thenReturn(true);
        service.interact(owner, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.COAL, 1));
        assertEquals(16L, repo.load(signId).orElseThrow().stockAmount());
        assertEquals(0, PaidSignService.countMatching(ownerStorage, new ItemStack(Material.COAL, 1)));

        // 5. Stranger cannot break owner's trade sign
        assertFalse(service.handleBreak(stranger, identity, s -> new ItemStack(Material.COAL, 1)));
        verify(messages).send(stranger, Messages.SIGN_TRADE_NOT_OWNER);
        assertTrue(repo.load(signId).isPresent());

        // 6. Owner breaking sign reclaims the 16 stocked coal and removes the repository row
        assertTrue(service.handleBreak(owner, identity, s -> new ItemStack(Material.COAL, 1)));
        assertEquals(16, PaidSignService.countMatching(ownerStorage, new ItemStack(Material.COAL, 1)));
        assertTrue(repo.load(signId).isEmpty());
    }

    @Test
    void barterTradeSignSwapsItemsWithoutEconomy() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("barter.db"));
        TradeSignRepository repo = new TradeSignRepository(cs, SqlDialect.SQLITE, System::currentTimeMillis);
        repo.migrate();

        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        MessageManager messages = mock(MessageManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.isServerThread()).thenReturn(true);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());

        UUID ownerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        ItemStack[] buyerStorage = new ItemStack[]{new ItemStack(Material.DIAMOND, 2), null, null, null};
        Player buyer = mockPlayer(buyerId, "Bob", buyerStorage, new ItemStack(Material.AIR));
        when(server.getPlayer(buyerId)).thenReturn(buyer);

        Block block = mock(Block.class);
        World world = mock(World.class);
        Sign sign = mock(Sign.class);
        SignSide face = mock(SignSide.class);
        when(block.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(block.getState()).thenReturn(sign);
        when(sign.getSide(Side.FRONT)).thenReturn(face);

        UUID signId = UUID.randomUUID();
        repo.create(new TradeSignRepository.TradeSignRecord(
                signId, ownerId, "Alice", "world", 0, 64, 0, "FRONT",
                TradeSignSpec.Kind.ITEM, "diamond", 1L,
                TradeSignSpec.Kind.ITEM, "iron_ingot", 8L,
                16L, 0L, 1L, 1_000L
        ));
        SignIdentity identity = new SignIdentity(signId, SignIdentity.Type.TRADE,
                TradeSignSpec.encodeArgument(ownerId, TradeSignSpec.Offer.item("diamond", 1),
                        TradeSignSpec.Offer.item("iron_ingot", 8)),
                false, new Money(0), false);

        TradeSignService service = new TradeSignService(plugin, repo, Runnable::run);
        service.interact(buyer, block, Side.FRONT, identity, () -> true,
                spec -> "diamond".equals(spec) ? new ItemStack(Material.DIAMOND, 1) : new ItemStack(Material.IRON_INGOT, 1));

        assertEquals(1, PaidSignService.countMatching(buyerStorage, new ItemStack(Material.DIAMOND, 1)));
        assertEquals(8, PaidSignService.countMatching(buyerStorage, new ItemStack(Material.IRON_INGOT, 1)));
        var after = repo.load(signId).orElseThrow();
        assertEquals(8L, after.stockAmount());
        assertEquals(1L, after.earnedAmount());

        // Breaking a barter sign with two distinct item balances requires room for both
        ItemStack[] crampedOwnerStorage = new ItemStack[]{null};
        Player crampedOwner = mockPlayer(ownerId, "Alice", crampedOwnerStorage, new ItemStack(Material.AIR), "allium.signs.break.trade");
        assertFalse(service.handleBreak(crampedOwner, identity,
                spec -> "diamond".equals(spec) ? new ItemStack(Material.DIAMOND, 1) : new ItemStack(Material.IRON_INGOT, 1)));
        assertTrue(repo.load(signId).isPresent());

        ItemStack[] roomyOwnerStorage = new ItemStack[]{null, null};
        Player roomyOwner = mockPlayer(ownerId, "Alice", roomyOwnerStorage, new ItemStack(Material.AIR), "allium.signs.break.trade");
        assertTrue(service.handleBreak(roomyOwner, identity,
                spec -> "diamond".equals(spec) ? new ItemStack(Material.DIAMOND, 1) : new ItemStack(Material.IRON_INGOT, 1)));
        assertEquals(8, PaidSignService.countMatching(roomyOwnerStorage, new ItemStack(Material.IRON_INGOT, 1)));
        assertEquals(1, PaidSignService.countMatching(roomyOwnerStorage, new ItemStack(Material.DIAMOND, 1)));
        assertTrue(repo.load(signId).isEmpty());
    }

    @Test
    void itemForMoneySellFundPartialCollectAndBreakReclaimWorkEndToEnd() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("sell-trade.db"));
        TradeSignRepository repo = new TradeSignRepository(cs, SqlDialect.SQLITE, System::currentTimeMillis);
        repo.migrate();

        EconomySettings settings = EconomySettings.defaults();
        EconomyEngine engine = new EconomyEngine(cs, settings, (c, from, to) -> true);
        EconomyRuntime runtime = mock(EconomyRuntime.class);
        when(runtime.ready()).thenReturn(true);
        when(runtime.engine()).thenReturn(engine);
        when(runtime.reservations()).thenReturn(engine.reservations());
        when(runtime.synchronous(any(EconomyRequest.class))).thenAnswer(inv -> engine.execute(inv.getArgument(0)));

        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        ModuleManager modules = mock(ModuleManager.class);
        EconomyModule economyModule = mock(EconomyModule.class);
        MessageManager messages = mock(MessageManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        AlliumLogger logger = mock(AlliumLogger.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(scheduler.isServerThread()).thenReturn(true);
        when(scheduler.runOnServer(any())).thenAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return TaskHandle.NONE; });
        when(modules.getModule("economy")).thenReturn(economyModule);
        when(economyModule.isEnabled()).thenReturn(true);
        when(economyModule.runtime()).thenReturn(runtime);

        UUID ownerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open-owner", EconomyRequest.Kind.OPEN_IMPORTED, "test",
                ownerId, null, new Money(10_000))));
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(engine.request(
                UUID.randomUUID(), "test", "open-seller", EconomyRequest.Kind.OPEN_IMPORTED, "test",
                sellerId, null, new Money(500))));

        // Owner has only 1 free slot (max 64 oak_log); seller has 128 oak_log
        ItemStack[] ownerStorage = new ItemStack[]{null};
        ItemStack[] sellerStorage = new ItemStack[]{
                new ItemStack(Material.OAK_LOG, 64),
                new ItemStack(Material.OAK_LOG, 64)
        };
        Player owner = mockPlayer(ownerId, "Alice", ownerStorage, new ItemStack(Material.AIR), "allium.signs.break.trade");
        Player seller = mockPlayer(sellerId, "Bob", sellerStorage, new ItemStack(Material.AIR));
        when(server.getPlayer(ownerId)).thenReturn(owner);
        when(server.getPlayer(sellerId)).thenReturn(seller);

        Block block = mock(Block.class);
        World world = mock(World.class);
        Sign sign = mock(Sign.class);
        SignSide face = mock(SignSide.class);
        when(block.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(block.getState()).thenReturn(sign);
        when(sign.getSide(Side.FRONT)).thenReturn(face);

        // Sign buys 64 oak_log for $25 (stock initially $25)
        UUID signId = UUID.randomUUID();
        repo.create(new TradeSignRepository.TradeSignRecord(
                signId, ownerId, "Alice", "world", 0, 64, 0, "FRONT",
                TradeSignSpec.Kind.ITEM, "oak_log", 64L,
                TradeSignSpec.Kind.MONEY, "", 2_500L,
                2_500L, 0L, 1L, 1_000L
        ));
        SignIdentity identity = new SignIdentity(signId, SignIdentity.Type.TRADE,
                TradeSignSpec.encodeArgument(ownerId, TradeSignSpec.Offer.item("oak_log", 64),
                        TradeSignSpec.Offer.money(new Money(2_500))),
                false, new Money(0), false);

        TradeSignService service = new TradeSignService(plugin, repo, Runnable::run);

        // 1. Seller sells 64 oak_log -> gets $25, stock becomes $0, earned becomes 64 oak_log
        service.interact(seller, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.OAK_LOG, 1));
        assertEquals(64, PaidSignService.countMatching(sellerStorage, new ItemStack(Material.OAK_LOG, 1)));
        assertEquals(3_000L, assertInstanceOf(EconomyResult.Found.class, engine.balance(sellerId)).book().minor());
        assertEquals(0L, repo.load(signId).orElseThrow().stockAmount());
        assertEquals(64L, repo.load(signId).orElseThrow().earnedAmount());

        // 2. Owner sneaks + right-clicks to fund another $25 (x2 -> $50 via 2 sneaks)
        when(owner.isSneaking()).thenReturn(true);
        service.interact(owner, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.OAK_LOG, 1));
        service.interact(owner, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.OAK_LOG, 1));
        assertEquals(5_000L, repo.load(signId).orElseThrow().stockAmount());
        assertEquals(5_000L, assertInstanceOf(EconomyResult.Found.class, engine.balance(ownerId)).book().minor());

        // 3. Seller sells remaining 64 oak_log -> gets $25, stock becomes $25, earned becomes 128 oak_log
        service.interact(seller, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.OAK_LOG, 1));
        assertEquals(0, PaidSignService.countMatching(sellerStorage, new ItemStack(Material.OAK_LOG, 1)));
        assertEquals(5_500L, assertInstanceOf(EconomyResult.Found.class, engine.balance(sellerId)).book().minor());
        assertEquals(2_500L, repo.load(signId).orElseThrow().stockAmount());
        assertEquals(128L, repo.load(signId).orElseThrow().earnedAmount());

        // 4. Owner collects with only 1 free slot -> collects 64 oak_log, 64 remain in sign!
        when(owner.isSneaking()).thenReturn(false);
        service.interact(owner, block, Side.FRONT, identity, () -> true, s -> new ItemStack(Material.OAK_LOG, 1));
        assertEquals(64, PaidSignService.countMatching(ownerStorage, new ItemStack(Material.OAK_LOG, 1)));
        assertEquals(64L, repo.load(signId).orElseThrow().earnedAmount());

        // 5. Clear owner inventory, break sign -> reclaims remaining 64 oak_log AND remaining $25 stock!
        ownerStorage[0] = null;
        assertTrue(service.handleBreak(owner, identity, s -> new ItemStack(Material.OAK_LOG, 1)));
        assertEquals(64, PaidSignService.countMatching(ownerStorage, new ItemStack(Material.OAK_LOG, 1)));
        assertEquals(7_500L, assertInstanceOf(EconomyResult.Found.class, engine.balance(ownerId)).book().minor());
        assertTrue(repo.load(signId).isEmpty());
    }
}
