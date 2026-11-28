package codes.castled.allium.legacy;

import codes.castled.allium.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyCreativeManagerTest {

    @TempDir
    Path tempDir;

    private Server previousBukkitServer;

    @AfterEach
    void restoreBukkitServer() throws Exception {
        setBukkitServer(previousBukkitServer);
    }

    private static void setBukkitServer(Server server) throws Exception {
        var f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);
    }

    private record Harness(
            JavaPlugin plugin,
            Server server,
            World world,
            LegacyBukkitRuntime runtime,
            List<Player> online,
            LegacyCreativeManager manager,
            LegacyCreativeManage manage
    ) {
        Player addPlayer(String name, UUID id, boolean op, GameMode mode) {
            Player p = mock(Player.class);
            PlayerInventory inv = mock(PlayerInventory.class);
            ItemStack[][] storage = new ItemStack[][]{new ItemStack[41]};
            ItemStack[][] armor = new ItemStack[][]{new ItemStack[4]};
            when(p.getName()).thenReturn(name);
            when(p.getDisplayName()).thenReturn(name);
            when(p.getUniqueId()).thenReturn(id);
            when(p.getServer()).thenReturn(server);
            when(p.isOnline()).thenReturn(true);
            when(p.canSee(any(Player.class))).thenReturn(true);
            when(p.isOp()).thenReturn(op);
            when(p.hasPermission(anyString())).thenReturn(op);
            when(p.getWorld()).thenReturn(world);
            when(p.getLocation()).thenReturn(new Location(world, 10.0, 64.0, 20.0));
            when(p.getGameMode()).thenReturn(mode);
            when(p.getActivePotionEffects()).thenReturn(Collections.emptyList());
            when(p.getInventory()).thenReturn(inv);
            when(inv.getContents()).thenAnswer(i -> storage[0]);
            doAnswer(i -> {
                storage[0] = i.getArgument(0);
                return null;
            }).when(inv).setContents(any(ItemStack[].class));
            when(inv.getArmorContents()).thenAnswer(i -> armor[0]);
            doAnswer(i -> {
                armor[0] = i.getArgument(0);
                return null;
            }).when(inv).setArmorContents(any(ItemStack[].class));
            online.add(p);
            when(server.getPlayerExact(name)).thenReturn(p);
            when(server.getPlayer(name)).thenReturn(p);
            when(server.getPlayer(id)).thenReturn(p);
            when(server.getOfflinePlayer(name)).thenReturn(p);
            when(server.getOfflinePlayer(id)).thenReturn(p);
            return p;
        }

        boolean run(Player sender, String cmdName, String... args) {
            Command cmd = mock(Command.class);
            when(cmd.getName()).thenReturn(cmdName);
            return runtime.onCommand(sender, cmd, cmdName, args);
        }

        List<String> complete(Player sender, String cmdName, String... args) {
            Command cmd = mock(Command.class);
            when(cmd.getName()).thenReturn(cmdName);
            return runtime.onTabComplete(sender, cmd, cmdName, args);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private Harness createHarness() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(itemFactory.getItemMeta(any(Material.class))).thenAnswer(i -> mock(ItemMeta.class));
        when(itemFactory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
        when(itemFactory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenAnswer(i -> i.getArgument(0));
        when(unsafe.getDataVersion()).thenReturn(1343);
        when(server.getItemFactory()).thenReturn(itemFactory);
        when(server.getUnsafe()).thenReturn(unsafe);
        try {
            previousBukkitServer = Bukkit.getServer();
            setBukkitServer(server);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        PluginManager pm = mock(PluginManager.class);
        World world = mock(World.class);
        PluginDescriptionFile desc = new PluginDescriptionFile("Allium", "1.0.2", "codes.castled.allium.AlliumBootstrap");
        List<Player> online = new ArrayList<>();

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDescription()).thenReturn(desc);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LegacyCreativeManagerTest"));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getName()).thenReturn("Spigot");
        when(server.getVersion()).thenReturn("git-Spigot-1.12.2");
        when(server.getBukkitVersion()).thenReturn("1.12.2-R0.1-SNAPSHOT");
        when(server.getMaxPlayers()).thenReturn(100);
        when(server.getOnlinePlayers()).thenAnswer(inv -> (Collection) online);
        when(server.getWorlds()).thenReturn(Collections.singletonList(world));
        when(server.getWorld("world")).thenReturn(world);
        when(server.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(inv -> {
            Inventory menuInv = mock(Inventory.class);
            when(menuInv.getHolder()).thenReturn(inv.getArgument(0));
            when(menuInv.getSize()).thenReturn(inv.getArgument(1));
            return menuInv;
        });
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMaxHeight()).thenReturn(256);
        when(world.getSpawnLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0));
        when(world.getPlayers()).thenAnswer(inv -> online);

        LegacyBukkitRuntime runtime = new LegacyBukkitRuntime(plugin, ServerVersion.parse("1.12.2-R0.1-SNAPSHOT"), 8);
        runtime.onEnable();

        var captor = ArgumentCaptor.forClass(org.bukkit.event.Listener.class);
        verify(pm, atLeastOnce()).registerEvents(captor.capture(), eq(plugin));
        LegacyCreativeManager mgr = captor.getAllValues().stream()
                .filter(LegacyCreativeManager.class::isInstance)
                .map(LegacyCreativeManager.class::cast)
                .findFirst().orElseThrow();
        LegacyCreativeManage manage = captor.getAllValues().stream()
                .filter(LegacyCreativeManage.class::isInstance)
                .map(LegacyCreativeManage.class::cast)
                .findFirst().orElseThrow();

        return new Harness(plugin, server, world, runtime, online, mgr, manage);
    }

    @Test
    void creativeLegacyClassesCompileToJava8Bytecode() throws Exception {
        for (String name : List.of(
                "codes.castled.allium.legacy.LegacyCreativeManager",
                "codes.castled.allium.legacy.LegacyCreativeManage")) {
            Class<?> clazz = Class.forName(name);
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = clazz.getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "Missing classfile for " + name);
                DataInputStream dis = new DataInputStream(in);
                assertEquals(0xCAFEBABE, dis.readInt());
                dis.readUnsignedShort();
                int major = dis.readUnsignedShort();
                assertTrue(major <= 52, name + " must target Java 8 (major <= 52), got " + major);
            }
        }
    }

    @Test
    void creativeRestrictionsBlacklistsAndSpectatorTeleportAreEnforced() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "modules:",
                "  creative: true",
                "creative-manager:",
                "  message-cooldown-seconds: 0",
                "  blacklist:",
                "    blocks: [BEDROCK]",
                "    items: [LAVA_BUCKET]",
                "    commands: [give]"
        ));
        Harness h = createHarness();
        Player builder = h.addPlayer("Builder", UUID.randomUUID(), false, GameMode.CREATIVE);
        Player op = h.addPlayer("OpStaff", UUID.randomUUID(), true, GameMode.CREATIVE);

        Block stone = mockBlock(h.world(), 5, 64, 5, Material.STONE);
        Block bedrock = mockBlock(h.world(), 6, 64, 5, Material.BEDROCK);

        // Restricted creative player cannot place, break, drop, pick up, or open chests
        BlockPlaceEvent placeDenied = new BlockPlaceEvent(stone, stone.getState(), stone, new ItemStack(Material.STONE), builder, true, EquipmentSlot.HAND);
        h.manager().onPlace(placeDenied);
        assertTrue(placeDenied.isCancelled());

        BlockBreakEvent breakDenied = new BlockBreakEvent(stone, builder);
        h.manager().onBreak(breakDenied);
        assertTrue(breakDenied.isCancelled());

        PlayerDropItemEvent dropDenied = new PlayerDropItemEvent(builder, mock(Item.class));
        h.manager().onDrop(dropDenied);
        assertTrue(dropDenied.isCancelled());

        Item droppedItem = mock(Item.class);
        when(droppedItem.getLocation()).thenReturn(new Location(h.world(), 10, 64, 20));
        EntityPickupItemEvent pickupDenied = new EntityPickupItemEvent(builder, droppedItem, 0);
        h.manager().onPickup(pickupDenied);
        assertTrue(pickupDenied.isCancelled());

        Inventory chest = mock(Inventory.class);
        when(chest.getType()).thenReturn(InventoryType.CHEST);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(builder);
        when(view.getTopInventory()).thenReturn(chest);
        InventoryOpenEvent openDenied = new InventoryOpenEvent(view);
        h.manager().onOpen(openDenied);
        assertTrue(openDenied.isCancelled());

        // Grant place/break -> normal stone allowed, blacklisted bedrock still blocked
        when(builder.hasPermission("allium.gamemode.creative.place")).thenReturn(true);
        when(builder.hasPermission("allium.gamemode.creative.break")).thenReturn(true);

        BlockPlaceEvent stoneAllowed = new BlockPlaceEvent(stone, stone.getState(), stone, new ItemStack(Material.STONE), builder, true, EquipmentSlot.HAND);
        h.manager().onPlace(stoneAllowed);
        assertFalse(stoneAllowed.isCancelled());

        BlockPlaceEvent bedrockBlocked = new BlockPlaceEvent(bedrock, bedrock.getState(), bedrock, new ItemStack(Material.BEDROCK), builder, true, EquipmentSlot.HAND);
        h.manager().onPlace(bedrockBlocked);
        assertTrue(bedrockBlocked.isCancelled());

        // Blacklisted command blocked for non-blacklist-bypass, allowed for OP
        PlayerCommandPreprocessEvent giveBlocked = new PlayerCommandPreprocessEvent(builder, "/minecraft:give Builder diamond");
        h.manager().onCommand(giveBlocked);
        assertTrue(giveBlocked.isCancelled());

        PlayerCommandPreprocessEvent giveOp = new PlayerCommandPreprocessEvent(op, "/give OpStaff diamond");
        h.manager().onCommand(giveOp);
        assertFalse(giveOp.isCancelled());

        // Spectator teleport blocked without permission
        PlayerTeleportEvent specTp = new PlayerTeleportEvent(builder, builder.getLocation(), builder.getLocation(), PlayerTeleportEvent.TeleportCause.SPECTATE);
        h.manager().onSpectatorTeleport(specTp);
        assertTrue(specTp.isCancelled());

        h.runtime().onDisable();
    }

    @Test
    void separateInventoriesSwapAndPlacedBlocksDropNothingInSurvival() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "modules:",
                "  creative: true",
                "creative-manager:",
                "  separate-inventories: true",
                "  armor:",
                "    enabled: false",
                "  placed-blocks:",
                "    enabled: true",
                "    survival-break: no-drops"
        ));
        Harness h = createHarness();
        Player builder = h.addPlayer("Builder", UUID.randomUUID(), false, GameMode.SURVIVAL);
        Player survivalist = h.addPlayer("Miner", UUID.randomUUID(), false, GameMode.SURVIVAL);
        when(builder.hasPermission("allium.gamemode.creative.place")).thenReturn(true);

        ItemStack[] survivalItems = new ItemStack[41];
        survivalItems[0] = new ItemStack(Material.DIAMOND_PICKAXE);
        builder.getInventory().setContents(survivalItems);

        // Switch Survival -> Creative swaps inventory to empty creative stash
        PlayerGameModeChangeEvent toCreative = new PlayerGameModeChangeEvent(builder, GameMode.CREATIVE);
        h.manager().prepareSwap(toCreative);
        h.manager().applySwap(toCreative);
        assertNull(builder.getInventory().getContents()[0], "Survival pickaxe must be stashed when entering creative");

        // Place block in creative -> marked as creative-placed
        when(builder.getGameMode()).thenReturn(GameMode.CREATIVE);
        Block diamondBlock = mockBlock(h.world(), 12, 64, 12, Material.DIAMOND_BLOCK);
        BlockPlaceEvent placed = new BlockPlaceEvent(diamondBlock, diamondBlock.getState(), diamondBlock, new ItemStack(Material.DIAMOND_BLOCK), builder, true, EquipmentSlot.HAND);
        h.manager().onPlace(placed);
        h.manager().onPlacedMonitor(placed);
        assertTrue(h.manager().isMarkedPlaced(diamondBlock));

        // Survival miner breaking creative-placed block gets no drops and 0 XP
        BlockBreakEvent mined = new BlockBreakEvent(diamondBlock, survivalist);
        mined.setDropItems(true);
        mined.setExpToDrop(10);
        h.manager().onBreak(mined);
        assertFalse(mined.isCancelled());
        assertFalse(mined.isDropItems(), "Creative-placed block must drop nothing in survival");
        assertEquals(0, mined.getExpToDrop());
        h.manager().onBrokenMonitor(mined);
        assertFalse(h.manager().isMarkedPlaced(diamondBlock));

        // Switch Creative -> Survival restores original diamond pickaxe
        PlayerGameModeChangeEvent backToSurvival = new PlayerGameModeChangeEvent(builder, GameMode.SURVIVAL);
        h.manager().prepareSwap(backToSurvival);
        h.manager().applySwap(backToSurvival);
        assertNotNull(builder.getInventory().getContents()[0]);
        assertEquals(Material.DIAMOND_PICKAXE, builder.getInventory().getContents()[0].getType());

        h.runtime().onDisable();
    }

    @Test
    void creativeManagerCommandCheckListGuiAndTabCompleteWork() {
        Harness h = createHarness();
        Player admin = h.addPlayer("Admin", UUID.randomUUID(), true, GameMode.SURVIVAL);
        Player user = h.addPlayer("Alice", UUID.randomUUID(), false, GameMode.CREATIVE);
        when(user.hasPermission("allium.gamemode.creative.place")).thenReturn(true);

        // /creativemanager player Alice check place
        assertTrue(h.run(admin, "creativemanager", "player", "Alice", "check", "place"));
        verify(admin).sendMessage(argThat((String msg) -> msg.contains("place") && msg.contains("allowed")));

        // /allium creativemanager player Alice list
        clearInvocations(admin);
        assertTrue(h.run(admin, "allium", "creativemanager", "player", "Alice", "list"));
        verify(admin).sendMessage(argThat((String msg) -> msg.contains("Alice") && msg.contains("place")));

        // /creativemanager player Alice add break (no LP/Vault -> read-only unavailable)
        clearInvocations(admin);
        assertTrue(h.run(admin, "creativemanager", "player", "Alice", "add", "break"));
        verify(admin).sendMessage(argThat((String msg) -> msg.contains("LuckPerms") || msg.contains("Vault")));

        // /creativemanager opens 54-slot GUI
        clearInvocations(admin);
        assertTrue(h.run(admin, "creativemanager"));
        verify(admin).openInventory(any(Inventory.class));

        // Tab completion
        List<String> rootComplete = h.complete(admin, "creativemanager", "");
        assertTrue(rootComplete.containsAll(List.of("gui", "active", "player", "group")));
        List<String> flagComplete = h.complete(admin, "creativemanager", "player", "Alice", "check", "pl");
        assertTrue(flagComplete.contains("place"));

        h.runtime().onDisable();
    }

    private static Block mockBlock(World world, int x, int y, int z, Material type) {
        Block block = mock(Block.class);
        org.bukkit.block.BlockState state = mock(org.bukkit.block.BlockState.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(type);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        when(block.getState()).thenReturn(state);
        when(state.getType()).thenReturn(type);
        return block;
    }
}
