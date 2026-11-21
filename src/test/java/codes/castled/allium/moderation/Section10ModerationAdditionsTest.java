package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.moderation.MuteCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Section10ModerationAdditionsTest {

    private PluginStart plugin;
    private Server server;
    private ModerationRepository repository;
    private PlayerNameService names;
    private YamlConfiguration config;
    private DisciplineService discipline;
    private ModerationService moderation;
    private ConsoleCommandSender console;
    private World world;

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final QueueExecutor io = new QueueExecutor();
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<String, NameCache.Entry> directory = new HashMap<>();
    private final Map<UUID, BanRecord> bans = new HashMap<>();
    private final Map<UUID, MuteRecord> mutes = new HashMap<>();
    private final Map<UUID, JailAssignment> prisoners = new HashMap<>();
    private final Map<String, JailRecord> jails = new HashMap<>();
    private final List<String> messagesSent = new ArrayList<>();

    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable work) { queue.add(work); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        repository = mock(ModerationRepository.class);
        names = mock(PlayerNameService.class);
        console = mock(ConsoleCommandSender.class);
        config = new YamlConfiguration();
        config.set("moderation.preload-wait-millis", 0);
        config.set("moderation.discipline.rate-limit-millis", 0);
        config.set("moderation.discipline.ip-ban-enabled", true);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getPlayerNames()).thenReturn(names);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getOnlinePlayers()).thenAnswer(i -> new ArrayList<>(online.values()));
        when(server.getPlayer(any(UUID.class))).thenAnswer(i -> online.get(i.getArgument(0)));
        when(server.getPlayerExact(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return online.values().stream().filter(p -> p.getName().equalsIgnoreCase(n)).findFirst().orElse(null);
        });
        when(console.hasPermission(anyString())).thenReturn(true);
        when(console.getName()).thenReturn("Console");
        doAnswer(i -> { messagesSent.add("Console:" + i.getArgument(0)); return null; }).when(console).sendMessage(anyString());

        LanguageManager language = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(language.get(m)).thenReturn(m.getDefault());
            when(language.get(m.getKey(), m.getDefault())).thenReturn(m.getDefault());
        }
        when(plugin.getMessageManager()).thenReturn(new MessageManager(plugin, language));

        when(names.resolve(anyString())).thenAnswer(i -> CompletableFuture.completedFuture(
                Optional.ofNullable(directory.get(i.<String>getArgument(0).toLowerCase()))));
        when(repository.loadActiveBans()).thenAnswer(i -> List.copyOf(bans.values()));
        when(repository.loadActiveBan(any())).thenAnswer(i -> Optional.ofNullable(bans.get(i.getArgument(0))));
        when(repository.loadBanByIp(anyString())).thenAnswer(i -> bans.values().stream()
                .filter(b -> i.getArgument(0).equals(b.ipAddress()) && !b.isExpired(clock.get())).findFirst());
        doAnswer(i -> { BanRecord b = i.getArgument(0); bans.put(b.playerUuid(), b); return null; }).when(repository).saveBan(any());
        when(repository.removeBan(any(), anyString())).thenAnswer(i -> bans.remove(i.getArgument(0)) != null);
        when(repository.removeBansByIp(anyString(), anyString())).thenAnswer(i -> {
            String ip = i.getArgument(0);
            int before = bans.size();
            bans.values().removeIf(b -> ip.equals(b.ipAddress()));
            return before - bans.size();
        });
        when(repository.loadFrozenPlayers()).thenReturn(List.of());
        when(repository.loadFrozen(any())).thenReturn(Optional.empty());
        when(repository.loadJails()).thenAnswer(i -> List.copyOf(jails.values()));
        doAnswer(i -> { JailRecord j = i.getArgument(0); jails.put(j.name(), j); return null; }).when(repository).saveJail(any(), anyString());
        when(repository.deleteJail(anyString(), anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (!jails.containsKey(key)) return false;
            if (prisoners.values().stream().anyMatch(p -> key.equals(p.jailName()))) return false;
            return jails.remove(key) != null;
        });
        when(repository.loadJailedPlayers()).thenAnswer(i -> List.copyOf(prisoners.values()));
        when(repository.loadJailed(any())).thenAnswer(i -> Optional.ofNullable(prisoners.get(i.getArgument(0))));
        when(repository.setJailed(any())).thenAnswer(i -> {
            JailAssignment a = i.getArgument(0);
            if (!jails.containsKey(a.jailName())) return false;
            prisoners.put(a.playerUuid(), a);
            return true;
        });
        when(repository.removeJailed(any(), anyString())).thenAnswer(i -> prisoners.remove(i.getArgument(0)) != null);

        when(repository.loadVanishedPlayers()).thenReturn(Set.of());
        when(repository.loadActiveMutes()).thenAnswer(i -> List.copyOf(mutes.values()));
        doAnswer(i -> { MuteRecord r = i.getArgument(0); mutes.put(r.playerUuid(), r); return null; }).when(repository).saveMute(any());
        when(repository.removeMute(any())).thenAnswer(i -> mutes.remove(i.getArgument(0)) != null);

        world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.getCenter()).thenReturn(new Location(world, 0, 64, 0));
        when(border.getSize()).thenReturn(60_000_000.0);
        when(border.isInside(any())).thenReturn(true);
        Block floor = mock(Block.class), air = mock(Block.class);
        BlockData sturdy = mock(BlockData.class);
        when(sturdy.isFaceSturdy(any(), any())).thenReturn(true);
        when(floor.getBlockData()).thenReturn(sturdy);
        when(floor.getType()).thenReturn(Material.STONE);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i -> (int) i.getArgument(1) == 63 ? floor : air);

        discipline = new DisciplineService(plugin, repository, io, clock::get);
        discipline.enable();
        io.drain();

        moderation = new ModerationService(plugin, repository, Runnable::run, clock::get);
        moderation.enable();
    }

    private Player addPlayer(String name, String ip, Set<String> perms) throws Exception {
        UUID id = UUID.randomUUID();
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenAnswer(i -> online.get(id) == p);
        when(p.hasPermission(anyString())).thenAnswer(i -> perms.contains(i.getArgument(0)));
        when(p.getAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(ip), 25565));
        AtomicReference<Location> location = new AtomicReference<>(new Location(world, 10.5, 64, 10.5));
        when(p.getLocation()).thenAnswer(i -> location.get().clone());
        when(p.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(i -> {
            PlayerTeleportEvent e = new PlayerTeleportEvent(p, location.get().clone(), i.getArgument(0), i.getArgument(1));
            discipline.onTeleport(e);
            if (e.isCancelled()) return false;
            location.set(e.getTo().clone());
            return true;
        });
        doAnswer(i -> { messagesSent.add(name + ":" + i.getArgument(0)); return null; }).when(p).sendMessage(anyString());
        online.put(id, p);
        directory.put(name.toLowerCase(), new NameCache.Entry(id, name, clock.get()));
        discipline.onJoin(new PlayerJoinEvent(p, ""));
        io.drain();
        return p;
    }

    @Test
    void tempbanipAndUnbanipWorkForBothLiteralIpAndPlayerName() throws Exception {
        Player alice = addPlayer("Alice", "203.0.113.10", Set.of());
        Player bob = addPlayer("Bob", "203.0.113.20", Set.of());

        // Literal IP tempbanip
        discipline.dispatch(console, "tempbanip", new String[]{"203.0.113.10", "10m", "spam bots"});
        io.drain();
        assertTrue(discipline.isIpBanned("203.0.113.10"));
        verify(alice).kickPlayer(anyString());

        // Pre-login from banned IP is denied
        AsyncPlayerPreLoginEvent login = new AsyncPlayerPreLoginEvent("Alt", InetAddress.getByName("203.0.113.10"), UUID.randomUUID());
        discipline.onAsyncPreLogin(login);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login.getLoginResult());

        // Unban literal IP via pardonip alias
        discipline.dispatch(console, "pardonip", new String[]{"203.0.113.10"});
        io.drain();
        assertFalse(discipline.isIpBanned("203.0.113.10"));

        // Player-name tempbanip and player-name unbanip
        discipline.dispatch(console, "tempbanip", new String[]{"Bob", "5m", "griefing"});
        io.drain();
        assertTrue(discipline.isIpBanned("203.0.113.20"));
        verify(bob).kickPlayer(anyString());

        discipline.dispatch(console, "unbanip", new String[]{"Bob"});
        io.drain();
        assertFalse(discipline.isIpBanned("203.0.113.20"));
    }

    @Test
    void muteTogglesOffWhenAlreadyMutedAndHasSilenceAlias() throws Exception {
        assertTrue(CoreCommandSpecs.MUTE.aliases().contains("silence"));
        Player alice = addPlayer("Alice", "203.0.113.11", Set.of());
        MuteCommand cmd = new MuteCommand(plugin, moderation);

        // First /silence Alice -> mutes
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MUTE, console, "silence", new String[]{"Alice"}));
        assertTrue(moderation.isMuted(alice.getUniqueId()));

        // /mute Alice 10m spamming -> updates mute rather than toggling off
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MUTE, console, "mute", new String[]{"Alice", "10m", "spamming"}));
        assertTrue(moderation.isMuted(alice.getUniqueId()));
        assertFalse(moderation.activeMute(alice.getUniqueId()).orElseThrow().isPermanent());

        // Second bare /mute Alice -> toggles off (unmutes)
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MUTE, console, "mute", new String[]{"Alice"}));
        assertFalse(moderation.isMuted(alice.getUniqueId()));
    }

    @Test
    void togglejailJailsJailedPlayersTimedExpiryAndDeljailWorkEndToEnd() throws Exception {
        Player staff = addPlayer("Staff", "203.0.113.1", Set.of("allium.setjail", "allium.deljail", "allium.jails", "allium.jailedplayers"));
        Player alice = addPlayer("Alice", "203.0.113.2", Set.of());

        // Empty /jails and /jailedplayers
        discipline.dispatch(console, "jails", new String[]{});
        discipline.dispatch(console, "jailedplayers", new String[]{});
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("No jails are defined")));
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("No players are currently jailed")));

        // Create jail "cell"
        messagesSent.clear();
        discipline.dispatch(staff, "setjail", new String[]{"cell"});
        io.drain();
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("Jail") && m.contains("cell") && m.contains("set")));

        messagesSent.clear();
        discipline.dispatch(console, "jails", new String[]{});
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("cell")));

        // /tjail Alice cell 5m -> timed jail
        discipline.dispatch(console, "tjail", new String[]{"Alice", "cell", "5m"});
        io.drain();
        assertTrue(discipline.jailed(alice.getUniqueId()));
        assertFalse(discipline.jailAssignment(alice.getUniqueId()).orElseThrow().isPermanent());

        // /jailedplayers lists Alice in cell
        messagesSent.clear();
        discipline.dispatch(console, "jailedplayers", new String[]{});
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("Alice") && m.contains("cell")));

        // /deljail refuses while occupied
        messagesSent.clear();
        discipline.dispatch(console, "rmjail", new String[]{"cell"});
        io.drain();
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("cannot be deleted while players are jailed")));
        assertTrue(jails.containsKey("cell"));

        // Advance clock past 5m -> tickExpiredJails auto-releases Alice
        clock.addAndGet(300_000L);
        discipline.tickExpiredJails();
        io.drain();
        assertFalse(discipline.jailed(alice.getUniqueId()));

        // Toggle jail using sole jail (omitting jail name), then toggle off with /togglejail Alice
        discipline.dispatch(console, "togglejail", new String[]{"Alice"});
        io.drain();
        assertTrue(discipline.jailed(alice.getUniqueId()));

        discipline.dispatch(console, "togglejail", new String[]{"Alice"});
        io.drain();
        assertFalse(discipline.jailed(alice.getUniqueId()));

        // Now /deljail cell succeeds
        messagesSent.clear();
        discipline.dispatch(console, "deljail", new String[]{"cell"});
        io.drain();
        assertFalse(jails.containsKey("cell"));
        assertTrue(messagesSent.stream().anyMatch(m -> m.contains("Jail") && m.contains("cell") && m.contains("deleted")));
    }

    @Test
    void kickallKicksEveryoneExceptSenderAndExemptHolders() throws Exception {
        Player staff = addPlayer("Staff", "203.0.113.1", Set.of("allium.kickall"));
        Player victim1 = addPlayer("Victim1", "203.0.113.2", Set.of());
        Player victim2 = addPlayer("Victim2", "203.0.113.3", Set.of());
        Player exempt = addPlayer("Exempt", "203.0.113.4", Set.of("allium.kickall.exempt"));
        Player modExempt = addPlayer("ModExempt", "203.0.113.5", Set.of("allium.moderation.exempt"));

        discipline.dispatch(staff, "kickall", new String[]{"Server maintenance"});
        io.drain();

        verify(victim1).kickPlayer(anyString());
        verify(victim2).kickPlayer(anyString());
        verify(staff, never()).kickPlayer(anyString());
        verify(exempt, never()).kickPlayer(anyString());
        verify(modExempt, never()).kickPlayer(anyString());
        assertTrue(messagesSent.stream().anyMatch(m -> m.startsWith("Staff:") && m.contains("Kicked") && m.contains("2")));
    }
}
