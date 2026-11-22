package codes.castled.allium.parity;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.kits.KitCommand;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.GiveSpec;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.kit.KitDefinition;
import codes.castled.allium.kit.KitDefinitionLoader;
import codes.castled.allium.kit.KitRepository;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.RequestTracker;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sections11To13ParityTest {

    @TempDir Path tempDir;

    private PluginStart plugin;
    private Server server;
    private PluginManager pluginManager;
    private ModuleManager moduleManager;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private YamlConfiguration config;
    private World world;
    private List<Player> online;
    private List<String> sent;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        moduleManager = mock(ModuleManager.class);
        scheduler = mock(TaskScheduler.class);
        config = new YamlConfiguration();
        config.set("teleport.warmup-seconds", 0);
        world = mock(World.class);
        online = new ArrayList<>();
        sent = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getModuleManager()).thenReturn(moduleManager);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        org.bukkit.permissions.Permission wildcardSpy = spy(new org.bukkit.permissions.Permission("allium.kits.*", org.bukkit.permissions.PermissionDefault.OP));
        org.mockito.Mockito.doNothing().when(wildcardSpy).recalculatePermissibles();
        when(pluginManager.getPermission("allium.kits.*")).thenReturn(wildcardSpy);
        when(server.getOnlinePlayers()).thenAnswer(inv -> new ArrayList<>(online));
        when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return online.stream().filter(p -> p.getUniqueId().equals(id)).findFirst().orElse(null);
        });
        when(server.getPlayerExact(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return online.stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        });
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(server.getWorld(any(UUID.class))).thenReturn(world);

        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(scheduler).runOnServer(any(Runnable.class));
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        when(scheduler.serverExecutor()).thenReturn(Runnable::run);

        LanguageManager lang = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(lang.get(m)).thenReturn(m.getDefault());
            when(lang.get(m.getKey(), m.getDefault())).thenReturn(m.getDefault());
        }
        messages = spy(new MessageManager(plugin, lang));
        when(plugin.getMessageManager()).thenReturn(messages);
    }

    private Player mockPlayer(String name, Set<String> perms) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.canSee(any(Player.class))).thenReturn(true);
        when(p.getMetadata(anyString())).thenReturn(List.of());
        when(p.getLocation()).thenReturn(new Location(world, 10.5, 64.0, 10.5, 90f, 0f));
        when(p.getWorld()).thenReturn(world);
        when(p.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        when(p.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        doAnswer(inv -> { sent.add(name + ":" + inv.getArgument(0)); return null; }).when(p).sendMessage(anyString());
        online.add(p);
        return p;
    }

    private static CommandSpec findSpec(List<CommandSpec> specs, String name) {
        return specs.stream().filter(s -> s.canonicalName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void section11And13AliasesAndSpecsAreWired() {
        assertEquals("on", CoreCommandSpecs.payToggleAlias("payon"));
        assertEquals("off", CoreCommandSpecs.payToggleAlias("payoff"));
        assertEquals("on", CoreCommandSpecs.payConfirmAlias("payconfirmon"));
        assertEquals("off", CoreCommandSpecs.payConfirmAlias("payconfirmoff"));

        assertTrue(findSpec(CoreCommandSpecs.ECONOMY_SPECS, "paytoggle").aliases().containsAll(List.of("payon", "payoff")));
        assertTrue(findSpec(CoreCommandSpecs.ECONOMY_SPECS, "payconfirmtoggle").aliases()
                .containsAll(List.of("payconfirm", "payconfirmon", "payconfirmoff")));

        assertTrue(CoreCommandSpecs.SHOWKIT.aliases().containsAll(List.of("kitpreview", "preview", "kitshow")));
        assertTrue(CoreCommandSpecs.KITRESET.aliases().containsAll(List.of("kitr", "resetkit")));

        assertTrue(findSpec(CoreCommandSpecs.TELEPORT_SPECS, "tp").aliases().containsAll(List.of("tp2p", "tele")));
        assertTrue(findSpec(CoreCommandSpecs.TELEPORT_SPECS, "tpaccept").aliases().contains("tpyes"));
        assertTrue(findSpec(CoreCommandSpecs.TELEPORT_SPECS, "tpdeny").aliases().contains("tpno"));
        assertTrue(findSpec(CoreCommandSpecs.TELEPORT_SPECS, "jump").aliases().containsAll(List.of("j", "jumpto")));
    }

    @Test
    void showkitOpensReadOnlyGuiForPlayerAndListsItemsForConsoleAndKitresetClearsCooldown() throws Exception {
        KitRepository repo = mock(KitRepository.class);
        KitDefinitionLoader loader = mock(KitDefinitionLoader.class);
        ItemSpecParser parser = mock(ItemSpecParser.class);
        ItemLibrary library = mock(ItemLibrary.class);
        ItemDeliveryService delivery = mock(ItemDeliveryService.class);

        KitDefinition starter = new KitDefinition("starter", true, 3600L, false, "0.00",
                List.of("minecraft:stone 16"), "kits/starter.yml");
        when(loader.load(parser)).thenReturn(new KitDefinitionLoader.Loaded(Map.of("starter", starter), List.of()));
        GiveSpec spec = mock(GiveSpec.class);
        when(parser.parse("minecraft:stone 16")).thenReturn(spec);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.STONE);
        when(stack.getAmount()).thenReturn(16);
        when(stack.clone()).thenReturn(stack);
        when(stack.hasItemMeta()).thenReturn(false);
        ItemLibrary.PreparedItem preparedItem = mock(ItemLibrary.PreparedItem.class);
        when(preparedItem.stack()).thenReturn(stack);
        when(preparedItem.skull()).thenReturn(null);
        ItemLibrary.Prepared prepared = mock(ItemLibrary.Prepared.class);
        when(prepared.items()).thenReturn(List.of(preparedItem));
        when(library.build(spec, true)).thenReturn(prepared);

        Inventory previewInv = mock(Inventory.class);
        ArgumentCaptor<InventoryHolder> holderCaptor = ArgumentCaptor.forClass(InventoryHolder.class);
        when(server.createInventory(holderCaptor.capture(), anyInt(), anyString())).thenReturn(previewInv);
        when(previewInv.getHolder()).thenAnswer(inv -> holderCaptor.getValue());

        KitService kitService = new KitService(plugin, repo, loader, () -> parser, () -> library, () -> delivery,
                Runnable::run, "srv-1", tempDir);
        kitService.enable();

        Player alice = mockPlayer("Alice", Set.of("allium.showkit", "allium.kits.starter", "allium.kitreset"));
        KitCommand showCmd = new KitCommand(kitService, CoreCommandSpecs.SHOWKIT);

        // Player opens read-only GUI
        showCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SHOWKIT, alice, "showkit", new String[]{"starter"}));
        verify(alice).openInventory(previewInv);
        verify(previewInv).setItem(0, stack);

        // Clicking or dragging inside preview GUI is cancelled
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(previewInv);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        kitService.onPreviewClick(click);
        verify(click).setCancelled(true);

        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        kitService.onPreviewDrag(drag);
        verify(drag).setCancelled(true);

        // Console gets chat list
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        showCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SHOWKIT, console, "showkit", new String[]{"starter"}));
        verify(messages).send(eq(console), eq(Messages.KIT_SHOW_HEADER), any(String[].class));
        verify(messages).send(eq(console), eq(Messages.KIT_SHOW_ENTRY), any(String[].class));

        // /kitreset starter clears cooldown
        when(repo.resetUsage(alice.getUniqueId(), "starter")).thenReturn(true, false);
        KitCommand resetCmd = new KitCommand(kitService, CoreCommandSpecs.KITRESET);
        resetCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.KITRESET, alice, "kitreset", new String[]{"starter"}));
        verify(messages).send(eq(alice), eq(Messages.KIT_RESET_SELF), any(String[].class));

        resetCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.KITRESET, alice, "kitreset", new String[]{"starter"}));
        verify(messages).send(eq(alice), eq(Messages.KIT_RESET_NONE), any(String[].class));
        kitService.disable();
    }

    @Test
    void tpaallTpautoAndJumpWorkEndToEnd() throws Exception {
        TeleportRepository tpRepo = mock(TeleportRepository.class);
        when(tpRepo.load(any(), anyString())).thenReturn(Optional.empty());
        when(tpRepo.loadCooldowns(any())).thenReturn(Map.of());

        PreferencesModule prefs = mock(PreferencesModule.class);
        PreferenceSessions prefSessions = mock(PreferenceSessions.class);
        when(moduleManager.getModule("preferences")).thenReturn(prefs);
        when(prefs.isEnabled()).thenReturn(true);
        when(prefs.sessions()).thenReturn(prefSessions);
        when(prefs.acceptance(any(), eq(PreferenceKey.ACCEPT_TELEPORT_REQUESTS)))
                .thenReturn(PreferenceSessions.Acceptance.ACCEPTS);

        codes.castled.allium.module.MessagingModule messaging = mock(codes.castled.allium.module.MessagingModule.class);
        codes.castled.allium.messaging.IgnoreService ignores = mock(codes.castled.allium.messaging.IgnoreService.class);
        when(moduleManager.getModule("messaging")).thenReturn(messaging);
        when(messaging.isEnabled()).thenReturn(true);
        when(messaging.ignores()).thenReturn(ignores);
        when(ignores.ignores(any(), any())).thenReturn(codes.castled.allium.messaging.MessagePolicy.Ignore.NO);
        codes.castled.allium.messaging.MessagingService messagingService = mock(codes.castled.allium.messaging.MessagingService.class);
        when(messaging.messaging()).thenReturn(messagingService);
        when(messagingService.visibleTo(any(), any())).thenReturn(true);

        AtomicBoolean bobAuto = new AtomicBoolean(false);
        Player staff = mockPlayer("Staff", Set.of("allium.tpaall", "allium.tpa", "allium.tpahere", "allium.jump"));
        Player bob = mockPlayer("Bob", Set.of("allium.tpaccept", "allium.tpauto"));
        Player carol = mockPlayer("Carol", Set.of("allium.tpaccept"));

        when(prefs.effective(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            boolean auto = id.equals(bob.getUniqueId()) && bobAuto.get();
            return Optional.of(new EffectivePreferences(true, true, true, 1.0, false, false, false, auto));
        });
        when(prefSessions.change(eq(bob.getUniqueId()), any(PreferenceChange.class))).thenAnswer(inv -> {
            PreferenceChange c = inv.getArgument(1);
            Boolean val = (Boolean) c.entries().get(PreferenceKey.TP_AUTO).orElse(false);
            bobAuto.set(Boolean.TRUE.equals(val));
            return CompletableFuture.completedFuture(StoredPreferences.empty(bob.getUniqueId()));
        });

        DestinationSafety safety = mock(DestinationSafety.class);
        when(safety.resolve(any(), any())).thenAnswer(inv -> Optional.of(((Location) inv.getArgument(0)).clone()));

        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override public void execute(Runnable command) { command.run(); }
        };
        TeleportService tpService = new TeleportService(plugin, tpRepo, pool, safety);
        tpService.enable();

        // /tpaall sends requests to both Bob and Carol; accepting Carol leaves Bob's pending request intact
        tpService.requestAll(staff, staff);
        assertEquals(2, tpService.pendingRequests());
        tpService.accept(carol, staff);
        assertEquals(1, tpService.pendingRequests());
        verify(carol).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));

        // Enable /tpauto for Bob; incoming /tpa from Staff auto-accepts immediately without queueing a request
        tpService.toggleAuto(bob, bob, true);
        assertTrue(bobAuto.get());
        verify(messages).send(eq(bob), eq(Messages.TELEPORT_AUTO_ON), any(String[].class));

        tpService.request(staff, bob, RequestTracker.Kind.TO_TARGET);
        verify(staff).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(messages).send(eq(bob), eq(Messages.TELEPORT_AUTO_ACCEPTED), any(String[].class));

        // /jump teleports to looked-at block +1 Y
        Block hitBlock = mock(Block.class);
        when(hitBlock.getLocation()).thenReturn(new Location(world, 30, 70, 40));
        when(staff.rayTraceBlocks(128.0, FluidCollisionMode.NEVER))
                .thenReturn(new RayTraceResult(new Vector(30.5, 71.0, 40.5), hitBlock, org.bukkit.block.BlockFace.UP));
        tpService.jump(staff);
        ArgumentCaptor<Location> jumpDest = ArgumentCaptor.forClass(Location.class);
        verify(staff, org.mockito.Mockito.atLeastOnce()).teleport(jumpDest.capture(), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertEquals(30.5, jumpDest.getValue().getX());
        assertEquals(71.0, jumpDest.getValue().getY());
        assertEquals(40.5, jumpDest.getValue().getZ());

        // /jump with no block in sight sends TELEPORT_JUMP_NONE
        when(staff.rayTraceBlocks(128.0, FluidCollisionMode.NEVER)).thenReturn(null);
        tpService.jump(staff);
        verify(messages).send(eq(staff), eq(Messages.TELEPORT_JUMP_NONE), any(String[].class));

        tpService.disable();
    }
}
