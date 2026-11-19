package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
import codes.castled.allium.config.ResourceSyncManager;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.util.AlliumLogger;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionBarAndWarmupCountdownTest {

    @TempDir
    Path tempDir;

    @Test
    void actionBarLangFormatSendsNoChatLineAndWarmupCountsDownInActionBar() throws Exception {
        // 1. LanguageEntry parses [actionbar] with empty chat text so ZERO chat line is sent.
        LanguageEntry parsed = LanguageEntry.read(List.of(
                "[actionbar] &eTeleporting in &f{warmup}s&e.",
                "[sound] block.note_block.chime 1.0"));
        assertEquals("", parsed.text());
        assertEquals("&eTeleporting in &f{warmup}s&e.", parsed.actionBar());
        assertEquals("minecraft:block.note_block.chime", parsed.sound().soundId());

        LanguageEntry plainChat = LanguageEntry.read(List.of("&aHello"));
        assertEquals("&aHello", plainChat.text());
        assertNull(plainChat.actionBar());

        // 2. MessageManager.send() with [actionbar]-only entry delivers ONLY on ChatMessageType.ACTION_BAR,
        //    never player.sendMessage(...) and never ChatMessageType.CHAT.
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.warmup-seconds", 2);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("actionbar-test"));
        AlliumLogger logger = new AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(logger);

        java.nio.file.Path langFile = tempDir.resolve("lang/en_US.yml");
        java.nio.file.Files.createDirectories(langFile.getParent());
        try (var in = getClass().getClassLoader().getResourceAsStream("lang/en_US.yml")) {
            java.nio.file.Files.copy(in, langFile);
        }

        LanguageManager lang = new LanguageManager(plugin, mock(ResourceSyncManager.class));
        lang.load();
        MessageManager messages = new MessageManager(plugin, lang);
        when(plugin.getMessageManager()).thenReturn(messages);

        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        UUID playerId = UUID.randomUUID();
        World world = safeWorld();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.spigot()).thenReturn(spigot);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(true);
        when(player.hasPermission(anyString())).thenAnswer(inv -> {
            String perm = inv.getArgument(0);
            return perm.equals("allium.home") || perm.equals("allium.worlds.world");
        });

        messages.send(player, Messages.TELEPORT_WARMUP, "{warmup}", "3", "{time}", "3");

        // Zero chat line sent!
        verify(player, never()).sendMessage(anyString());
        verify(spigot, never()).sendMessage(any(BaseComponent[].class));
        verify(spigot, never()).sendMessage(eq(ChatMessageType.CHAT), any(BaseComponent[].class));

        ArgumentCaptor<BaseComponent[]> barCaptor = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(spigot, times(1)).sendMessage(eq(ChatMessageType.ACTION_BAR), barCaptor.capture());
        String firstPlain = BaseComponent.toPlainText(barCaptor.getValue());
        assertTrue(firstPlain.contains("Teleporting in 3s"), firstPlain);
        // Sound played once on initial send.
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.chime"), anyFloat(), anyFloat());

        // 3. Silent action bar countdown update does NOT replay sound or send chat.
        messages.sendActionBarUpdate(player, Messages.TELEPORT_WARMUP, "{warmup}", "2", "{time}", "2");
        verify(player, times(1)).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
        verify(spigot, times(2)).sendMessage(eq(ChatMessageType.ACTION_BAR), barCaptor.capture());
        assertTrue(BaseComponent.toPlainText(barCaptor.getValue()).contains("Teleporting in 2s"));

        // 4. End-to-end TeleportService warmup ticks down {warmup} in action bar and clears on completion.
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getPlayer(playerId)).thenReturn(player);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(player));
        when(server.getWorlds()).thenReturn(List.of(world));
        when(plugin.getServer()).thenReturn(server);

        AtomicReference<BooleanSupplier> tickRef = new AtomicReference<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; })
                .when(scheduler).runOnServer(any(Runnable.class));
        when(scheduler.repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong())).thenAnswer(inv -> {
            tickRef.set(inv.getArgument(0));
            return TaskHandle.NONE;
        });
        when(plugin.getScheduler()).thenReturn(scheduler);

        PreferencesModule prefs = mock(PreferencesModule.class);
        when(prefs.isEnabled()).thenReturn(true);
        EffectivePreferences eff = mock(EffectivePreferences.class);
        when(eff.messageSoundVolume()).thenReturn(1.0);
        when(prefs.effective(any())).thenReturn(Optional.of(eff));
        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("preferences")).thenReturn(prefs);
        when(plugin.getModuleManager()).thenReturn(modules);

        TeleportRepository repo = mock(TeleportRepository.class);
        when(repo.load(any(), anyString())).thenReturn(Optional.empty());
        when(repo.loadCooldowns(any())).thenReturn(Map.of());
        ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        TeleportService tp = new TeleportService(plugin, repo, io);
        tp.enable();
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);

        List<String> actionBarHistory = new ArrayList<>();
        doAnswer(inv -> {
            StringBuilder sb = new StringBuilder();
            Object[] all = inv.getArguments();
            for (int i = 1; i < all.length; i++) {
                if (all[i] instanceof BaseComponent[] arr) sb.append(BaseComponent.toPlainText(arr));
                else if (all[i] instanceof BaseComponent comp) sb.append(comp.toPlainText());
            }
            actionBarHistory.add(sb.toString());
            return null;
        }).when(spigot).sendMessage(eq(ChatMessageType.ACTION_BAR), any(BaseComponent[].class));
        doAnswer(inv -> {
            BaseComponent comp = inv.getArgument(1);
            actionBarHistory.add(comp.toPlainText());
            return null;
        }).when(spigot).sendMessage(eq(ChatMessageType.ACTION_BAR), any(BaseComponent.class));

        assertTrue(tp.teleport(player, () -> new Location(world, 5, 64, 5), "home", () -> true));
        assertTrue(actionBarHistory.stream().anyMatch(s -> s.contains("Teleporting in 2s")), actionBarHistory.toString());

        // Wait ~1.1s so remaining drops from 2s -> 1s, tick once -> actionbar updates to 1s!
        Thread.sleep(1150L);
        tickRef.get().getAsBoolean();
        assertTrue(actionBarHistory.stream().anyMatch(s -> s.contains("Teleporting in 1s")), actionBarHistory.toString());

        // Wait remaining ~1.0s and tick -> teleport commits and actionbar clears ("").
        Thread.sleep(1050L);
        tickRef.get().getAsBoolean();
        verify(player).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertTrue(actionBarHistory.contains(""), "Expected action bar clear on completion: " + actionBarHistory);

        tp.disable();
    }

    private static World safeWorld() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.isChunkGenerated(anyInt(), anyInt())).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class);
        when(border.getCenter()).thenReturn(new Location(world, 0, 64, 0));
        when(border.getSize()).thenReturn(10000.0);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.getWorldBorder()).thenReturn(border);
        Block solid = mock(Block.class);
        when(solid.getType()).thenReturn(Material.STONE);
        when(solid.isPassable()).thenReturn(false);
        when(solid.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0, 63, 0, 1, 64, 1));
        BlockData solidData = mock(BlockData.class);
        when(solidData.isFaceSturdy(any(), any())).thenReturn(true);
        when(solid.getBlockData()).thenReturn(solidData);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        when(air.isEmpty()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> ((int) inv.getArgument(1)) < 64 ? solid : air);
        return world;
    }
}
