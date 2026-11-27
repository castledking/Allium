package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.xp.Experience;
import codes.castled.allium.xp.XpBottles;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntBinaryOperator;

/**
 * {@code /xpbottle <amount|amountL|all> [player]}: converts player XP into a redeemable
 * {@link Material#EXPERIENCE_BOTTLE} carrying {@code allium:xp_voucher_amount} and
 * {@code allium:xp_voucher_signer} (compatible with legacy Allium XP bottles), or mints one for
 * another player with {@code allium.xpbottle.other} (supporting {@code min-max} ranges).
 */
public final class XpBottleCommand implements SpecCommandHandler, Listener {

    public static final String PERMISSION_USE = "allium.xpbottle";
    public static final String PERMISSION_OTHER = "allium.xpbottle.other";
    public static final String PERMISSION_OTHERS_ALIAS = "allium.xpbottle.others";
    public static final String KEY_AMOUNT = XpBottles.KEY_AMOUNT;
    public static final String KEY_SIGNER = XpBottles.KEY_SIGNER;

    private final PluginStart plugin;
    private final NamespacedKey amountKey;
    private final NamespacedKey signerKey;
    private final java.util.function.BiFunction<Integer, String, ItemStack> bottleFactory;

    public XpBottleCommand(PluginStart plugin) {
        this(plugin, null);
    }

    XpBottleCommand(PluginStart plugin, java.util.function.BiFunction<Integer, String, ItemStack> bottleFactory) {
        this.plugin = plugin;
        this.amountKey = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), KEY_AMOUNT);
        this.signerKey = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), KEY_SIGNER);
        this.bottleFactory = bottleFactory != null ? bottleFactory : this::createBottle;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.XPBOTTLE;
    }

    public NamespacedKey amountKey() {
        return amountKey;
    }

    public NamespacedKey signerKey() {
        return signerKey;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length < 1 || args.length > 2) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        boolean hasTarget = args.length == 2;
        if (hasTarget) {
            if (!canGiveOthers(sender)) {
                UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION);
                return;
            }
            OptionalInt parsed = parseAmount(args[0], true, (min, max) ->
                    min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1));
            if (parsed.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UTILITY_XP_INVALID,
                        "{input}", args[0], "{max}", Integer.toString(Experience.MAX_LEVEL));
                return;
            }
            Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, args[1]);
            if (target == null) return;
            int amount = parsed.getAsInt();
            String signer = sender instanceof Player p ? p.getName() : "Console";
            UtilitySupport.onOwner(plugin, target, () -> {
                if (!target.isOnline() || !UtilitySupport.guardInventoryNotBusy(plugin, sender, target)) return;
                ItemStack bottle = bottleFactory.apply(amount, signer);
                if (!target.getInventory().addItem(bottle).isEmpty()) {
                    UtilitySupport.send(plugin, sender, Messages.UTILITY_XPBOTTLE_INVENTORY_FULL,
                            "{player}", target.getName());
                    return;
                }
                String formatted = Integer.toString(amount);
                if (target.equals(sender)) {
                    UtilitySupport.send(plugin, sender, Messages.UTILITY_XPBOTTLE_CREATED, "{points}", formatted);
                } else {
                    UtilitySupport.send(plugin, sender, Messages.UTILITY_XPBOTTLE_GIVEN,
                            "{points}", formatted, "{player}", target.getName());
                    UtilitySupport.send(plugin, target, Messages.UTILITY_XPBOTTLE_RECEIVED,
                            "{points}", formatted, "{signer}", signer);
                }
            });
            return;
        }

        if (!(sender instanceof Player self)) {
            context.sendInvalidArgs(spec().consoleUsage());
            return;
        }
        boolean withdrawAll = args[0].equalsIgnoreCase("all");
        OptionalInt parsed = withdrawAll ? OptionalInt.empty() : parseAmount(args[0], false, (min, max) -> min);
        if (!withdrawAll && parsed.isEmpty()) {
            UtilitySupport.send(plugin, sender, Messages.UTILITY_XP_INVALID,
                    "{input}", args[0], "{max}", Integer.toString(Experience.MAX_LEVEL));
            return;
        }
        UtilitySupport.onOwner(plugin, self, () -> {
            if (!self.isOnline() || !UtilitySupport.guardInventoryNotBusy(plugin, self, self)) return;
            Experience.State before = XpCommand.state(self);
            long available = before.total();
            long requested = withdrawAll ? Math.min(Integer.MAX_VALUE, available) : parsed.getAsInt();
            if (requested <= 0 || available < requested) {
                UtilitySupport.send(plugin, self, Messages.UTILITY_XPBOTTLE_NOT_ENOUGH,
                        "{points}", Long.toString(Math.max(1L, requested)),
                        "{current}", Long.toString(available));
                return;
            }
            int amount = (int) requested;
            ItemStack bottle = bottleFactory.apply(amount, self.getName());
            if (!self.getInventory().addItem(bottle).isEmpty()) {
                UtilitySupport.send(plugin, self, Messages.UTILITY_XPBOTTLE_INVENTORY_FULL,
                        "{player}", self.getName());
                return;
            }
            XpCommand.apply(self, Experience.fromTotal(available - amount));
            UtilitySupport.send(plugin, self, Messages.UTILITY_XPBOTTLE_CREATED,
                    "{points}", Integer.toString(amount));
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Player player = event.getPlayer();
        ItemStack inHand = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() != Material.EXPERIENCE_BOTTLE || inHand.getAmount() <= 0) return;
        ItemMeta meta = inHand.getItemMeta();
        if (meta == null) return;
        Integer amount = meta.getPersistentDataContainer().get(amountKey, PersistentDataType.INTEGER);
        if (amount == null || amount <= 0) return;

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);

        Experience.State before = XpCommand.state(player);
        if (before.total() >= Experience.MAX_POINTS) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_XPBOTTLE_MAX_XP);
            return;
        }
        // Consume one bottle before granting XP so a failure cannot duplicate XP.
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        long afterTotal = Math.min(Experience.MAX_POINTS, before.total() + amount.longValue());
        XpCommand.apply(player, Experience.fromTotal(afterTotal));
        if (player.getLocation() != null) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.15f);
        }
        UtilitySupport.send(plugin, player, Messages.UTILITY_XPBOTTLE_REDEEMED,
                "{points}", Integer.toString(amount));
    }

    /** Creates a redeemable XP bottle item carrying the legacy-compatible PDC keys. */
    @SuppressWarnings("deprecation")
    public ItemStack createBottle(int xpAmount, String signer) {
        if (xpAmount <= 0) throw new IllegalArgumentException("xpAmount must be positive");
        String safeSigner = signer == null || signer.isBlank() ? "Console" : signer;
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(amountKey, PersistentDataType.INTEGER, xpAmount);
            meta.getPersistentDataContainer().set(signerKey, PersistentDataType.STRING, safeSigner);
            meta.setDisplayName("\u00a7b\u00a7lXP Bottle \u00a77(Right Click)");
            meta.setLore(List.of(
                    "\u00a7fExperience: \u00a7a" + xpAmount + " XP",
                    "\u00a7fSigner: \u00a7e" + safeSigner
            ));
            Enchantment glow = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static OptionalInt parseAmount(String raw, boolean allowRange, IntBinaryOperator randomInRange) {
        return XpBottles.parseAmount(raw, allowRange, randomInRange);
    }

    private static boolean canGiveOthers(CommandSender sender) {
        return !(sender instanceof Player)
                || sender.hasPermission(PERMISSION_OTHER)
                || sender.hasPermission(PERMISSION_OTHERS_ALIAS);
    }
}
