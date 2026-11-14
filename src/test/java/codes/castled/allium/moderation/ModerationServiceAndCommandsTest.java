package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.essentials.HealCommand;
import codes.castled.allium.command.kits.KitCommand;
import codes.castled.allium.command.moderation.MuteCommand;
import codes.castled.allium.command.moderation.MutesCommand;
import codes.castled.allium.command.moderation.UnmuteCommand;
import codes.castled.allium.command.moderation.VanishCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagePolicy;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationServiceAndCommandsTest {

    private PluginStart plugin;
    private Server server;
    private PluginManager pluginManager;
    private MessageManager messageManager;
    private ModerationRepository repository;
    private ModerationService moderation;
    private MessagingService messaging;
    private PlayerNameService playerNames;
    private AtomicLong clock;

    private final Set<UUID> persistedVanished = new LinkedHashSet<>();
    private final Map<UUID, MuteRecord> persistedMutes = new HashMap<>();
    private final List<ModerationAuditRecord> persistedAudits = new ArrayList<>();

    private FakePlayer vanishedStaff;
    private FakePlayer normalPlayer;
    private FakePlayer staffObserver;
    private ConsoleCommandSender console;
    private List<Player> onlinePlayers;

    @BeforeEach
    void setUp() throws Exception {
        clock = new AtomicLong(1_700_000_000_000L);
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("Allium", "1.0.0", "codes.castled.allium.PluginStart"));

        LanguageManager langManager = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(langManager.get(m)).thenReturn(m.getDefault());
            when(langManager.get(m.getKey(), m.getDefault())).thenReturn(m.getDefault());
        }
        when(plugin.getLanguageManager()).thenReturn(langManager);
        messageManager = new MessageManager(plugin, langManager);
        when(plugin.getMessageManager()).thenReturn(messageManager);

        repository = mock(ModerationRepository.class);
        when(repository.loadVanishedPlayers()).thenAnswer(inv -> new LinkedHashSet<>(persistedVanished));
        when(repository.loadVanished(any(UUID.class))).thenAnswer(inv -> persistedVanished.contains(inv.getArgument(0)));
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            boolean v = inv.getArgument(1);
            if (v) persistedVanished.add(id);
            else persistedVanished.remove(id);
            return null;
        }).when(repository).setVanished(any(UUID.class), anyBoolean(), anyString());

        when(repository.loadActiveMutes()).thenAnswer(inv -> new ArrayList<>(persistedMutes.values()));
        when(repository.loadActiveMute(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(persistedMutes.get(inv.getArgument(0))));
        doAnswer(inv -> {
            MuteRecord r = inv.getArgument(0);
            persistedMutes.put(r.playerUuid(), r);
            return null;
        }).when(repository).saveMute(any(MuteRecord.class));
        when(repository.removeMute(any(UUID.class))).thenAnswer(inv -> persistedMutes.remove(inv.getArgument(0)) != null);
        doAnswer(inv -> {
            persistedAudits.add(new ModerationAuditRecord(
                    persistedAudits.size() + 1L,
                    clock.get(),
                    inv.getArgument(0),
                    inv.getArgument(1),
                    inv.getArgument(2),
                    inv.getArgument(3),
                    inv.getArgument(4)
            ));
            return null;
        }).when(repository).recordAudit(anyString(), anyString(), any(), any(), anyString());
        when(repository.loadRecentAudit(any(Integer.class))).thenAnswer(inv -> List.copyOf(persistedAudits));

        IgnoreService ignoreService = mock(IgnoreService.class);
        when(ignoreService.ignores(any(), any())).thenReturn(MessagePolicy.Ignore.NO);
        messaging = new MessagingService(plugin, ignoreService);

        PlayerNameRepository nameRepo = mock(PlayerNameRepository.class);
        playerNames = new PlayerNameService(plugin, nameRepo, 100, Runnable::run);
        when(plugin.getPlayerNames()).thenReturn(playerNames);

        ModuleManager moduleManager = mock(ModuleManager.class);
        MessagingModule messagingModule = mock(MessagingModule.class);
        when(plugin.getModuleManager()).thenReturn(moduleManager);
        when(moduleManager.getModule("messaging")).thenReturn(messagingModule);
        when(messagingModule.isEnabled()).thenReturn(true);
        when(messagingModule.messaging()).thenReturn(messaging);
        when(messagingModule.ignores()).thenReturn(ignoreService);

        vanishedStaff = new FakePlayer("ModStaff", Set.of("allium.vanish", "allium.vanish.others", "allium.vanish.see", "allium.mute", "allium.unmute"));
        normalPlayer = new FakePlayer("ModNorm", Set.of("allium.msg", "allium.reply", "allium.tpa", "allium.heal", "allium.heal.others", "allium.kit", "allium.kit.others"));
        staffObserver = new FakePlayer("ModObs", Set.of("allium.vanish.see", "allium.msg", "allium.reply", "allium.heal", "allium.heal.others"));
        console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);

        onlinePlayers = new ArrayList<>(List.of(vanishedStaff.player, normalPlayer.player, staffObserver.player));
        doAnswer(inv -> onlinePlayers).when(server).getOnlinePlayers();
        when(server.getPlayerExact(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            for (Player p : onlinePlayers) {
                if (p.getName().equalsIgnoreCase(name)) {
                    return p;
                }
            }
            return null;
        });
        when(server.getPlayer(anyString())).thenAnswer(inv -> server.getPlayerExact(inv.getArgument(0)));
        when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            for (Player p : onlinePlayers) {
                if (p.getUniqueId().equals(id)) {
                    return p;
                }
            }
            return null;
        });

        for (Player p : onlinePlayers) {
            playerNames.cache().put(p.getUniqueId(), p.getName(), clock.get());
        }

        moderation = new ModerationService(plugin, repository, Runnable::run, clock::get);
        moderation.enable();
        messaging.setVanishSource(moderation);
        messaging.setMuteSource(moderation);
    }

    @AfterEach
    void tearDown() {
        moderation.disable();
    }

    @Test
    void vanishHidesPlayerFromNormalPlayersOnlyPersistsAcrossRelogAndSuppressesSideEffects() throws Exception {
        VanishCommand vanishCmd = new VanishCommand(plugin, moderation);

        // 1. Staff vanishes self
        vanishCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.VANISH, vanishedStaff.player, "vanish", new String[0]));
        assertTrue(moderation.isVanished(vanishedStaff.player));
        assertTrue(repository.loadVanished(vanishedStaff.uuid));
        assertTrue(vanishedStaff.sleepingIgnored);
        assertFalse(normalPlayer.player.canSee(vanishedStaff.player));
        assertTrue(staffObserver.player.canSee(vanishedStaff.player));

        // 2. Tab completion and PlayerNameService hide vanishedStaff from normalPlayer, but show to staffObserver
        assertEquals(List.of("ModNorm", "ModObs"), BukkitCommandRenderer.collectVisibleOnlinePlayers(server, normalPlayer.player));
        assertEquals(List.of("ModNorm", "ModObs", "ModStaff"), BukkitCommandRenderer.collectVisibleOnlinePlayers(server, staffObserver.player));
        assertEquals(List.of("ModNorm", "ModObs"), playerNames.complete(normalPlayer.player, "mod", 50));
        assertEquals(List.of("ModStaff", "ModNorm", "ModObs"), playerNames.complete(staffObserver.player, "mod", 50));

        // 3. /msg to vanishedStaff looks offline to normalPlayer, but delivers from staffObserver
        normalPlayer.messages.clear();
        assertEquals(MessagePolicy.Outcome.NOT_FOUND, messaging.send(normalPlayer.player, vanishedStaff.player, "ModStaff", "are you there?"));
        assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("Player ModStaff not found")));

        staffObserver.messages.clear();
        assertEquals(MessagePolicy.Outcome.DELIVER, messaging.send(staffObserver.player, vanishedStaff.player, "ModStaff", "staff ping"));

        // 3b. /reply to a vanished partner gives MSG_NO_REPLY_TARGET for normal players without clearing staffObserver's target
        assertEquals(MessagePolicy.Outcome.DELIVER, messaging.send(vanishedStaff.player, normalPlayer.player, "ModNorm", "secret ping"));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(vanishedStaff.uuid)).thenReturn(vanishedStaff.player);
            normalPlayer.messages.clear();
            messaging.reply(normalPlayer.player, "reply attempt");
            assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("nobody to reply to")),
                    "expected offline-equivalent no-reply message, got " + normalPlayer.messages);

            vanishedStaff.messages.clear();
            staffObserver.messages.clear();
            messaging.reply(staffObserver.player, "staff reply");
            assertTrue(vanishedStaff.messages.stream().anyMatch(m -> m.contains("staff reply")));
        }

        // 4. Other commands (/heal <player>, /kit <name> <player>) treat vanishedStaff as PLAYER_NOT_FOUND for normalPlayer
        normalPlayer.messages.clear();
        new HealCommand(plugin).execute(new BukkitCommandContext(plugin, CoreCommandSpecs.HEAL, normalPlayer.player, "heal", new String[]{"ModStaff"}));
        assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("Player ModStaff not found")));

        KitService kitService = mock(KitService.class);
        normalPlayer.messages.clear();
        new KitCommand(kitService, CoreCommandSpecs.KIT).execute(new BukkitCommandContext(plugin, CoreCommandSpecs.KIT, normalPlayer.player, "kit", new String[]{"starter", "ModStaff"}));
        assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("Player ModStaff not found")));
        verify(kitService, never()).claim(any(), any(), anyString());

        // 5. Mob targeting cleared and EntityTargetEvent cancelled
        Mob zombie = mock(Mob.class);
        when(zombie.getTarget()).thenReturn(vanishedStaff.player);
        doReturn(List.of(zombie)).when(vanishedStaff.player).getNearbyEntities(anyDouble(), anyDouble(), anyDouble());
        vanishCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.VANISH, vanishedStaff.player, "vanish", new String[]{"on"}));
        verify(zombie).setTarget(null);

        EntityTargetEvent targetEvent = new EntityTargetEvent(zombie, vanishedStaff.player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        moderation.onEntityTarget(targetEvent);
        assertTrue(targetEvent.isCancelled());
        assertNull(targetEvent.getTarget());

        // 6. Item pickup, physical interaction (pressure plate), sculk game events, and silent containers
        EntityPickupItemEvent pickupEvent = new EntityPickupItemEvent(vanishedStaff.player, mock(Item.class), 0);
        moderation.onEntityPickupItem(pickupEvent);
        assertTrue(pickupEvent.isCancelled());

        PlayerInteractEvent pressurePlate = new PlayerInteractEvent(vanishedStaff.player, Action.PHYSICAL, null, mock(Block.class), null);
        moderation.onPhysicalInteract(pressurePlate);
        assertTrue(pressurePlate.isCancelled());

        BlockReceiveGameEvent sculkEvent = mock(BlockReceiveGameEvent.class);
        when(sculkEvent.getEntity()).thenReturn(vanishedStaff.player);
        moderation.onBlockReceiveGameEvent(sculkEvent);
        verify(sculkEvent).setCancelled(true);

        // Silent Ender Chest & lidded Chest interaction
        Inventory enderChestInv = mock(Inventory.class);
        when(vanishedStaff.player.getEnderChest()).thenReturn(enderChestInv);
        Block enderChestBlock = mock(Block.class);
        when(enderChestBlock.getType()).thenReturn(Material.ENDER_CHEST);
        PlayerInteractEvent enderChestInteract = new PlayerInteractEvent(vanishedStaff.player, Action.RIGHT_CLICK_BLOCK, null, enderChestBlock, null);
        moderation.onContainerInteract(enderChestInteract);
        assertTrue(enderChestInteract.isCancelled());
        verify(vanishedStaff.player).openInventory(enderChestInv);

        Block chestBlock = mock(Block.class);
        when(chestBlock.getType()).thenReturn(Material.CHEST);
        Chest chestState = mock(Chest.class);
        Inventory chestInv = mock(Inventory.class);
        when(chestInv.getSize()).thenReturn(27);
        when(chestInv.getContents()).thenReturn(new ItemStack[27]);
        when(chestState.getInventory()).thenReturn(chestInv);
        when(chestBlock.getState()).thenReturn(chestState);
        Inventory mirrorInv = mock(Inventory.class);
        when(server.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenReturn(mirrorInv);
        PlayerInteractEvent chestInteract = new PlayerInteractEvent(vanishedStaff.player, Action.RIGHT_CLICK_BLOCK, null, chestBlock, null);
        moderation.onContainerInteract(chestInteract);
        assertTrue(chestInteract.isCancelled());
        verify(vanishedStaff.player).openInventory(mirrorInv);

        // 7. ServerListPingEvent removes vanished player from iterator
        List<Player> pingPlayers = new ArrayList<>(onlinePlayers);
        ServerListPingEvent pingEvent = new ServerListPingEvent("", InetAddress.getLoopbackAddress(), "MOTD", 3, 20) {
            @Override
            public Iterator<Player> iterator() {
                return pingPlayers.iterator();
            }
        };
        moderation.onServerListPing(pingEvent);
        assertEquals(List.of(normalPlayer.player, staffObserver.player), pingPlayers);

        // 8. Quit and Join messages suppressed while vanished, and relog never reveals the player
        PlayerQuitEvent quitEvent = new PlayerQuitEvent(vanishedStaff.player, "ModStaff left the game");
        moderation.onPlayerQuit(quitEvent);
        assertNull(quitEvent.getQuitMessage());

        // Simulate restart/relog with a fresh player instance for ModStaff
        FakePlayer rejoinedStaff = new FakePlayer(vanishedStaff.uuid, "ModStaff", vanishedStaff.permissions);
        onlinePlayers.set(0, rejoinedStaff.player);
        PlayerJoinEvent joinEvent = new PlayerJoinEvent(rejoinedStaff.player, "ModStaff joined the game");
        moderation.onPlayerJoin(joinEvent);
        assertNull(joinEvent.getJoinMessage());
        assertFalse(normalPlayer.player.canSee(rejoinedStaff.player));
        assertTrue(staffObserver.player.canSee(rejoinedStaff.player));

        // 9. Unvanish restores visibility
        vanishCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.VANISH, rejoinedStaff.player, "vanish", new String[]{"off"}));
        assertFalse(moderation.isVanished(rejoinedStaff.player));
        assertTrue(normalPlayer.player.canSee(rejoinedStaff.player));
    }

    @Test
    void muteBlocksChatMsgReplyAndMailWithRemainingTimeExpiresAutomaticallyAndSupportsListing() throws Exception {
        MuteCommand muteCmd = new MuteCommand(plugin, moderation);
        UnmuteCommand unmuteCmd = new UnmuteCommand(plugin, moderation);
        MutesCommand mutesCmd = new MutesCommand(plugin, moderation);

        // 1. Timed mute for 10s with reason
        muteCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MUTE, vanishedStaff.player, "mute",
                new String[]{"ModNorm", "10s", "Caps", "in", "global"}));
        assertTrue(moderation.isMuted(normalPlayer.uuid));
        assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("10s") && m.contains("Caps in global")));

        // 2. /mutes lists the active mute
        vanishedStaff.messages.clear();
        mutesCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MUTES, vanishedStaff.player, "mutes", new String[0]));
        assertTrue(vanishedStaff.messages.stream().anyMatch(m -> m.contains("Active mutes (1)")));
        assertTrue(vanishedStaff.messages.stream().anyMatch(m -> m.contains("ModNorm") && m.contains("10s") && m.contains("Caps in global")));

        // 3. Chat is cancelled and shows remaining time
        normalPlayer.messages.clear();
        AsyncPlayerChatEvent chatEvent = new AsyncPlayerChatEvent(true, normalPlayer.player, "hello world", new HashSet<>(onlinePlayers));
        moderation.onAsyncPlayerChat(chatEvent);
        assertTrue(chatEvent.isCancelled());
        assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("You are muted") && m.contains("10s") && m.contains("Caps in global")));

        // 4. /msg is refused and shows remaining time
        normalPlayer.messages.clear();
        assertEquals(MessagePolicy.Outcome.SENDER_MUTED, messaging.send(normalPlayer.player, staffObserver.player, "ModObs", "secret pm"));
        assertTrue(normalPlayer.messages.stream().anyMatch(m -> m.contains("You are muted") && m.contains("10s") && m.contains("Caps in global")));

        // 5. Advance clock by 11 seconds -> timed mute expires automatically without restart
        clock.addAndGet(11_000L);
        moderation.tickExpiredMutes();
        assertFalse(moderation.isMuted(normalPlayer.uuid));

        AsyncPlayerChatEvent chatAfterExpiry = new AsyncPlayerChatEvent(true, normalPlayer.player, "im back", new HashSet<>(onlinePlayers));
        moderation.onAsyncPlayerChat(chatAfterExpiry);
        assertFalse(chatAfterExpiry.isCancelled());

        // 6. Permanent mute and /unmute
        muteCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MUTE, vanishedStaff.player, "mute",
                new String[]{"ModNorm", "Permanent", "mute", "reason"}));
        assertTrue(moderation.isMuted(normalPlayer.uuid));
        assertTrue(moderation.activeMute(normalPlayer.uuid).orElseThrow().isPermanent());

        unmuteCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.UNMUTE, vanishedStaff.player, "unmute",
                new String[]{"ModNorm", "Appealed"}));
        assertFalse(moderation.isMuted(normalPlayer.uuid));

        // 7. Verify audit trail
        List<ModerationAuditRecord> audits = repository.loadRecentAudit(10);
        assertTrue(audits.stream().anyMatch(a -> a.action().equals("MUTE")));
        assertTrue(audits.stream().anyMatch(a -> a.action().equals("MUTE_EXPIRED")));
        assertTrue(audits.stream().anyMatch(a -> a.action().equals("UNMUTE")));
    }

    private final class FakePlayer {
        final UUID uuid;
        final String name;
        final Set<String> permissions;
        final Player player;
        final Set<UUID> hiddenTargets = new HashSet<>();
        final Map<String, List<MetadataValue>> metadata = new HashMap<>();
        final List<String> messages = new ArrayList<>();
        boolean sleepingIgnored;

        FakePlayer(String name, Set<String> permissions) {
            this(UUID.nameUUIDFromBytes(name.getBytes()), name, permissions);
        }

        FakePlayer(UUID uuid, String name, Set<String> permissions) {
            this.uuid = uuid;
            this.name = name;
            this.permissions = new HashSet<>(permissions);
            this.player = mock(Player.class);
            Player.Spigot spigot = mock(Player.Spigot.class);
            when(player.spigot()).thenReturn(spigot);
            doAnswer(inv -> {
                for (Object arg : inv.getArguments()) {
                    if (arg instanceof net.md_5.bungee.api.chat.BaseComponent component) {
                        messages.add(ChatColor.stripColor(component.toPlainText()));
                    } else if (arg instanceof net.md_5.bungee.api.chat.BaseComponent[] components) {
                        StringBuilder sb = new StringBuilder();
                        for (var c : components) sb.append(c.toPlainText());
                        messages.add(ChatColor.stripColor(sb.toString()));
                    }
                }
                return null;
            }).when(spigot).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));
            doAnswer(inv -> {
                net.md_5.bungee.api.chat.BaseComponent c = inv.getArgument(0);
                messages.add(ChatColor.stripColor(c.toPlainText()));
                return null;
            }).when(spigot).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent.class));
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenReturn(true);
            when(player.hasPermission(anyString())).thenAnswer(inv -> this.permissions.contains(inv.getArgument(0)));
            doAnswer(inv -> {
                Player target = inv.getArgument(1);
                hiddenTargets.add(target.getUniqueId());
                return null;
            }).when(player).hidePlayer(any(Plugin.class), any(Player.class));
            doAnswer(inv -> {
                Player target = inv.getArgument(1);
                hiddenTargets.remove(target.getUniqueId());
                return null;
            }).when(player).showPlayer(any(Plugin.class), any(Player.class));
            when(player.canSee(any(Player.class))).thenAnswer(inv -> {
                Player target = inv.getArgument(0);
                return !hiddenTargets.contains(target.getUniqueId());
            });
            doAnswer(inv -> {
                String key = inv.getArgument(0);
                MetadataValue val = inv.getArgument(1);
                metadata.put(key, List.of(val));
                return null;
            }).when(player).setMetadata(anyString(), any(MetadataValue.class));
            doAnswer(inv -> {
                String key = inv.getArgument(0);
                metadata.remove(key);
                return null;
            }).when(player).removeMetadata(anyString(), any(Plugin.class));
            when(player.getMetadata(anyString())).thenAnswer(inv -> metadata.getOrDefault(inv.getArgument(0), List.of()));
            doAnswer(inv -> {
                sleepingIgnored = inv.getArgument(0);
                return null;
            }).when(player).setSleepingIgnored(any(Boolean.class));
            doAnswer(inv -> {
                String raw = inv.getArgument(0);
                messages.add(ChatColor.stripColor(raw));
                return null;
            }).when(player).sendMessage(anyString());
        }
    }
}
