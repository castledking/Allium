package codes.castled.allium.tool.powertool;

import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.LangMessage;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.tool.definition.ToolEnvironment;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PowerToolBehaviorTest {

    @TempDir
    Path tempDir;

    private ItemService items;
    private ProtectionService protection;
    private AtomicLong clock;
    private List<Runnable> scheduledLater;
    private List<String> sentMessageKeys;
    private PowerToolBehavior behavior;
    private World world;

    private static final ToolEnvironment ENV = new ToolEnvironment() {
        @Override public boolean isCoreItem(String localId) { return false; }
        @Override public Optional<String> coreItemBehavior(String localId) { return Optional.empty(); }
        @Override public boolean isItemMaterial(String material) {
            try { return Material.matchMaterial(material) != null; } catch (Exception e) { return false; }
        }
        @Override public boolean isEntityType(String type) { return true; }
        @Override public boolean isParticle(String particle) { return true; }
        @Override public boolean hasLangKey(String key) { return true; }
    };

    @BeforeEach
    void setUp() {
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> {});
        PluginManager pm = mock(PluginManager.class);
        protection = new ProtectionService(List.of(), pm, msg -> {});
        clock = new AtomicLong(100_000L);
        scheduledLater = new ArrayList<>();
        sentMessageKeys = new ArrayList<>();
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { scheduledLater.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { scheduledLater.add(task); return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) { throw new UnsupportedOperationException(); }
        };
        behavior = new PowerToolBehavior(
                items,
                protection,
                scheduler,
                (p, msg, repl) -> sentMessageKeys.add(msg.key()),
                diag -> {},
                clock::get,
                new Random(42L),
                tempDir.resolve("tools").toFile(),
                () -> ENV,
                PowerToolSettings.DEFAULT
        );
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
    }

    private Player mockPlayer(String name, Set<String> perms) {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        PlayerInventory inv = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 10, 64, 20));
        when(player.getInventory()).thenReturn(inv);
        when(player.hasPermission(anyString())).thenAnswer(invoc -> perms.contains(invoc.getArgument(0)));
        when(player.performCommand(anyString())).thenReturn(true);
        return player;
    }

    @Test
    void inlineSignedToolExecutesDelaysCooldownsAndBlocksTampering() {
        Player player = mockPlayer("Alex", Set.of(PowerToolBehavior.PERM_GLOBAL_USE));
        ItemStack stick = MockItemStacks.create(Material.STICK);

        PowerToolAction immediate = new PowerToolAction(
                PowerToolAction.TYPE_COMMAND, "/say hi {player}", false,
                0L, PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of());
        PowerToolAction delayed = new PowerToolAction(
                PowerToolAction.TYPE_COMMAND, "/say later {player}", false,
                0L, PowerToolCooldownScope.PLAYER, null, 20L, null, Map.of());
        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT)
                .withCooldown(5000L, PowerToolCooldownScope.PLAYER, null, true)
                .withActionAdded(immediate)
                .withActionAdded(delayed);
        assertTrue(behavior.writeInline(stick, PowerToolParameters.EMPTY.withBinding(PowerToolTrigger.RIGHT, binding), null));

        // First trigger runs immediate command, schedules delayed command, sets visual cooldown
        assertTrue(behavior.handleTrigger(player, stick, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(player).performCommand("say hi Alex");
        verify(player).setCooldown(Material.STICK, 100);
        assertEquals(1, scheduledLater.size());
        scheduledLater.get(0).run();
        verify(player).performCommand("say later Alex");

        // Second trigger inside 5s cooldown is blocked with cooldown message (after debounce passes)
        clock.addAndGet(500L);
        assertTrue(behavior.handleTrigger(player, stick, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(player, times(1)).performCommand("say hi Alex");
        assertTrue(sentMessageKeys.contains("tool.powertool.cooldown"));

        // Tampering with signed PDC state causes ItemService to reject it completely!
        clock.addAndGet(10_000L);
        PersistentDataContainer pdc = stick.getItemMeta().getPersistentDataContainer();
        pdc.set(new org.bukkit.NamespacedKey("allium", "state_pt"), PersistentDataType.STRING, "{\"bindings\":{}}");
        assertNull(behavior.resolve(stick), "tampered inline powertool must be rejected by HMAC verification");
        assertFalse(behavior.handleTrigger(player, stick, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
    }

    @Test
    void usesRechargeConsumeAndToggleWork() {
        Player player = mockPlayer("Alex", Set.of(PowerToolBehavior.PERM_GLOBAL_USE));
        AtomicReference<ItemStack> mainHand = new AtomicReference<>(MockItemStacks.create(Material.BLAZE_ROD));
        PlayerInventory inv = player.getInventory();
        doAnswer(i -> { mainHand.set(i.getArgument(0)); return null; }).when(inv).setItemInMainHand(any());

        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT)
                .withActionAdded(new PowerToolAction(PowerToolAction.TYPE_COMMAND, "/ping", false,
                        0L, PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of()));
        PowerToolParameters params = new PowerToolParameters(new PowerToolUses(2, false, 10, true), 0L, Map.of(PowerToolTrigger.RIGHT, binding));
        behavior.writeInline(mainHand.get(), params, null);

        // 2 uses succeed
        assertTrue(behavior.handleTrigger(player, mainHand.get(), PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        assertTrue(behavior.handleTrigger(player, mainHand.get(), PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(player, times(2)).performCommand("ping");

        // 3rd use is recharging
        assertTrue(behavior.handleTrigger(player, mainHand.get(), PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(player, times(2)).performCommand("ping");
        assertTrue(sentMessageKeys.contains("tool.powertool.recharging"));

        // After 10s recharge, refills and fires again
        clock.addAndGet(10_000L);
        assertTrue(behavior.handleTrigger(player, mainHand.get(), PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(player, times(3)).performCommand("ping");

        // Toggle off blocks firing
        assertFalse(behavior.togglePlayer(player.getUniqueId()));
        assertFalse(behavior.handleTrigger(player, mainHand.get(), PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(player, times(3)).performCommand("ping");
        assertTrue(behavior.togglePlayer(player.getUniqueId()));
    }

    @Test
    void disarmActionRemovesMobEquipmentAndRespectsNoOp() {
        Player player = mockPlayer("Alex", Set.of(PowerToolBehavior.PERM_GLOBAL_USE));
        ItemStack hoe = MockItemStacks.create(Material.GOLDEN_HOE);
        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.ON_INTERACT_ENTITY)
                .withActionAdded(new PowerToolAction(PowerToolAction.TYPE_DISARM, null, false,
                        0L, PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of("drop-rule", "always")));
        behavior.writeInline(hoe, new PowerToolParameters(new PowerToolUses(2, false, 0, true), 0L,
                Map.of(PowerToolTrigger.ON_INTERACT_ENTITY, binding)), null);

        Mob zombie = mock(Mob.class);
        EntityEquipment eq = mock(EntityEquipment.class);
        PersistentDataContainer zombiePdc = mock(PersistentDataContainer.class);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        when(zombie.getWorld()).thenReturn(world);
        when(zombie.getLocation()).thenReturn(new Location(world, 12, 64, 20));
        when(zombie.getEquipment()).thenReturn(eq);
        when(zombie.getPersistentDataContainer()).thenReturn(zombiePdc);
        ItemStack sword = MockItemStacks.create(Material.IRON_SWORD);
        when(eq.getItem(EquipmentSlot.HAND)).thenReturn(sword);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Cancellable event = new Cancellable() {
            @Override public boolean isCancelled() { return cancelled.get(); }
            @Override public void setCancelled(boolean cancel) { cancelled.set(cancel); }
        };

        assertTrue(behavior.handleTrigger(player, hoe, PowerToolBinding.HAND_MAIN, PowerToolTrigger.ON_INTERACT_ENTITY,
                null, zombie, event, null));
        verify(eq).setItem(EquipmentSlot.HAND, null);
        verify(eq).setItemInMainHandDropChance(0.0F);
        verify(world).dropItemNaturally(any(Location.class), any(ItemStack.class));
        assertEquals("1", items.getState(hoe, PowerToolBehavior.STATE_USES).orElse(""));
    }

    @Test
    void obliterateActionKillsNearbyUnnamedPhantomsOnly() {
        Player player = mockPlayer("Alex", Set.of(PowerToolBehavior.PERM_GLOBAL_USE));
        ItemStack wand = MockItemStacks.create(Material.BLAZE_ROD);
        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT)
                .withActionAdded(new PowerToolAction(PowerToolAction.TYPE_OBLITERATE, null, false,
                        0L, PowerToolCooldownScope.PLAYER, null, 0L, null,
                        Map.of("radius", 32, "entity-types", List.of("phantom"), "require-unnamed", true)));
        behavior.writeInline(wand, PowerToolParameters.EMPTY.withBinding(PowerToolTrigger.RIGHT, binding), null);

        LivingEntity unnamedPhantom = mock(LivingEntity.class);
        when(unnamedPhantom.getType()).thenReturn(EntityType.PHANTOM);
        when(unnamedPhantom.isValid()).thenReturn(true);
        when(unnamedPhantom.isDead()).thenReturn(false);
        when(unnamedPhantom.getHealth()).thenReturn(20.0D);
        when(unnamedPhantom.getLocation()).thenReturn(new Location(world, 15, 70, 20));
        doAnswer(inv -> { when(unnamedPhantom.isDead()).thenReturn(true); return null; })
                .when(unnamedPhantom).damage(anyDouble(), eq(player));

        LivingEntity namedPhantom = mock(LivingEntity.class);
        when(namedPhantom.getType()).thenReturn(EntityType.PHANTOM);
        when(namedPhantom.isValid()).thenReturn(true);
        when(namedPhantom.isDead()).thenReturn(false);
        when(namedPhantom.getCustomName()).thenReturn("Pet Phantom");
        when(namedPhantom.getLocation()).thenReturn(new Location(world, 15, 70, 20));

        when(player.getNearbyEntities(32.0D, 32.0D, 32.0D)).thenReturn(List.of(unnamedPhantom, namedPhantom));

        assertTrue(behavior.handleTrigger(player, wand, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        verify(unnamedPhantom).damage(anyDouble(), eq(player));
        verify(namedPhantom, never()).damage(anyDouble(), any());
    }

    @Test
    void customRegisteredActionSaveReloadGiveAndDeleteFlow() {
        AtomicInteger customHits = new AtomicInteger();
        behavior.actions().register("custom_pulse", (ctx, action) -> {
            customHits.incrementAndGet();
            return true;
        });

        Player player = mockPlayer("Alex", Set.of(PowerToolBehavior.PERM_GLOBAL_USE, "allium.tool.pulse_wand.use"));
        ItemStack held = MockItemStacks.create(Material.BLAZE_ROD);
        held.getItemMeta().setDisplayName("§dPulse Wand");
        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT)
                .withActionAdded(new PowerToolAction("custom_pulse", "burst", false,
                        0L, PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of()));
        behavior.writeInline(held, PowerToolParameters.EMPTY.withBinding(PowerToolTrigger.RIGHT, binding), null);

        // Save to tools/pulse_wand.yml
        PowerToolBehavior.SaveResult saved = behavior.saveHeldAsTool("pulse_wand", held, false);
        assertTrue(saved.saved(), () -> "Save failed: " + saved.message());
        File file = tempDir.resolve("tools/pulse_wand.yml").toFile();
        assertTrue(file.isFile());

        // Refuses overwrite without flag
        assertFalse(behavior.saveHeldAsTool("pulse_wand", held, false).saved());
        assertTrue(behavior.saveHeldAsTool("pulse_wand", held, true).saved());

        // Give via createToolItem mints a freshly signed stack that executes the custom action
        ResolutionResult given = behavior.createToolItem("pulse_wand", 1);
        assertTrue(given.isResolved());
        ItemStack minted = given.itemStack().orElseThrow();
        assertTrue(behavior.handleTrigger(player, minted, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        assertEquals(1, customHits.get());

        // Hot-reload from disk preserves definition and minted item still works
        assertEquals(1, behavior.reloadPowerTools());
        clock.addAndGet(500L);
        assertTrue(behavior.handleTrigger(player, minted, PowerToolBinding.HAND_MAIN, PowerToolTrigger.RIGHT, null, null, null, null));
        assertEquals(2, customHits.get());

        // Delete removes file and unregisters
        assertTrue(behavior.deleteSavedTool("pulse_wand").saved());
        assertFalse(file.exists());
        assertTrue(behavior.registryTool("pulse_wand").isEmpty());
    }

    @Test
    void enableAndDisableToggleToolFilesOnTheFlyAndPreserveComments() throws Exception {
        // Create a commented staple tool file (lazy_axe.yml) starting disabled
        File toolsDir = tempDir.resolve("tools").toFile();
        assertTrue(toolsDir.mkdirs() || toolsDir.isDirectory());
        File lazyAxe = new File(toolsDir, "lazy_axe.yml");
        java.nio.file.Files.writeString(lazyAxe.toPath(),
                "# Lazy Axe comment\nschema: 1\nid: allium:lazy_axe\nname: Lazy Axe\nbehavior: allium:fell_tree\nenabled: false\n");

        // Also save a powertool (starts enabled)
        ItemStack held = MockItemStacks.create(Material.STICK);
        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT)
                .withActionAdded(new PowerToolAction(PowerToolAction.TYPE_COMMAND, "/ping", false,
                        0L, PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of()));
        behavior.writeInline(held, PowerToolParameters.EMPTY.withBinding(PowerToolTrigger.RIGHT, binding), null);
        assertTrue(behavior.saveHeldAsTool("ping_stick", held, false).saved());

        // listAllToolFiles shows both enabled and disabled tools
        List<PowerToolBehavior.ToolSummary> listed = behavior.listAllToolFiles();
        assertTrue(listed.contains(new PowerToolBehavior.ToolSummary("lazy_axe", false)));
        assertTrue(listed.contains(new PowerToolBehavior.ToolSummary("ping_stick", true)));

        // Enable lazy_axe on the fly -> updates enabled: true and keeps header comment
        AtomicInteger reloadCalls = new AtomicInteger();
        PowerToolBehavior.EnableResult enabledLazy = behavior.setToolEnabled("lazy_axe", true, reloadCalls::incrementAndGet);
        assertEquals(PowerToolBehavior.EnableOutcome.CHANGED, enabledLazy.outcome());
        assertEquals(1, reloadCalls.get());
        String lazyText = java.nio.file.Files.readString(lazyAxe.toPath());
        assertTrue(lazyText.contains("# Lazy Axe comment"));
        assertTrue(lazyText.contains("enabled: true"));

        // Enabling again reports ALREADY_IN_STATE
        assertEquals(PowerToolBehavior.EnableOutcome.ALREADY_IN_STATE,
                behavior.setToolEnabled("lazy_axe", true, reloadCalls::incrementAndGet).outcome());

        // Disable ping_stick on the fly -> unregisters it live from PowerToolBehavior
        assertTrue(behavior.registryTool("ping_stick").isPresent());
        PowerToolBehavior.EnableResult disabledPing = behavior.setToolEnabled("ping_stick", false, null);
        assertEquals(PowerToolBehavior.EnableOutcome.CHANGED, disabledPing.outcome());
        assertTrue(behavior.registryTool("ping_stick").isEmpty());

        // Re-enable ping_stick -> hot-registers it back immediately
        assertEquals(PowerToolBehavior.EnableOutcome.CHANGED,
                behavior.setToolEnabled("ping_stick", true, null).outcome());
        assertTrue(behavior.registryTool("ping_stick").isPresent());

        // Aliases /tool and /tools are declared on CoreCommandSpecs.POWERTOOL
        assertTrue(codes.castled.allium.command.spec.CoreCommandSpecs.POWERTOOL.aliases().contains("tool"));
        assertTrue(codes.castled.allium.command.spec.CoreCommandSpecs.POWERTOOL.aliases().contains("tools"));
    }
}
