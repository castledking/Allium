package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PotionAndFireworkCommandTest {

    private PluginStart plugin;
    private MessageManager messages;
    private YamlConfiguration config;
    private Player player;
    private PlayerInventory inventory;
    private World world;
    private Set<String> perms;
    private ItemStack held;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        messages = mock(MessageManager.class);
        config = new YamlConfiguration();
        config.set("spawnmob.limit", 5);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));

        world = mock(World.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        perms = new HashSet<>(Set.of(
                "allium.potion", "allium.potion.apply", "allium.potions.*", "allium.potions.multiple",
                "allium.firework", "allium.firework.fire", "allium.firework.multiple"
        ));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alex");
        when(player.getInventory()).thenReturn(inventory);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        Location eye = new Location(world, 10, 65.62, 10, 0f, -30f);
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        when(inventory.getItemInMainHand()).thenAnswer(inv -> held);
        doAnswer(inv -> {
            held = inv.getArgument(0);
            return null;
        }).when(inventory).setItemInMainHand(any());
    }

    private ItemStack mockPotion(Material type, List<PotionEffect> initialEffects) {
        List<PotionEffect> effects = new ArrayList<>(initialEffects);
        Color[] colorBox = new Color[1];
        Material[] typeBox = new Material[]{type};
        PotionMeta meta = mock(PotionMeta.class);
        when(meta.getCustomEffects()).thenAnswer(inv -> List.copyOf(effects));
        when(meta.hasCustomEffects()).thenAnswer(inv -> !effects.isEmpty());
        when(meta.addCustomEffect(any(PotionEffect.class), anyBoolean())).thenAnswer(inv -> {
            PotionEffect eff = inv.getArgument(0);
            effects.removeIf(e -> e.getType().equals(eff.getType()));
            effects.add(eff);
            return true;
        });
        when(meta.clearCustomEffects()).thenAnswer(inv -> {
            boolean had = !effects.isEmpty();
            effects.clear();
            return had;
        });
        doAnswer(inv -> {
            colorBox[0] = inv.getArgument(0);
            return null;
        }).when(meta).setColor(any());
        when(meta.getColor()).thenAnswer(inv -> colorBox[0]);
        when(meta.hasDisplayName()).thenReturn(true); // skip CraftBukkit reflection path in unit test

        ItemStack stack = mock(ItemStack.class);
        when(stack.getAmount()).thenReturn(1);
        when(stack.getType()).thenAnswer(inv -> typeBox[0]);
        doAnswer(inv -> {
            typeBox[0] = inv.getArgument(0);
            return null;
        }).when(stack).setType(any(Material.class));
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(any(ItemMeta.class))).thenReturn(true);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    private ItemStack mockFirework(int initialPower, List<FireworkEffect> initialEffects) {
        List<FireworkEffect> effects = new ArrayList<>(initialEffects);
        int[] powerBox = new int[]{initialPower};
        FireworkMeta meta = createFireworkMetaMock(powerBox, effects);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getAmount()).thenReturn(1);
        when(stack.getType()).thenReturn(Material.FIREWORK_ROCKET);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(any(ItemMeta.class))).thenReturn(true);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    private FireworkMeta createFireworkMetaMock(int[] powerBox, List<FireworkEffect> effects) {
        FireworkMeta meta = mock(FireworkMeta.class);
        when(meta.getPower()).thenAnswer(inv -> powerBox[0]);
        doAnswer(inv -> {
            powerBox[0] = inv.getArgument(0);
            return null;
        }).when(meta).setPower(anyInt());
        when(meta.getEffects()).thenAnswer(inv -> List.copyOf(effects));
        when(meta.getEffectsSize()).thenAnswer(inv -> effects.size());
        when(meta.hasEffects()).thenAnswer(inv -> !effects.isEmpty());
        doAnswer(inv -> {
            FireworkEffect eff = inv.getArgument(0);
            effects.add(eff);
            return null;
        }).when(meta).addEffect(any(FireworkEffect.class));
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<FireworkEffect> iterable = inv.getArgument(0);
            iterable.forEach(effects::add);
            return null;
        }).when(meta).addEffects(any(Iterable.class));
        doAnswer(inv -> {
            effects.clear();
            return null;
        }).when(meta).clearEffects();
        when(meta.clone()).thenAnswer(inv -> createFireworkMetaMock(new int[]{powerBox[0]}, new ArrayList<>(effects)));
        return meta;
    }

    // ---- /potion tests ----

    @Test
    void potionRejectsConsoleAndNonPotionItems() {
        PotionCommand cmd = new PotionCommand(plugin);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, console, "potion", new String[]{"clear"}));
        verify(messages).send(console, Messages.PLAYER_ONLY);

        ItemStack stick = mock(ItemStack.class);
        when(stick.getType()).thenReturn(Material.STICK);
        held = stick;
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[]{"clear"}));
        verify(messages).send(player, Messages.POTION_HOLD);
    }

    @Test
    void potionNoArgsListsPermittedEffectsOrRefusesWhenNone() {
        PotionCommand cmd = new PotionCommand(plugin);
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[0]));
        verify(messages).send(eq(player), eq(Messages.POTION_LIST), eq("{effects}"), anyString());

        perms.remove("allium.potions.*");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[0]));
        verify(messages).send(player, Messages.POTION_NONE_ALLOWED);
    }

    @Test
    void potionAddsEffectsInAnyOrderSupportsAliasesInfiniteDurationSplashAndColor() {
        PotionCommand cmd = new PotionCommand(plugin);
        held = mockPotion(Material.POTION, List.of());

        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion",
                new String[]{"duration:120", "effect:speed", "power:2", "splash:true", "color:#FF55AA"}));

        assertEquals(Material.SPLASH_POTION, held.getType());
        PotionMeta meta = (PotionMeta) held.getItemMeta();
        assertEquals(1, meta.getCustomEffects().size());
        PotionEffect eff = meta.getCustomEffects().get(0);
        assertEquals(PotionEffectType.SPEED, eff.getType());
        assertEquals(1, eff.getAmplifier(), "power:2 maps to amplifier 1 (Level II)");
        assertEquals(2400, eff.getDuration(), "120s -> 2400 ticks");
        assertEquals(Color.fromRGB(0xFF55AA), meta.getColor());

        // Add second effect via short tokens + alias + infinite duration + revert splash
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "elixer",
                new String[]{"e:regen", "a:3", "d:-1", "s:false"}));

        assertEquals(Material.POTION, held.getType());
        assertEquals(2, meta.getCustomEffects().size());
        PotionEffect regen = meta.getCustomEffects().get(1);
        assertEquals(PotionEffectType.REGENERATION, regen.getType());
        assertEquals(3, regen.getAmplifier(), "amplifier:3 is raw 0-based");
        assertEquals(PotionEffect.INFINITE_DURATION, regen.getDuration());
    }

    @Test
    void potionEnforcesPerEffectAndMultiplePermissionsAndLeavesItemUntouchedOnError() {
        PotionCommand cmd = new PotionCommand(plugin);
        held = mockPotion(Material.POTION, List.of());
        perms.remove("allium.potions.*");
        perms.add("allium.potions.speed");
        perms.remove("allium.potions.multiple");

        // Denied effect
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion",
                new String[]{"effect:strength", "power:1", "duration:30"}));
        verify(messages).send(player, Messages.POTION_NO_EFFECT_PERM, "{effect}", "strength");
        assertTrue(((PotionMeta) held.getItemMeta()).getCustomEffects().isEmpty());

        // Allowed single effect
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion",
                new String[]{"effect:speed", "power:1", "duration:30"}));
        assertEquals(1, ((PotionMeta) held.getItemMeta()).getCustomEffects().size());

        // Second distinct effect blocked without allium.potions.multiple
        perms.add("allium.potions.regeneration");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion",
                new String[]{"effect:regen", "power:1", "duration:30"}));
        verify(messages).send(player, Messages.POTION_NO_MULTIPLE);
        assertEquals(1, ((PotionMeta) held.getItemMeta()).getCustomEffects().size());

        // Incomplete triplet refused without mutation
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion",
                new String[]{"effect:speed", "power:2"}));
        verify(messages).send(player, Messages.POTION_INVALID);
    }

    @Test
    void potionClearAndApplyRespectPermissionsAndEmptyState() {
        PotionCommand cmd = new PotionCommand(plugin);
        PotionEffect speed = new PotionEffect(PotionEffectType.SPEED, 600, 1);
        held = mockPotion(Material.TIPPED_ARROW, List.of(speed));

        // Apply denied without permission
        perms.remove("allium.potion.apply");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[]{"apply"}));
        verify(messages).send(player, Messages.NO_PERMISSION);
        verify(player, never()).addPotionEffect(any(), anyBoolean());

        // Apply succeeds with permission
        perms.add("allium.potion.apply");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[]{"apply"}));
        verify(player).addPotionEffect(speed, true);
        verify(messages).send(player, Messages.POTION_APPLIED, "{count}", "1");

        // Clear removes effects; applying after clear reports POTION_NO_EFFECTS
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[]{"clear"}));
        assertTrue(((PotionMeta) held.getItemMeta()).getCustomEffects().isEmpty());
        verify(messages).send(player, Messages.POTION_CLEARED);

        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.POTION, player, "potion", new String[]{"apply"}));
        verify(messages).send(player, Messages.POTION_NO_EFFECTS);
    }

    @Test
    void potionTabCompletionGuidesNextToken() {
        PotionCommand cmd = new PotionCommand(plugin);
        List<String> arg1 = cmd.complete(player, new String[]{""});
        assertTrue(arg1.contains("clear"));
        assertTrue(arg1.contains("apply"));
        assertTrue(arg1.contains("effect:speed"));

        List<String> arg2 = cmd.complete(player, new String[]{"effect:speed", ""});
        assertTrue(arg2.contains("power:1"));
        assertTrue(arg2.contains("amplifier:0"));

        List<String> arg3 = cmd.complete(player, new String[]{"effect:speed", "power:2", ""});
        assertTrue(arg3.contains("duration:120"));
        assertTrue(arg3.contains("duration:-1"));
    }

    // ---- /firework tests ----

    @Test
    void fireworkRejectsConsoleAndNonFireworkItems() {
        FireworkCommand cmd = new FireworkCommand(plugin);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, console, "firework", new String[]{"clear"}));
        verify(messages).send(console, Messages.PLAYER_ONLY);

        ItemStack stick = mock(ItemStack.class);
        when(stick.getType()).thenReturn(Material.STICK);
        held = stick;
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"clear"}));
        verify(messages).send(player, Messages.FIREWORK_HOLD);
    }

    @Test
    void fireworkMetaPowerAndClearWorkAsInEssentials() {
        FireworkCommand cmd = new FireworkCommand(plugin);
        held = mockFirework(1, List.of());

        // Set power
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"power", "3"}));
        assertEquals(3, ((FireworkMeta) held.getItemMeta()).getPower());
        verify(messages).send(player, Messages.FIREWORK_POWER_SET, "{power}", "3");

        // Reject out-of-range power
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"power", "9"}));
        verify(messages).send(player, Messages.FIREWORK_INVALID_POWER, "{min}", "0", "{max}", "4");
        assertEquals(3, ((FireworkMeta) held.getItemMeta()).getPower());

        // Add full effect (colors + hex fade + shape + twinkle & trail)
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework",
                new String[]{"color:red,blue", "fade:white,#00FF00", "shape:star", "effect:twinkle,trail"}));

        FireworkMeta meta = (FireworkMeta) held.getItemMeta();
        assertEquals(1, meta.getEffectsSize());
        FireworkEffect effect = meta.getEffects().get(0);
        assertEquals(List.of(DyeColor.RED.getFireworkColor(), DyeColor.BLUE.getFireworkColor()), effect.getColors());
        assertEquals(List.of(DyeColor.WHITE.getFireworkColor(), Color.fromRGB(0x00FF00)), effect.getFadeColors());
        assertEquals(FireworkEffect.Type.STAR, effect.getType());
        assertTrue(effect.hasFlicker());
        assertTrue(effect.hasTrail());
        verify(messages).send(player, Messages.FIREWORK_ADDED, "{count}", "1", "{total}", "1");

        // Clear removes all effects
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"clear"}));
        assertEquals(0, meta.getEffectsSize());
        verify(messages).send(player, Messages.FIREWORK_CLEARED);
    }

    @Test
    void fireworkEnforcesMultiplePermissionColorRequirementAndInvalidFormat() {
        FireworkCommand cmd = new FireworkCommand(plugin);
        held = mockFirework(1, List.of());
        perms.remove("allium.firework.multiple");

        // Missing primary color
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework",
                new String[]{"shape:large", "effect:trail"}));
        verify(messages).send(player, Messages.FIREWORK_COLOR_REQUIRED);
        assertEquals(0, ((FireworkMeta) held.getItemMeta()).getEffectsSize());

        // Invalid color
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework",
                new String[]{"color:notacolor"}));
        verify(messages).send(player, Messages.FIREWORK_INVALID_FORMAT, "{value}", "notacolor", "{key}", "color");

        // Two charges in one command blocked without allium.firework.multiple
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework",
                new String[]{"color:red", "shape:ball", "color:blue", "shape:burst"}));
        verify(messages).send(player, Messages.FIREWORK_NO_MULTIPLE);
        assertEquals(0, ((FireworkMeta) held.getItemMeta()).getEffectsSize());

        // Allowed once granted
        perms.add("allium.firework.multiple");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework",
                new String[]{"color:red", "shape:ball", "color:blue", "shape:burst"}));
        assertEquals(2, ((FireworkMeta) held.getItemMeta()).getEffectsSize());
        assertEquals(FireworkEffect.Type.BALL, ((FireworkMeta) held.getItemMeta()).getEffects().get(0).getType());
        assertEquals(FireworkEffect.Type.BURST, ((FireworkMeta) held.getItemMeta()).getEffects().get(1).getType());
    }

    @Test
    void fireworkFireLaunchesClampedCountAndDirectionalAimWithoutMutatingHeldStack() {
        FireworkCommand cmd = new FireworkCommand(plugin);
        held = mockFirework(3, List.of(FireworkEffect.builder().withColor(Color.RED).build()));
        List<Firework> spawned = new ArrayList<>();
        when(world.spawn(any(Location.class), eq(Firework.class))).thenAnswer(inv -> {
            Firework fw = mock(Firework.class);
            spawned.add(fw);
            return fw;
        });

        // Permission check
        perms.remove("allium.firework.fire");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"fire"}));
        verify(messages).send(player, Messages.NO_PERMISSION);
        assertTrue(spawned.isEmpty());

        // Clamped launch (limit=5)
        perms.add("allium.firework.fire");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"fire", "99"}));
        assertEquals(5, spawned.size());
        verify(messages).send(player, Messages.FIREWORK_FIRE_LIMIT, "{limit}", "5");
        verify(messages).send(player, Messages.FIREWORK_FIRED, "{count}", "5");

        // Directional aim launch caps spawned rocket power to 1 without altering held firework's power (3)
        spawned.clear();
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.FIREWORK, player, "firework", new String[]{"fire", "aim"}));
        assertEquals(1, spawned.size());
        verify(spawned.get(0)).setVelocity(any(Vector.class));
        ArgumentCaptor<FireworkMeta> launchedMeta = ArgumentCaptor.forClass(FireworkMeta.class);
        verify(spawned.get(0)).setFireworkMeta(launchedMeta.capture());
        assertEquals(1, launchedMeta.getValue().getPower(), "directed launch caps entity power to 1");
        assertEquals(3, ((FireworkMeta) held.getItemMeta()).getPower(), "held firework power must stay 3");
    }

    @Test
    void fireworkTabCompletionSupportsCommaChainedColorsShapesAndEffects() {
        FireworkCommand cmd = new FireworkCommand(plugin);
        List<String> root = cmd.complete(player, new String[]{""});
        assertTrue(root.containsAll(List.of("clear", "power", "color:", "fire")));

        List<String> colors = cmd.complete(player, new String[]{"color:red,bl"});
        assertTrue(colors.contains("color:red,blue"));
        assertTrue(colors.contains("color:red,black"));

        List<String> shapes = cmd.complete(player, new String[]{"color:red", "shape:s"});
        assertEquals(List.of("shape:star"), shapes);

        List<String> effects = cmd.complete(player, new String[]{"color:red", "effect:t"});
        assertTrue(effects.contains("effect:trail"));
        assertTrue(effects.contains("effect:twinkle"));
    }
}
