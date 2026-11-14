package codes.castled.allium.kit;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import codes.castled.allium.item.MockItemStacks;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KitServiceTest {

    @TempDir
    Path tempDir;

    private File dataFolder;
    private Path playerDataDir;
    private PluginStart plugin;
    private Server server;
    private PluginManager pluginManager;
    private MessageManager messages;
    private KitRepository repository;
    private Player player;
    private UUID playerId;
    private Map<NamespacedKey, Object> pdc;
    private final Map<String, Permission> permissions = new HashMap<>();
    private final Set<String> playerGrants = new HashSet<>();
    private final List<String> infoLogs = new ArrayList<>();
    private final List<String> warnLogs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = tempDir.resolve("plugin").toFile();
        assertTrue(dataFolder.mkdirs());
        playerDataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerDataDir);

        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        messages = mock(MessageManager.class);
        repository = mock(KitRepository.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.isServerThread()).thenReturn(true);
        when(scheduler.serverExecutor()).thenReturn(Runnable::run);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return TaskHandle.NONE;
        });
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);

        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPermission(anyString())).thenAnswer(call -> permissions.get(call.getArgument(0)));
        doAnswer(call -> {
            Permission p = call.getArgument(0);
            permissions.put(p.getName(), p);
            return null;
        }).when(pluginManager).addPermission(any(Permission.class));

        playerId = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(player));
        when(player.hasPermission(anyString())).thenAnswer(call -> playerGrants.contains(call.getArgument(0)));

        PlayerInventory inv = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inv);

        pdc = new HashMap<>();
        var container = MockItemStacks.container(pdc);
        when(player.getPersistentDataContainer()).thenReturn(container);
    }

    private KitDefinitionLoader newLoader() {
        return new KitDefinitionLoader(
                dataFolder,
                path -> getClass().getClassLoader().getResourceAsStream(path),
                infoLogs::add,
                warnLogs::add
        );
    }

    private KitService newService(Executor io) {
        return new KitService(
                plugin,
                repository,
                newLoader(),
                () -> new ItemSpecParser(false),
                () -> mock(ItemLibrary.class),
                () -> mock(ItemDeliveryService.class),
                io,
                "server-a",
                playerDataDir
        );
    }

    @Test
    void loaderInstallsBundledStarterOnceAndValidatesUnknownKeysAndPaidKits() throws IOException {
        KitDefinitionLoader loader = newLoader();
        loader.installDefaults();

        File starterFile = new File(loader.directory(), "starter.yml");
        assertTrue(starterFile.isFile());

        // Deleting starter.yml and re-running installDefaults does not reinstall it
        assertTrue(starterFile.delete());
        loader.installDefaults();
        assertFalse(starterFile.exists(), ".defaults prevents reinstalling deleted default kit");

        // Write a valid free kit, a paid kit, and an invalid kit
        Files.writeString(new File(loader.directory(), "free.yml").toPath(), """
                name: free
                enabled: true
                cooldown-seconds: 60
                one-time: false
                cost: 0
                items:
                  - minecraft:diamond 2
                """, StandardCharsets.UTF_8);

        Files.writeString(new File(loader.directory(), "paid.yml").toPath(), """
                name: paid
                enabled: true
                cooldown-seconds: 0
                one-time: false
                cost: 25.5
                items:
                  - minecraft:emerald 1
                """, StandardCharsets.UTF_8);

        Files.writeString(new File(loader.directory(), "broken.yml").toPath(), """
                name: broken
                unknown-setting: true
                items:
                  - minecraft:stone 0
                """, StandardCharsets.UTF_8);

        KitDefinitionLoader.Loaded loaded = loader.load(new ItemSpecParser(false));
        assertEquals(Set.of("free", "paid"), loaded.definitions().keySet());
        assertFalse(loaded.definitions().get("free").paid());
        assertTrue(loaded.definitions().get("paid").paid());
        assertEquals(1, loaded.rejected().size());
        assertEquals("kits/broken.yml", loaded.rejected().get(0).file());
        assertTrue(loaded.rejected().get(0).diagnostics().stream().anyMatch(d -> d.contains("unknown-setting")));
    }

    @Test
    void falseDefaultKitPermissionsDenyPaidKitsAndEnforceAccessOnListCompleteAndClaim() throws IOException {
        KitDefinitionLoader loader = newLoader();
        Files.createDirectories(loader.directory().toPath());
        Files.writeString(new File(loader.directory(), "starter.yml").toPath(), """
                name: starter
                enabled: true
                cooldown-seconds: 60
                one-time: false
                cost: 0
                items:
                  - minecraft:stone_sword 1
                """, StandardCharsets.UTF_8);
        Files.writeString(new File(loader.directory(), "vip.yml").toPath(), """
                name: vip
                enabled: true
                cooldown-seconds: 60
                one-time: false
                cost: 100
                items:
                  - minecraft:diamond 1
                """, StandardCharsets.UTF_8);

        KitService service = newService(Runnable::run);
        assertTrue(service.reloadDefinitions());
        assertEquals(PermissionDefault.FALSE, permissions.get("allium.kits.starter").getDefault());
        assertEquals(PermissionDefault.FALSE, permissions.get("allium.kits.vip").getDefault());

        playerGrants.add("allium.kit");
        playerGrants.add("allium.kits");

        // Without allium.kits.starter, list and completion are empty and claim is refused
        assertTrue(service.completeKits(player, false, "").isEmpty());
        service.claim(player, player, "starter");
        verify(messages).send(player, Messages.KIT_NO_PERMISSION, "{name}", "starter");

        // Grant both starter and vip; vip is paid so it stays excluded from player completion/list and denied on claim
        playerGrants.add("allium.kits.starter");
        playerGrants.add("allium.kits.vip");
        assertEquals(List.of("starter"), service.completeKits(player, false, ""));
        service.listKits(player);
        verify(messages).send(player, Messages.KIT_LIST, "{kits}", "starter");

        service.claim(player, player, "vip");
        verify(messages).send(player, Messages.KIT_PAID_DENIED, "{name}", "vip");
    }

    @Test
    void joinRecoveryQuarantinesUnwrittenLiveReceiptAndFinalizesVerifiedDiskReceipt() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        KitReceipts.Claimed claimed = new KitReceipts.Claimed(deliveryId, token, "starter");
        String encoded = KitReceipts.format(List.of(claimed));
        pdc.put(new NamespacedKey(plugin, "kit_receipts"), encoded);

        KitService service = newService(Runnable::run);

        // 1. No playerdata/<uuid>.dat file on disk -> quarantine player, never touch repository
        var joined = KitService.class.getDeclaredMethod("joined", Player.class);
        joined.setAccessible(true);
        joined.invoke(service, player);
        verifyNoInteractions(repository);
        verify(player).kickPlayer(contains("inventory needs kit recovery"));

        // 2. Write matching playerdata/<uuid>.dat with allium:kit_receipts and verify recovery finishes claim
        Player player2 = mock(Player.class);
        UUID id2 = UUID.randomUUID();
        when(player2.getUniqueId()).thenReturn(id2);
        when(player2.getName()).thenReturn("Bob");
        when(player2.isOnline()).thenReturn(true);
        when(server.getPlayer(id2)).thenReturn(player2);
        Map<NamespacedKey, Object> pdc2 = new HashMap<>();
        pdc2.put(new NamespacedKey(plugin, "kit_receipts"), encoded);
        var container2 = MockItemStacks.container(pdc2);
        when(player2.getPersistentDataContainer()).thenReturn(container2);

        writePlayerDatWithKitReceipt(playerDataDir.resolve(id2 + ".dat"), encoded);
        KitDeliveryRecord row = new KitDeliveryRecord(
                deliveryId, id2, "starter", token, "server-a", "minecraft:stone_sword 1", 1,
                300_000L, false, false, 1_000L);
        when(repository.claimingFor(id2, "server-a")).thenReturn(List.of(row));
        when(repository.finishClaim(eq(deliveryId), eq(token), anyLong())).thenReturn(true);

        joined.invoke(service, player2);
        verify(repository).finishClaim(eq(deliveryId), eq(token), anyLong());
        verify(player2).saveData();
        assertNull(pdc2.get(new NamespacedKey(plugin, "kit_receipts")), "verified receipt is cleared after recovery");
    }

    private static void writePlayerDatWithKitReceipt(Path file, String receiptValue) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeByte(10); // root TAG_Compound
            out.writeUTF("");
            out.writeByte(10); // TAG_Compound BukkitValues
            out.writeUTF("BukkitValues");
            out.writeByte(8); // TAG_String
            out.writeUTF("allium:kit_receipts");
            out.writeUTF(receiptValue);
            out.writeByte(0); // end BukkitValues
            out.writeByte(0); // end root
        }
        Files.write(file, raw.toByteArray());
    }
}
