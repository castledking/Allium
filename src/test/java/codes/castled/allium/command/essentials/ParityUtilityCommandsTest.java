package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParityUtilityCommandsTest {

    private PluginStart plugin;
    private Server server;
    private MessageManager messageManager;
    private CommandFamilyPolicy policy;
    private Command command;
    private Player alice;
    private Player bob;
    private ConsoleCommandSender console;
    private ModuleManager modules;
    private World world;
    private LanguageManager langManager;

    private static String containsPlain(String needle) {
        return argThat((String s) -> s != null && s.replaceAll("§.", "").contains(needle));
    }

    private void setVanished(Player target, boolean vanished) {
        if (vanished) {
            when(target.getMetadata("vanished")).thenReturn(List.of(new FixedMetadataValue(plugin, true)));
        } else {
            when(target.getMetadata("vanished")).thenReturn(List.of());
        }
    }

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        modules = mock(ModuleManager.class);
        world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getDescription()).thenReturn(
                new PluginDescriptionFile("Allium", "1.0.0-SNAPSHOT", "codes.castled.allium.PluginStart"));

        langManager = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(langManager.get(m)).thenReturn(m.getDefault());
        }
        messageManager = new MessageManager(plugin, langManager);
        when(plugin.getMessageManager()).thenReturn(messageManager);

        policy = new CommandFamilyPolicy();
        policy.registerSpecs(CoreCommandSpecs.UTILITY_SPECS);
        policy.registerSpecs(CoreCommandSpecs.PARITY_UTILITY_SPECS);
        policy.initializeStartup(List.of());

        command = mock(Command.class);
        alice = mock(Player.class);
        bob = mock(Player.class);
        console = mock(ConsoleCommandSender.class);

        UUID worldId = UUID.randomUUID();
        when(alice.getName()).thenReturn("Alice");
        when(bob.getName()).thenReturn("Bob");
        when(alice.getWorld()).thenReturn(world);
        when(bob.getWorld()).thenReturn(world);
        when(bob.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        when(bob.isOnline()).thenReturn(true);
        when(alice.isOnline()).thenReturn(true);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(worldId);
        when(server.getPlayer("Bob")).thenReturn(bob);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getWorlds()).thenReturn(List.of(world));
        doReturn(List.of(alice, bob)).when(server).getOnlinePlayers();
        when(alice.canSee(any(Player.class))).thenReturn(true);
        when(bob.canSee(any(Player.class))).thenReturn(true);
    }

    @Test
    void speedSetsWalkOrFlyAndEnforcesOthersAndVanish() {
        BukkitCommandRenderer renderer = new BukkitCommandRenderer(plugin, policy, new SpeedCommand(plugin));
        when(alice.hasPermission("allium.speed")).thenReturn(true);
        when(alice.isFlying()).thenReturn(true);

        renderer.onCommand(alice, command, "speed", new String[]{"2.5"});
        verify(alice).setFlySpeed(0.25f);

        // Vanished Bob cannot be targeted
        when(alice.hasPermission("allium.speed.others")).thenReturn(true);
        setVanished(bob, true);
        renderer.onCommand(alice, command, "speed", new String[]{"walk", "3", "Bob"});
        verify(bob, never()).setWalkSpeed(anyFloat());
        verify(alice).sendMessage(containsPlain("Bob not found"));

        setVanished(bob, false);
        renderer.onCommand(alice, command, "speed", new String[]{"walk", "10", "Bob"});
        verify(bob).setWalkSpeed(1.0f);
    }

    @Test
    void pingReportsVisibleTargetAndHidesVanished() {
        BukkitCommandRenderer renderer = new BukkitCommandRenderer(plugin, policy, new PingCommand(plugin));
        when(alice.hasPermission("allium.ping")).thenReturn(true);
        when(alice.hasPermission("allium.ping.others")).thenReturn(true);
        when(alice.getPing()).thenReturn(42);
        when(bob.getPing()).thenReturn(87);

        renderer.onCommand(alice, command, "ping", new String[0]);
        verify(alice).sendMessage(containsPlain("42ms"));

        renderer.onCommand(alice, command, "ping", new String[]{"Bob"});
        verify(alice).sendMessage(containsPlain("87ms"));

        setVanished(bob, true);
        renderer.onCommand(alice, command, "ping", new String[]{"Bob"});
        verify(alice).sendMessage(containsPlain("Bob not found"));
    }

    @Test
    void extBurnAndSmiteRespectBoundsAndStrikeEffect() {
        when(alice.hasPermission("allium.ext")).thenReturn(true);
        when(alice.hasPermission("allium.burn")).thenReturn(true);
        when(alice.hasPermission("allium.smite")).thenReturn(true);

        new BukkitCommandRenderer(plugin, policy, new ExtCommand(plugin))
                .onCommand(alice, command, "ext", new String[0]);
        verify(alice).setFireTicks(0);

        var burn = new BukkitCommandRenderer(plugin, policy, new BurnCommand(plugin));
        burn.onCommand(alice, command, "burn", new String[]{"Bob", "12"});
        verify(bob).setFireTicks(240);

        burn.onCommand(alice, command, "burn", new String[]{"Bob", "999"});
        verify(bob, times(1)).setFireTicks(anyInt());
        verify(alice).sendMessage(containsPlain("1 and 300"));

        new BukkitCommandRenderer(plugin, policy, new SmiteCommand(plugin))
                .onCommand(alice, command, "smite", new String[]{"Bob"});
        verify(world).strikeLightningEffect(bob.getLocation());
    }

    @Test
    void nvTogglesAndAppliesExplicitStates() {
        var nv = new BukkitCommandRenderer(plugin, policy, new NvCommand(plugin));
        when(alice.hasPermission("allium.nv")).thenReturn(true);
        when(alice.hasPermission("allium.nv.others")).thenReturn(true);
        when(alice.hasPotionEffect(PotionEffectType.NIGHT_VISION)).thenReturn(false);

        nv.onCommand(alice, command, "nv", new String[0]);
        verify(alice).addPotionEffect(argThat((PotionEffect e) -> e.getType().equals(PotionEffectType.NIGHT_VISION)));

        nv.onCommand(alice, command, "nv", new String[]{"off", "Bob"});
        verify(bob).removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    @Test
    void clearInventoryFiltersMaterialRespectsCapAndBusyLock() {
        var ci = new BukkitCommandRenderer(plugin, policy, new ClearInventoryCommand(plugin));
        when(alice.hasPermission("allium.clearinventory")).thenReturn(true);
        when(alice.hasPermission("allium.clearinventory.others")).thenReturn(true);

        PlayerInventory inv = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(inv);
        ItemStack dirt = mock(ItemStack.class);
        when(dirt.getType()).thenReturn(Material.DIRT);
        when(dirt.getAmount()).thenReturn(10);
        ItemStack stone = mock(ItemStack.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(stone.getAmount()).thenReturn(5);
        when(inv.getContents()).thenReturn(new ItemStack[]{dirt, stone});

        ci.onCommand(alice, command, "ci", new String[]{"Bob", "dirt", "4"});
        verify(dirt).setAmount(6);
        verify(stone, never()).setAmount(anyInt());
        verify(alice).sendMessage(containsPlain("Cleared 4 item(s) from Bob"));

        KitsModule kits = mock(KitsModule.class);
        KitService kitService = mock(KitService.class);
        when(modules.getModule("kits")).thenReturn(kits);
        when(kits.isEnabled()).thenReturn(true);
        when(kits.kits()).thenReturn(kitService);
        when(kitService.busy(bob)).thenReturn(true);

        ci.onCommand(alice, command, "ci", new String[]{"Bob"});
        verify(alice).sendMessage(containsPlain("busy"));
    }

    @Test
    void moreAndHatRespectBusyLockAndSwapCleanly() {
        var more = new BukkitCommandRenderer(plugin, policy, new MoreCommand(plugin));
        var hat = new BukkitCommandRenderer(plugin, policy, new HatCommand(plugin));
        when(alice.hasPermission("allium.more")).thenReturn(true);
        when(alice.hasPermission("allium.hat")).thenReturn(true);

        PlayerInventory inv = mock(PlayerInventory.class);
        when(alice.getInventory()).thenReturn(inv);

        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.GOLDEN_APPLE);
        when(stack.getAmount()).thenReturn(1);
        when(stack.getMaxStackSize()).thenReturn(64);
        when(inv.getItemInMainHand()).thenReturn(stack);

        more.onCommand(alice, command, "more", new String[]{"16"});
        verify(stack).setAmount(16);

        ItemStack helmet = mock(ItemStack.class);
        when(helmet.getType()).thenReturn(Material.DIAMOND_HELMET);
        when(helmet.getAmount()).thenReturn(1);
        ItemStack helmetClone = mock(ItemStack.class);
        ItemStack hatClone = mock(ItemStack.class);
        when(helmet.clone()).thenReturn(helmetClone);
        when(stack.clone()).thenReturn(hatClone);
        when(inv.getHelmet()).thenReturn(helmet);

        hat.onCommand(alice, command, "hat", new String[0]);
        verify(inv).setHelmet(same(hatClone));
        verify(inv).setItemInMainHand(same(helmetClone));
    }

    @Test
    void skullResolvesAndDeliversWithFallbackWarning() {
        SkullProfiles skulls = mock(SkullProfiles.class);
        PlayerProfile profile = mock(PlayerProfile.class);
        when(skulls.resolve("Notch")).thenReturn(CompletableFuture.completedFuture(
                new SkullProfiles.Resolved(profile, "name-only fallback", true)));

        ItemStack mockHead = mock(ItemStack.class);
        when(mockHead.getType()).thenReturn(Material.PLAYER_HEAD);
        SkullMeta meta = mock(SkullMeta.class);
        when(mockHead.getItemMeta()).thenReturn(meta);

        PlayerInventory inv = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(inv);
        when(inv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        var cmd = new BukkitCommandRenderer(plugin, policy, new SkullCommand(plugin, () -> skulls, () -> mockHead));
        when(alice.hasPermission("allium.skull")).thenReturn(true);
        when(alice.hasPermission("allium.skull.others")).thenReturn(true);

        cmd.onCommand(alice, command, "skull", new String[]{"Notch", "Bob"});
        verify(meta).setOwnerProfile(same(profile));
        verify(inv).addItem(same(mockHead));
        verify(alice).sendMessage(containsPlain("Gave Notch's head to Bob"));
        verify(alice).sendMessage(containsPlain("degraded"));
    }

    @Test
    void itemDbAndGcReportReadOnlyDiagnostics() {
        var itemDb = new BukkitCommandRenderer(plugin, policy, new ItemDbCommand(plugin));
        when(alice.hasPermission("allium.itemdb")).thenReturn(true);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(alice.getInventory()).thenReturn(inv);
        ItemStack pick = mock(ItemStack.class);
        when(pick.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        when(pick.getAmount()).thenReturn(1);
        when(pick.getMaxStackSize()).thenReturn(1);
        var dmg = mock(Damageable.class);
        when(dmg.getDamage()).thenReturn(10);
        when(pick.getItemMeta()).thenReturn(dmg);
        when(inv.getItemInMainHand()).thenReturn(pick);
        ItemService items = mock(ItemService.class);
        when(items.identify(pick)).thenReturn(Optional.of(
                codes.castled.allium.item.ItemRef.parse("allium:ruby_pickaxe")));
        when(plugin.getItemService()).thenReturn(items);

        itemDb.onCommand(alice, command, "itemdb", new String[0]);
        verify(alice).sendMessage(contains("Diamond Pickaxe"));
        verify(alice).sendMessage(contains("Durability:"));
        verify(alice).sendMessage(contains("Allium ID: allium:ruby_pickaxe"));

        when(console.hasPermission("allium.gc")).thenReturn(true);
        when(world.getLoadedChunks()).thenReturn(new Chunk[3]);
        when(world.getEntities()).thenReturn(List.of());
        new BukkitCommandRenderer(plugin, policy, new GcCommand(plugin, new TimePauseCoordinator(plugin)))
                .onCommand(console, command, "gc", new String[0]);
        verify(console).sendMessage(contains("Runtime uptime:"));
        verify(console).sendMessage(containsPlain("world: 3 chunks"));
    }

    @Test
    void gcHonorsConfiguredLanguageTextAndPrefix() {
        when(console.hasPermission("allium.gc")).thenReturn(true);
        for(var m:Messages.values())if(m.getKey().equals("utility.gc.runtime"))when(langManager.get(m)).thenReturn("{prefix} &aConfigured uptime {uptime}");
        new BukkitCommandRenderer(plugin,policy,new GcCommand(plugin,new TimePauseCoordinator(plugin))).onCommand(console,command,"gc",new String[0]);
        verify(console).sendMessage(contains("Configured uptime"));
    }

    @Test
    void timeDayNightPauseAndWeatherControlWorldAndRestoreOnClose() {
        var coordinator = new TimePauseCoordinator(plugin);
        var time = new BukkitCommandRenderer(plugin, policy, new TimeCommand(plugin, CoreCommandSpecs.TIME, coordinator));
        var day = new BukkitCommandRenderer(plugin, policy, new TimeCommand(plugin, CoreCommandSpecs.DAY, coordinator));
        var night = new BukkitCommandRenderer(plugin, policy, new TimeCommand(plugin, CoreCommandSpecs.NIGHT, coordinator));
        var weather = new BukkitCommandRenderer(plugin, policy, new WeatherCommand(plugin));

        when(alice.hasPermission(anyString())).thenReturn(true);
        when(world.getTime()).thenReturn(1000L);
        when(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE)).thenReturn(Boolean.TRUE);

        day.onCommand(alice, command, "day", new String[0]);
        verify(world).setTime(1000L);

        night.onCommand(alice, command, "night", new String[]{"world"});
        verify(world).setTime(13_000L);

        time.onCommand(alice, command, "time", new String[]{"pause", "on"});
        verify(world).setGameRule(GameRule.DO_DAYLIGHT_CYCLE, Boolean.FALSE);

        coordinator.close();
        verify(world).setGameRule(GameRule.DO_DAYLIGHT_CYCLE, Boolean.TRUE);

        weather.onCommand(alice, command, "weather", new String[]{"thunder", "120", "*"});
        verify(world).setStorm(true);
        verify(world).setThundering(true);
        verify(world).setWeatherDuration(2400);
        verify(world).setThunderDuration(2400);
    }

    @Test
    void enchantEnforcesUnsafePermissionRemovesLevelZeroAndAcceptsLegacyOrder() {
        Enchantment sharp = mock(Enchantment.class);
        when(sharp.getKey()).thenReturn(NamespacedKey.minecraft("sharpness"));
        when(sharp.getMaxLevel()).thenReturn(5);
        when(sharp.canEnchantItem(any())).thenReturn(true);

        var handler = new EnchantCommand(plugin, tok -> "sharpness".equalsIgnoreCase(tok)
                ? Optional.of(sharp)
                : Optional.empty());
        var renderer = new BukkitCommandRenderer(plugin, policy, handler);

        when(alice.hasPermission("allium.enchant")).thenReturn(true);
        when(alice.hasPermission("allium.enchant.others")).thenReturn(true);
        when(alice.hasPermission("allium.enchant.unsafe")).thenReturn(false);

        PlayerInventory inv = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(inv);
        ItemStack sword = mock(ItemStack.class);
        when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(sword.getAmount()).thenReturn(1);
        var meta = mock(Damageable.class);
        when(sword.getItemMeta()).thenReturn(meta);
        when(inv.getItemInMainHand()).thenReturn(sword);

        // Above-max blocked without allium.enchant.unsafe
        renderer.onCommand(alice, command, "enchant", new String[]{"sharpness", "10", "Bob"});
        verify(meta, never()).addEnchant(any(), anyInt(), anyBoolean());
        verify(alice).sendMessage(contains("allium.enchant.unsafe"));

        // Safe level in legacy order <player> <enchant> <level> succeeds
        renderer.onCommand(alice, command, "enchant", new String[]{"Bob", "sharpness", "5"});
        verify(meta).addEnchant(same(sharp), eq(5), eq(true));

        // Unsafe allowed when explicit permission granted
        when(alice.hasPermission("allium.enchant.unsafe")).thenReturn(true);
        renderer.onCommand(alice, command, "enchant", new String[]{"sharpness", "10", "Bob"});
        verify(meta).addEnchant(same(sharp), eq(10), eq(true));

        // Level 0 removes
        renderer.onCommand(alice, command, "enchant", new String[]{"sharpness", "0", "Bob"});
        verify(meta).removeEnchant(same(sharp));
    }

    @Test
    void disabledCommandFamilyBlocksExecutionAndCompletion() {
        policy.applyReload(List.of("ci", "extinguish"));
        var ci = new BukkitCommandRenderer(plugin, policy, new ClearInventoryCommand(plugin));
        var ext = new BukkitCommandRenderer(plugin, policy, new ExtCommand(plugin));

        when(alice.hasPermission(anyString())).thenReturn(true);
        ci.onCommand(alice, command, "clearinventory", new String[0]);
        verify(alice).sendMessage(contains("disabled"));
        verify(alice, never()).getInventory();

        ext.onCommand(alice, command, "ext", new String[0]);
        verify(alice, never()).setFireTicks(anyInt());
    }
}
