package codes.castled.allium.legacy;

import codes.castled.allium.xp.Experience;
import codes.castled.allium.xp.XpBottles;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

final class LegacyXpBottle implements Listener {
    private static final String USE = "allium.xpbottle";

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final BiPredicate<CommandSender, Player> canSee;
    private Material bottleType;
    private LegacyXpBottleData data;
    private boolean metadataReadFailed;

    LegacyXpBottle(JavaPlugin plugin, LegacyMessageManager messages, BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.messages = messages;
        this.canSee = canSee;
    }

    void enable() {
        bottleType = LegacyCompat.matchMaterial("EXPERIENCE_BOTTLE");
        if (bottleType == null) bottleType = LegacyCompat.matchMaterial("EXP_BOTTLE");
        try {
            data = LegacyXpBottleData.create(plugin.getServer());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            plugin.getLogger().warning("XP bottle metadata is unavailable: " + unavailable);
        }
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE)) return refuse(sender);
        if (args.length < 1 || args.length > 2) return false;
        boolean minting = args.length == 2;
        if (minting && !canMint(sender)) return refuse(sender);
        if (!minting && !(sender instanceof Player)) return false;
        if (bottleType == null || data == null) return unavailable(sender);

        Player target = minting ? plugin.getServer().getPlayerExact(args[1]) : (Player) sender;
        if (target == null || !canSee.test(sender, target)) {
            messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", args[1]);
            return true;
        }
        Experience.State before = minting ? null : LegacyExperienceCommand.state(target);
        boolean withdrawAll = !minting && "all".equalsIgnoreCase(args[0]);
        int amount;
        if (withdrawAll) {
            amount = (int) before.total();
        } else {
            OptionalInt parsed = XpBottles.parseAmount(args[0], minting,
                    (min, max) -> (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1));
            if (!parsed.isPresent()) {
                messages.send(sender, "utility.xp-invalid",
                        "{prefix} &f{input} &cis not an amount: use points like &f100 &cor levels like &f5L &c(up to level &f{max}&c).",
                        "{input}", args[0], "{max}", Integer.toString(Experience.MAX_LEVEL));
                return true;
            }
            amount = parsed.getAsInt();
        }
        if (before != null && (amount <= 0 || amount > before.total())) {
            messages.send(sender, "utility.xpbottle-not-enough", "{prefix} &cYou need &f{points} XP &cbut only have &f{current} XP&c.",
                    "{points}", Integer.toString(Math.max(1, amount)), "{current}", Long.toString(before.total()));
            return true;
        }

        String signer = sender instanceof Player ? sender.getName() : "Console";
        ItemStack bottle;
        try {
            bottle = createBottle(amount, signer);
        } catch (ReflectiveOperationException unavailable) {
            plugin.getLogger().warning("Could not write XP bottle metadata: " + unavailable);
            return unavailable(sender);
        }
        if (!target.getInventory().addItem(bottle).isEmpty()) {
            messages.send(sender, "utility.xpbottle-inventory-full", "{prefix} &c{player}'s inventory is full.", "{player}", target.getName());
            return true;
        }
        if (before != null) LegacyExperienceCommand.apply(target, Experience.fromTotal(before.total() - amount));

        String points = Integer.toString(amount);
        if (target == sender) {
            messages.send(sender, "utility.xpbottle-created", "{prefix} &aCreated an XP bottle for &f{points} XP&a.", "{points}", points);
        } else {
            messages.send(sender, "utility.xpbottle-given", "{prefix} &aGave an XP bottle for &f{points} XP &ato &f{player}&a.",
                    "{points}", points, "{player}", target.getName());
            messages.send(target, "utility.xpbottle-received", "{prefix} &aYou received an XP bottle for &f{points} XP &afrom &f{signer}&a.",
                    "{points}", points, "{signer}", signer);
        }
        return true;
    }

    private ItemStack createBottle(int amount, String signer) throws ReflectiveOperationException {
        ItemStack bottle = new ItemStack(bottleType);
        ItemMeta meta = bottle.getItemMeta();
        if (meta == null) throw new ReflectiveOperationException("The server returned no item metadata");
        data.write(meta, amount, signer);
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "XP Bottle " + ChatColor.GRAY + "(Right Click)");
        meta.setLore(Arrays.asList(ChatColor.WHITE + "Experience: " + ChatColor.GREEN + amount + " XP",
                ChatColor.WHITE + "Signer: " + ChatColor.YELLOW + signer));
        Enchantment glow = LegacyCompat.matchEnchantment("unbreaking");
        if (glow != null) {
            meta.addEnchant(glow, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        if (!bottle.setItemMeta(meta)) throw new ReflectiveOperationException("The server refused the XP bottle metadata");
        return bottle;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (data == null || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        boolean offHand = hand == EquipmentSlot.OFF_HAND;
        ItemStack held = offHand ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
        if (held == null || held.getType() != bottleType || held.getAmount() <= 0 || !held.hasItemMeta()) return;
        ItemMeta meta = held.getItemMeta();
        if (meta == null) return;
        int amount;
        try {
            amount = data.amount(meta);
        } catch (ReflectiveOperationException invalid) {
            if (!metadataReadFailed) {
                plugin.getLogger().warning("Could not read XP bottle metadata: " + invalid);
                metadataReadFailed = true;
            }
            return;
        }
        if (amount <= 0) return;
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);

        Experience.State before = LegacyExperienceCommand.state(player);
        if (before.total() >= Experience.MAX_POINTS) {
            messages.send(player, "utility.xpbottle-max-xp", "{prefix} &cYour experience is already at the maximum limit.");
            return;
        }
        long after = before.total() + amount;
        if (after > Experience.MAX_POINTS) {
            messages.send(player, "utility.xpbottle-capacity", "{prefix} &cYou don't have room for all the experience in this bottle.");
            return;
        }
        ItemStack remainder = null;
        if (held.getAmount() > 1) {
            remainder = held.clone();
            remainder.setAmount(held.getAmount() - 1);
        }
        if (offHand) inventory.setItemInOffHand(remainder);
        else inventory.setItemInMainHand(remainder);
        LegacyExperienceCommand.apply(player, Experience.fromTotal(after));
        messages.send(player, "utility.xpbottle-redeemed", "{prefix} &aRedeemed &f{points} XP&a.", "{points}", Integer.toString(amount));
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (!sender.hasPermission(USE)) return Collections.emptyList();
        if (args.length == 1) {
            return Arrays.asList("all", "100", "5L").stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length != 2 || !canMint(sender)) return Collections.emptyList();
        return plugin.getServer().getOnlinePlayers().stream().filter(player -> canSee.test(sender, player))
                .map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
    }

    private static boolean canMint(CommandSender sender) {
        return sender.hasPermission(USE + ".other") || sender.hasPermission(USE + ".others");
    }

    private boolean unavailable(CommandSender sender) {
        messages.send(sender, "utility.xpbottle-unavailable", "{prefix} &cXP bottle metadata is unavailable on this server.");
        return true;
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }
}
