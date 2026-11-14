package codes.castled.allium.kit;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.pack.CoreItems;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Chest;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
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
    private ItemService itemService;
    private Player player;
    private UUID playerId;
    private PlayerInventory playerInventory;
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

        itemService = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), warnLogs::add);
        var manifest = CoreItems.loadManifest(getClass().getClassLoader());
        CoreItems.definitions(manifest).forEach(itemService::registerAlliumItem);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getItemService()).thenReturn(itemService);
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
        when(scheduler.isServerThread()).thenReturn(true);
        when(scheduler.serverExecutor()).thenReturn(Runnable::run);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return TaskHandle.NONE;
        });
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);

        when(server.getPluginManager()).thenReturn(pluginManager);
        Permission wildcardSpy = spy(new Permission("allium.kits.*", PermissionDefault.FALSE));
        doNothing().when(wildcardSpy).recalculatePermissibles();
        permissions.put("allium.kits.*", wildcardSpy);
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
        when(player.hasPermission(anyString())).thenAnswer(call -> {
            String perm = call.getArgument(0);
            if (playerGrants.contains(perm)) {
                return true;
            }
            if (perm.startsWith("allium.kits.") && !perm.equals("allium.kits.*")) {
                Permission wildcard = permissions.get("allium.kits.*");
                return wildcard != null && Boolean.TRUE.equals(wildcard.getChildren().get(perm))
                        && playerGrants.contains("allium.kits.*");
            }
            return false;
        });

        playerInventory = mock(PlayerInventory.class);
        when(playerInventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(player.getInventory()).thenReturn(playerInventory);

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
        return newService(io, mock(ItemLibrary.class), mock(ItemDeliveryService.class));
    }

    private KitService newService(Executor io, ItemLibrary library, ItemDeliveryService delivery) {
        return new KitService(
                plugin,
                repository,
                newLoader(),
                () -> new ItemSpecParser(false),
                () -> library,
                () -> delivery,
                io,
                "server-a",
                playerDataDir
        );
    }

    @Test
    void loaderInstallsBundledStarterOnceAndValidatesUnknownKeysDecimalCostsAndLegacyWarnings() throws IOException {
        KitDefinitionLoader loader = newLoader();
        loader.installDefaults();

        File starterFile = new File(loader.directory(), "starter.yml");
        assertTrue(starterFile.isFile());

        // Deleting starter.yml and re-running installDefaults does not reinstall it
        assertTrue(starterFile.delete());
        loader.installDefaults();
        assertFalse(starterFile.exists(), ".defaults prevents reinstalling deleted default kit");

        // Write a valid free kit, a decimal-text paid kit, a legacy numeric paid kit, and an invalid kit
        Files.writeString(new File(loader.directory(), "free.yml").toPath(), """
                name: free
                enabled: true
                cooldown-seconds: 60
                one-time: false
                cost: '0.00'
                items:
                  - minecraft:diamond 2
                """, StandardCharsets.UTF_8);

        Files.writeString(new File(loader.directory(), "paid.yml").toPath(), """
                name: paid
                enabled: true
                cooldown-seconds: 0
                one-time: false
                cost: '25.50'
                items:
                  - minecraft:emerald 1
                """, StandardCharsets.UTF_8);

        Files.writeString(new File(loader.directory(), "legacy.yml").toPath(), """
                name: legacy
                enabled: true
                cooldown-seconds: 0
                one-time: false
                cost: 50.0
                items:
                  - minecraft:gold_ingot 1
                """, StandardCharsets.UTF_8);

        Files.writeString(new File(loader.directory(), "broken.yml").toPath(), """
                name: broken
                unknown-setting: true
                items:
                  - minecraft:stone 0
                """, StandardCharsets.UTF_8);

        KitDefinitionLoader.Loaded loaded = loader.load(new ItemSpecParser(false));
        assertEquals(Set.of("free", "paid", "legacy"), loaded.definitions().keySet());
        assertFalse(loaded.definitions().get("free").paid());
        assertEquals("0.00", loaded.definitions().get("free").cost());
        assertTrue(loaded.definitions().get("paid").paid());
        assertEquals("25.50", loaded.definitions().get("paid").cost());
        assertTrue(loaded.definitions().get("legacy").paid());
        assertEquals("50.00", loaded.definitions().get("legacy").cost());
        assertTrue(warnLogs.stream().anyMatch(w -> w.contains("legacy.yml") && w.contains("numeric") && w.contains("cost")),
                "legacy numeric cost should emit a deprecation warning recommending quoted decimal text");
        assertEquals(1, loaded.rejected().size());
        assertEquals("kits/broken.yml", loaded.rejected().get(0).file());
        assertTrue(loaded.rejected().get(0).diagnostics().stream().anyMatch(d -> d.contains("unknown-setting")));
    }

    @Test
    void falseDefaultKitPermissionsWildcardParentPaidDenialAndTargetUsageOwnership() throws Exception {
        KitDefinitionLoader loader = newLoader();
        Files.createDirectories(loader.directory().toPath());
        Files.writeString(new File(loader.directory(), "starter.yml").toPath(), """
                name: starter
                enabled: true
                cooldown-seconds: 60
                one-time: false
                cost: '0.00'
                items:
                  - minecraft:stone_sword 1
                """, StandardCharsets.UTF_8);
        Files.writeString(new File(loader.directory(), "vip.yml").toPath(), """
                name: vip
                enabled: true
                cooldown-seconds: 60
                one-time: false
                cost: '100.00'
                items:
                  - minecraft:diamond 1
                """, StandardCharsets.UTF_8);

        ItemLibrary library = mock(ItemLibrary.class);
        ItemStack preparedSword = MockItemStacks.create(Material.STONE_SWORD);
        when(library.build(any(), anyBoolean())).thenReturn(new ItemLibrary.Prepared(
                List.of(new ItemLibrary.PreparedItem(preparedSword, null)),
                List.of()
        ));
        ItemDeliveryService delivery = mock(ItemDeliveryService.class);
        ItemDeliveryService.MailPlan plan = new ItemDeliveryService.MailPlan(
                new ItemStack[36], new ItemStack[36], List.of(), List.of(1));
        when(delivery.planMail(any(), any(), any())).thenReturn(plan);
        when(delivery.applyMail(any(), eq(plan))).thenReturn(true);

        KitService service = newService(Runnable::run, library, delivery);
        assertTrue(service.reloadDefinitions());
        service.onJoinEarly(new org.bukkit.event.player.PlayerJoinEvent(player, ""));
        assertEquals(PermissionDefault.FALSE, permissions.get("allium.kits.*").getDefault());
        assertEquals(PermissionDefault.FALSE, permissions.get("allium.kits.starter").getDefault());
        assertEquals(PermissionDefault.FALSE, permissions.get("allium.kits.vip").getDefault());
        assertTrue(Boolean.TRUE.equals(permissions.get("allium.kits.*").getChildren().get("allium.kits.starter")));

        playerGrants.add("allium.kit");
        playerGrants.add("allium.kits");

        // Without allium.kits.starter, list and completion are empty and claim is refused
        assertTrue(service.completeKits(player, false, "").isEmpty());
        service.claim(player, player, "starter");
        verify(messages).send(player, Messages.KIT_NO_PERMISSION, "{name}", "starter");

        // Grant wildcard parent allium.kits.* -> exposes starter, while paid vip remains denied
        playerGrants.add("allium.kits.*");
        assertEquals(List.of("starter"), service.completeKits(player, false, ""));
        service.listKits(player);
        verify(messages).send(player, Messages.KIT_LIST, "{kits}", "starter");

        service.claim(player, player, "vip");
        verify(messages).send(player, Messages.KIT_PAID_DENIED, "{name}", "vip");

        // Giving kit to another player applies cooldown/usage ownership to target UUID, not sender UUID
        Player target = mock(Player.class);
        UUID targetId = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Bob");
        when(target.isOnline()).thenReturn(true);
        when(server.getPlayer(targetId)).thenReturn(target);
        Map<NamespacedKey, Object> targetPdc = new HashMap<>();
        var targetContainer = MockItemStacks.container(targetPdc);
        when(target.getPersistentDataContainer()).thenReturn(targetContainer);
        doAnswer(inv -> {
            Object v = targetPdc.get(new NamespacedKey(plugin, "kit_receipts"));
            if (v instanceof String str) writePlayerDatWithKitReceipt(playerDataDir.resolve(targetId + ".dat"), str);
            return null;
        }).when(target).saveData();
        service.onJoinEarly(new org.bukkit.event.player.PlayerJoinEvent(target, ""));
        PlayerInventory targetInv = mock(PlayerInventory.class);
        when(targetInv.getStorageContents()).thenReturn(new ItemStack[36]);
        when(target.getInventory()).thenReturn(targetInv);
        playerGrants.add("allium.kit.others");
        when(repository.beginClaim(any(), anyLong())).thenAnswer(inv -> new KitRepository.BeginOutcome.Claiming(inv.getArgument(0)));
        when(repository.finishClaim(any(), any(), eq("server-a"), anyLong())).thenReturn(true);

        service.claim(player, target, "starter");
        verify(repository).beginClaim(argThat(rec ->
                rec.playerUuid().equals(targetId) && rec.bypassCooldown() && "server-a".equals(rec.claimOwner())), anyLong());
        verify(messages).send(player, Messages.KIT_GIVEN, "{name}", "starter", "{player}", "Bob");
    }

    @Test
    void createKitValidatesNamesEnforcesPrivilegedPermissionAndRefusesLossyItems() throws Exception {
        KitService service = newService(Runnable::run);
        assertTrue(service.reloadDefinitions());

        // 1. Path traversal / invalid kit names rejected without creating files
        for (String badName : List.of("../x", "a/b", "a\\b", "/abs", ".", "..", "", "a".repeat(33), "UpperCase", "bad.dot")) {
            service.createKit(player, badName, 0L, false);
            assertFalse(Files.exists(new File(dataFolder, "kits").toPath().resolve(badName + ".yml")),
                    "Invalid kit name should not write file: " + badName);
        }
        verify(messages, times(10)).send(player, Messages.KIT_NAME_INVALID);

        // 2. Privileged behavior item requires allium.give.privileged
        ItemStack obliterator = itemService.create(itemService.refForKey(ProviderKey.allium("phantom_obliterator")))
                .itemStack().orElseThrow();
        ItemStack[] slots = new ItemStack[36];
        slots[0] = obliterator;
        when(playerInventory.getStorageContents()).thenReturn(slots);

        service.createKit(player, "privkit", 60L, false);
        verify(messages).send(eq(player), eq(Messages.KIT_CAPTURE_PRIVILEGED),
                eq("{slot}"), eq("0"),
                eq("{item}"), anyString(),
                eq("{behavior}"), eq("allium:phantom_obliterator"),
                eq("{permission}"), eq("allium.give.privileged"));
        assertFalse(new File(new File(dataFolder, "kits"), "privkit.yml").exists());

        playerGrants.add("allium.give.privileged");
        service.createKit(player, "privkit", 60L, false);
        File privFile = new File(new File(dataFolder, "kits"), "privkit.yml");
        assertTrue(privFile.isFile());
        String privYaml = Files.readString(privFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(privYaml.contains("cost: '0.00'"), "Saved kit writes decimal-text cost");

        // 3. Lossy metadata cases are refused naming slot and unsupported data, without writing a file
        // 3a. Shulker box with contents
        ItemStack shulker = mock(ItemStack.class);
        when(shulker.getType()).thenReturn(Material.SHULKER_BOX);
        when(shulker.getAmount()).thenReturn(1);
        BlockStateMeta bsm = mock(BlockStateMeta.class);
        Chest chestState = mock(Chest.class);
        Inventory chestInv = mock(Inventory.class);
        ItemStack containedDiamond = MockItemStacks.create(Material.DIAMOND);
        when(chestInv.getContents()).thenReturn(new ItemStack[]{containedDiamond});
        when(chestState.getInventory()).thenReturn(chestInv);
        when(bsm.hasBlockState()).thenReturn(true);
        when(bsm.getBlockState()).thenReturn(chestState);
        when(shulker.getItemMeta()).thenReturn(bsm);
        when(shulker.hasItemMeta()).thenReturn(true);
        assertCaptureUnsupported(service, slots, 2, shulker, "shulker/container contents", "lossy_shulker");

        // 3b. Bundle with contents
        ItemStack bundle = mock(ItemStack.class);
        when(bundle.getType()).thenReturn(Material.BUNDLE);
        when(bundle.getAmount()).thenReturn(1);
        BundleMeta bundleMeta = mock(BundleMeta.class);
        when(bundleMeta.hasItems()).thenReturn(true);
        when(bundle.getItemMeta()).thenReturn(bundleMeta);
        when(bundle.hasItemMeta()).thenReturn(true);
        assertCaptureUnsupported(service, slots, 3, bundle, "bundle contents", "lossy_bundle");

        // 3c. Attribute modifiers
        ItemStack attrSword = MockItemStacks.create(Material.IRON_SWORD);
        ItemMeta attrMeta = attrSword.getItemMeta();
        com.google.common.collect.Multimap<Attribute, AttributeModifier> attrs =
                com.google.common.collect.ArrayListMultimap.create();
        attrs.put(Attribute.GENERIC_ATTACK_DAMAGE,
                new AttributeModifier("bonus", 5.0, AttributeModifier.Operation.ADD_NUMBER));
        when(attrMeta.hasAttributeModifiers()).thenReturn(true);
        when(attrMeta.getAttributeModifiers()).thenReturn(attrs);
        assertCaptureUnsupported(service, slots, 4, attrSword, "attribute modifiers", "lossy_attr");

        // 3d. Custom model data on vanilla item
        ItemStack cmdStick = MockItemStacks.create(
                Material.STICK, 1, new HashMap<>(), new AtomicReference<>(null), new AtomicReference<>(1234));
        assertCaptureUnsupported(service, slots, 5, cmdStick, "custom model data", "lossy_cmd");

        // 3e. Written book data
        ItemStack writtenBook = mock(ItemStack.class);
        when(writtenBook.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(writtenBook.getAmount()).thenReturn(1);
        BookMeta bookMeta = mock(BookMeta.class);
        when(bookMeta.hasTitle()).thenReturn(true);
        when(writtenBook.getItemMeta()).thenReturn(bookMeta);
        when(writtenBook.hasItemMeta()).thenReturn(true);
        assertCaptureUnsupported(service, slots, 6, writtenBook, "written book data", "lossy_book");
    }

    private void assertCaptureUnsupported(
            KitService service,
            ItemStack[] slots,
            int slot,
            ItemStack badStack,
            String expectedReasonSubstring,
            String kitName
    ) {
        java.util.Arrays.fill(slots, null);
        slots[slot] = badStack;
        KitItemCapture.CaptureOutcome direct = KitItemCapture.captureInventory(itemService, slots, true);
        assertInstanceOf(KitItemCapture.CaptureOutcome.Unsupported.class, direct);
        var unsupported = (KitItemCapture.CaptureOutcome.Unsupported) direct;
        assertEquals(slot, unsupported.slot());
        assertTrue(unsupported.reason().contains(expectedReasonSubstring),
                "Expected reason containing '" + expectedReasonSubstring + "' but got: " + unsupported.reason());

        service.createKit(player, kitName, 0L, false);
        verify(messages).send(eq(player), eq(Messages.KIT_CAPTURE_UNSUPPORTED),
                eq("{slot}"), eq(Integer.toString(slot)),
                eq("{reason}"), contains(expectedReasonSubstring));
        assertFalse(new File(new File(dataFolder, "kits"), kitName + ".yml").exists(),
                "Lossy kit must never be saved to disk");
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
        when(repository.finishClaim(eq(deliveryId), eq(token), eq("server-a"), anyLong())).thenReturn(true);

        joined.invoke(service, player2);
        verify(repository).finishClaim(eq(deliveryId), eq(token), eq("server-a"), anyLong());
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
