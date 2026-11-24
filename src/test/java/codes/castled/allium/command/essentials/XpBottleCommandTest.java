package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.xp.Experience;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XpBottleCommandTest {

    private PluginStart plugin;
    private Server server;
    private MessageManager messages;
    private Player player;
    private PlayerInventory inventory;
    private Set<String> grants;
    private int level;
    private float progress;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        messages = mock(MessageManager.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        grants = new HashSet<>(Set.of("allium.xpbottle"));

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);

        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alex");
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.hasPermission(anyString())).thenAnswer(call -> grants.contains(call.<String>getArgument(0)));
        when(player.getLevel()).thenAnswer(call -> level);
        when(player.getExp()).thenAnswer(call -> progress);
        doAnswer(call -> { level = call.getArgument(0); return null; }).when(player).setLevel(anyInt());
        doAnswer(call -> { progress = call.getArgument(0); return null; }).when(player).setExp(anyFloat());
    }

    @Test
    void parseAmountHandlesPointsLevelsAndRangesWithStrictBounds() {
        assertEquals(OptionalInt.of(250), XpBottleCommand.parseAmount("250", false, (a, b) -> a));
        assertEquals(OptionalInt.of((int) Experience.totalForLevel(10)), XpBottleCommand.parseAmount("10L", false, (a, b) -> a));
        assertEquals(OptionalInt.empty(), XpBottleCommand.parseAmount("100-200", false, (a, b) -> a),
                "ranges must be rejected on self-withdraw");
        assertEquals(OptionalInt.of(150), XpBottleCommand.parseAmount("100-200", true, (min, max) -> (min + max) / 2));
        assertEquals(OptionalInt.of((int) Experience.totalForLevel(10)),
                XpBottleCommand.parseAmount("5L-10L", true, (min, max) -> max));
        assertEquals(OptionalInt.empty(), XpBottleCommand.parseAmount("200-100", true, (a, b) -> a));
        assertEquals(OptionalInt.empty(), XpBottleCommand.parseAmount("0", true, (a, b) -> a));
        assertEquals(OptionalInt.empty(), XpBottleCommand.parseAmount("-5", true, (a, b) -> a));
        assertEquals(OptionalInt.empty(), XpBottleCommand.parseAmount("999999L", true, (a, b) -> a));
    }

    @Test
    void selfWithdrawDeductsXpOnlyAfterDeliveryAndSupportsAll() {
        AtomicInteger minted = new AtomicInteger();
        XpBottleCommand cmd = new XpBottleCommand(plugin, (amount, signer) -> {
            minted.set(amount);
            return mockBottle(new NamespacedKey(plugin, XpBottleCommand.KEY_AMOUNT), amount, 1);
        });
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        // Start at level 10 (160 XP)
        level = 10;
        progress = 0.0f;
        assertEquals(160L, XpCommand.state(player).total());

        // Insufficient XP refuses cleanly without touching inventory
        run(cmd, player, "500");
        verify(messages).send(player, Messages.UTILITY_XPBOTTLE_NOT_ENOUGH, "{points}", "500", "{current}", "160");
        verify(inventory, never()).addItem(any());
        assertEquals(160L, XpCommand.state(player).total());

        // Full inventory refuses without deducting any XP
        ItemStack rejected = mock(ItemStack.class);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>(Map.of(0, rejected)));
        run(cmd, player, "100");
        verify(messages).send(player, Messages.UTILITY_XPBOTTLE_INVENTORY_FULL, "{player}", "Alex");
        assertEquals(160L, XpCommand.state(player).total());

        // Successful withdraw of 100 XP leaves 60 XP
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        run(cmd, player, "100");
        assertEquals(100, minted.get());
        assertEquals(60L, XpCommand.state(player).total());
        verify(messages).send(player, Messages.UTILITY_XPBOTTLE_CREATED, "{points}", "100");

        // /xpbottle all withdraws remaining 60 XP down to 0
        run(cmd, player, "all");
        assertEquals(60, minted.get());
        assertEquals(0L, XpCommand.state(player).total());
    }

    @Test
    void adminGrantRequiresPermissionAndSupportsRangeWithoutDrainingSender() {
        AtomicInteger minted = new AtomicInteger();
        XpBottleCommand cmd = new XpBottleCommand(plugin, (amount, signer) -> {
            minted.set(amount);
            return mockBottle(new NamespacedKey(plugin, XpBottleCommand.KEY_AMOUNT), amount, 1);
        });
        when(server.getPlayer("Alex")).thenReturn(player);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        // Regular player without allium.xpbottle.other is refused
        run(cmd, player, "100", "Alex");
        verify(messages).send(player, Messages.NO_PERMISSION);

        // Console can mint a ranged XP bottle for Alex
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        run(cmd, console, "300-500", "Alex");
        assertTrue(minted.get() >= 300 && minted.get() <= 500, "minted=" + minted.get());
        verify(messages).send(eq(console), eq(Messages.UTILITY_XPBOTTLE_GIVEN),
                eq("{points}"), eq(Integer.toString(minted.get())), eq("{player}"), eq("Alex"));
        verify(messages).send(eq(player), eq(Messages.UTILITY_XPBOTTLE_RECEIVED),
                eq("{points}"), eq(Integer.toString(minted.get())), eq("{signer}"), eq("Console"));
    }

    @Test
    void rightClickRedeemConsumesOneBottleGrantsXpAndIgnoresPlainBottles() {
        XpBottleCommand cmd = new XpBottleCommand(plugin, null);
        level = 0;
        progress = 0.0f;

        // Stacked bottle (amount = 2, worth 160 XP each) decrements to 1 and grants level 10
        ItemStack stack = mockBottle(cmd.amountKey(), 160, 2);
        when(inventory.getItemInMainHand()).thenReturn(stack);
        PlayerInteractEvent click = mock(PlayerInteractEvent.class);
        when(click.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(click.getHand()).thenReturn(EquipmentSlot.HAND);
        when(click.getPlayer()).thenReturn(player);

        cmd.onInteract(click);
        verify(click).setCancelled(true);
        verify(click).setUseItemInHand(Event.Result.DENY);
        verify(stack).setAmount(1);
        assertEquals(160L, XpCommand.state(player).total());
        verify(messages).send(player, Messages.UTILITY_XPBOTTLE_REDEEMED, "{points}", "160");

        // Last bottle in off-hand clears off-hand slot
        ItemStack single = mockBottle(cmd.amountKey(), 40, 1);
        when(inventory.getItemInOffHand()).thenReturn(single);
        PlayerInteractEvent offhandClick = mock(PlayerInteractEvent.class);
        when(offhandClick.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(offhandClick.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(offhandClick.getPlayer()).thenReturn(player);

        cmd.onInteract(offhandClick);
        verify(inventory).setItemInOffHand(null);
        assertEquals(200L, XpCommand.state(player).total());

        // Plain vanilla bottle without PDC key is never cancelled
        ItemStack vanilla = mock(ItemStack.class);
        ItemMeta vanillaMeta = mock(ItemMeta.class);
        PersistentDataContainer emptyPdc = mock(PersistentDataContainer.class);
        when(vanilla.getType()).thenReturn(Material.EXPERIENCE_BOTTLE);
        when(vanilla.getAmount()).thenReturn(1);
        when(vanilla.getItemMeta()).thenReturn(vanillaMeta);
        when(vanillaMeta.getPersistentDataContainer()).thenReturn(emptyPdc);
        when(emptyPdc.get(cmd.amountKey(), PersistentDataType.INTEGER)).thenReturn(null);
        when(inventory.getItemInMainHand()).thenReturn(vanilla);

        PlayerInteractEvent vanillaClick = mock(PlayerInteractEvent.class);
        when(vanillaClick.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(vanillaClick.getHand()).thenReturn(EquipmentSlot.HAND);
        when(vanillaClick.getPlayer()).thenReturn(player);
        cmd.onInteract(vanillaClick);
        verify(vanillaClick, never()).setCancelled(true);
    }

    private static ItemStack mockBottle(NamespacedKey amountKey, int xpAmount, int stackSize) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.EXPERIENCE_BOTTLE);
        when(item.getAmount()).thenReturn(stackSize);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(amountKey, PersistentDataType.INTEGER)).thenReturn(xpAmount);
        return item;
    }

    private void run(XpBottleCommand command, CommandSender sender, String... args) {
        command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.XPBOTTLE, sender, "xpbottle", args));
    }
}
