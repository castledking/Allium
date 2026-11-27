package codes.castled.allium.legacy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.TreeType;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Ocelot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 essentials utilities, item editing, world tools, and fun commands:
 * {@code /repair}, {@code /disposal}, {@code /anvil}, {@code /grindstone}, {@code /cartographytable},
 * {@code /stonecutter}, {@code /loom}, {@code /smithing}, {@code /rename}, {@code /lore}, {@code /enchant},
 * {@code /itemflag}, {@code /more}, {@code /itemdb}, {@code /condense}, {@code /book}, {@code /skull},
 * {@code /give}, {@code /i}, {@code /xp}, {@code /ext}, {@code /burn}, {@code /smite}, {@code /nv},
 * {@code /glow}, {@code /pvp}, {@code /rest}, {@code /compass}, {@code /ptime}, {@code /pweather},
 * {@code /break}, {@code /spawner}, {@code /tree}, {@code /bigtree}, {@code /remove}, {@code /spawnmob},
 * {@code /gc}, {@code /broadcastworld}, {@code /info}, {@code /help}, {@code /fireball}, {@code /antioch},
 * {@code /nuke}, {@code /explode}, {@code /kittycannon}.
 */
final class LegacyUtilitiesModule implements Listener {

    private final JavaPlugin plugin;
    private final LegacyBukkitRuntime runtime;
    private final LegacyRecipeCommand recipes;
    private final LegacyFireworkCommand fireworks;
    private final LegacyPotionCommand potions;
    private final Set<UUID> pvpDisabled = new HashSet<UUID>();

    LegacyUtilitiesModule(JavaPlugin plugin, LegacyBukkitRuntime runtime) {
        this.recipes = new LegacyRecipeCommand(plugin.getServer());
        this.fireworks = new LegacyFireworkCommand(runtime.configManager(), runtime.messageManager());
        this.potions = new LegacyPotionCommand(runtime.messageManager());
        this.plugin = plugin;
        this.runtime = runtime;
    }

    void disable() {
        pvpDisabled.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        Player attacker = null;
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) event.getDamager();
            if (proj.getShooter() instanceof Player) {
                attacker = (Player) proj.getShooter();
            }
        }
        if (attacker == null || attacker.equals(victim)) return;
        if (pvpDisabled.contains(victim.getUniqueId()) || pvpDisabled.contains(attacker.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "PvP is disabled for one of you.");
        }
    }

    boolean handles(String name) {
        switch (name) {
            case "repair":
            case "recipe":
            case "firework":
            case "potion":
            case "disposal":
            case "anvil":
            case "grindstone":
            case "cartographytable":
            case "stonecutter":
            case "loom":
            case "smithing":
            case "rename":
            case "lore":
            case "enchant":
            case "itemflag":
            case "more":
            case "itemdb":
            case "condense":
            case "book":
            case "skull":
            case "give":
            case "i":
            case "xp":
            case "ext":
            case "burn":
            case "smite":
            case "nv":
            case "glow":
            case "pvp":
            case "rest":
            case "compass":
            case "ptime":
            case "pweather":
            case "break":
            case "spawner":
            case "tree":
            case "bigtree":
            case "remove":
            case "spawnmob":
            case "gc":
            case "broadcastworld":
            case "fireball":
            case "antioch":
            case "nuke":
            case "explode":
            case "kittycannon":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "repair": return handleRepair(sender, args);
            case "recipe": return recipes.execute(sender, args);
            case "firework": return fireworks.execute(sender, args);
            case "potion": return potions.execute(sender, args);
            case "disposal": return handleDisposal(sender);
            case "anvil": return handleWorkstation(sender, "allium.anvil", "ANVIL", "openAnvil", "Anvil");
            case "grindstone": return handleWorkstation(sender, "allium.grindstone", "GRINDSTONE", "openGrindstone", "Grindstone");
            case "cartographytable": return handleWorkstation(sender, "allium.cartographytable", "CARTOGRAPHY", "openCartographyTable", "Cartography Table");
            case "stonecutter": return handleWorkstation(sender, "allium.stonecutter", "STONECUTTER", "openStonecutter", "Stonecutter");
            case "loom": return handleWorkstation(sender, "allium.loom", "LOOM", "openLoom", "Loom");
            case "smithing": return handleWorkstation(sender, "allium.smithing", "SMITHING", "openSmithingTable", "Smithing Table");
            case "rename": return handleRename(sender, args);
            case "lore": return handleLore(sender, args);
            case "enchant": return handleEnchant(sender, args);
            case "itemflag": return handleItemFlag(sender, args);
            case "more": return handleMore(sender);
            case "itemdb": return handleItemDb(sender, args);
            case "condense": return handleCondense(sender);
            case "book": return handleBook(sender);
            case "skull": return handleSkull(sender, args);
            case "give": return handleGive(sender, args, false);
            case "i": return handleGive(sender, args, true);
            case "xp": return handleXp(sender, args);
            case "ext": return handleExt(sender, args);
            case "burn": return handleBurn(sender, args);
            case "smite": return handleSmite(sender, args);
            case "nv": return handleNightVision(sender, args);
            case "glow": return handleGlow(sender, args);
            case "pvp": return handlePvp(sender, args);
            case "rest": return handleRest(sender, args);
            case "compass": return handleCompass(sender);
            case "ptime": return handlePlayerTime(sender, args);
            case "pweather": return handlePlayerWeather(sender, args);
            case "break": return handleBreak(sender);
            case "spawner": return handleSpawner(sender, args);
            case "tree": return handleTree(sender, args, false);
            case "bigtree": return handleTree(sender, args, true);
            case "remove": return handleRemove(sender, args);
            case "spawnmob": return handleSpawnMob(sender, args);
            case "gc": return handleGc(sender);
            case "broadcastworld": return handleBroadcastWorld(sender, args);
            case "help": return handleHelp(sender, args);
            case "fireball": return handleFireball(sender);
            case "antioch": return handleAntioch(sender);
            case "nuke": return handleNuke(sender, args);
            case "explode": return handleExplode(sender, args);
            case "kittycannon": return handleKittyCannon(sender);
            default: return false;
        }
    }

    List<String> onTabComplete(CommandSender sender, String name, String[] args, String prefix) {
        switch (name) {
            case "recipe":
                return args.length == 1 && sender.hasPermission("allium.recipe")
                        ? recipes.complete(prefix) : Collections.emptyList();
            case "firework":
                return sender.hasPermission("allium.firework") ? fireworks.complete(sender, args, prefix)
                        : Collections.emptyList();
            case "potion":
                return sender.hasPermission("allium.potion") ? potions.complete(sender, args, prefix)
                        : Collections.emptyList();
            case "repair":
                return args.length == 1 ? filter(Arrays.asList("hand", "all"), prefix) : Collections.<String>emptyList();
            case "lore":
                return args.length == 1 ? filter(Arrays.asList("add", "set", "remove", "clear"), prefix) : Collections.<String>emptyList();
            case "itemflag":
                if (args.length == 1) {
                    List<String> flags = new ArrayList<String>();
                    for (ItemFlag f : ItemFlag.values()) flags.add(f.name().toLowerCase(Locale.ROOT));
                    return filter(flags, prefix);
                }
                return Collections.emptyList();
            case "xp":
                if (args.length == 1) return filter(Arrays.asList("show", "give", "set", "take"), prefix);
                if (args.length == 2) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "give":
                if (args.length == 1) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "ext":
            case "burn":
            case "smite":
            case "nv":
            case "glow":
            case "pvp":
            case "rest":
            case "skull":
            case "nuke":
            case "explode":
                return args.length == 1 ? onlinePlayers(prefix) : Collections.<String>emptyList();
            case "ptime":
                if (args.length == 1) return filter(Arrays.asList("day", "noon", "night", "midnight", "reset"), prefix);
                if (args.length == 2) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "pweather":
                if (args.length == 1) return filter(Arrays.asList("clear", "rain", "reset"), prefix);
                if (args.length == 2) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "remove":
                return args.length == 1 ? filter(Arrays.asList("drops", "items", "mobs", "monsters", "all"), prefix) : Collections.<String>emptyList();
            case "broadcastworld":
                if (args.length == 1) {
                    List<String> worlds = new ArrayList<String>();
                    for (World w : plugin.getServer().getWorlds()) worlds.add(w.getName());
                    return filter(worlds, prefix);
                }
                return Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleRepair(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can repair items.");
            return true;
        }
        if (!sender.hasPermission("allium.repair")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /repair.");
            return true;
        }
        Player player = (Player) sender;
        boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
        if (all && !sender.hasPermission("allium.repair.all")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to repair all items.");
            return true;
        }
        if (!all) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!LegacyCompat.repairItem(hand)) {
                sender.sendMessage(ChatColor.RED + "Held item cannot be repaired or is already at full durability.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Repaired held item.");
            return true;
        }
        int repaired = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (LegacyCompat.repairItem(item)) repaired++;
        }
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (LegacyCompat.repairItem(armor)) repaired++;
        }
        if (repaired == 0) {
            sender.sendMessage(ChatColor.YELLOW + "No damaged items found to repair.");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Repaired " + repaired + " item(s).");
        }
        return true;
    }

    private boolean handleDisposal(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open disposal.");
            return true;
        }
        if (!sender.hasPermission("allium.disposal")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /disposal.");
            return true;
        }
        Inventory inv = Bukkit.createInventory((Player) sender, 36, ChatColor.DARK_RED + "Disposal");
        ((Player) sender).openInventory(inv);
        return true;
    }

    private boolean handleWorkstation(CommandSender sender, String perm, String invType, String paperMethod, String display) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open a " + display + ".");
            return true;
        }
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to open a " + display + ".");
            return true;
        }
        if (!LegacyCompat.openWorkstation((Player) sender, invType, paperMethod)) {
            sender.sendMessage(ChatColor.YELLOW + display + " is not available on this Minecraft version.");
        }
        return true;
    }

    private boolean handleRename(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can rename items.");
            return true;
        }
        if (!sender.hasPermission("allium.rename")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /rename.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /rename <name>");
            return true;
        }
        Player p = (Player) sender;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Hold an item to rename.");
            return true;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) return true;
        String colored = LegacyCompat.color(joinArgs(args, 0));
        meta.setDisplayName(colored);
        hand.setItemMeta(meta);
        sender.sendMessage(ChatColor.GREEN + "Renamed item to " + colored + ChatColor.GREEN + ".");
        return true;
    }

    private boolean handleLore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can edit item lore.");
            return true;
        }
        if (!sender.hasPermission("allium.lore")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /lore.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /lore <add <text>|set <line> <text>|remove <line>|clear>");
            return true;
        }
        Player p = (Player) sender;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Hold an item to edit its lore.");
            return true;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) return true;
        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<String>(meta.getLore())
                : new ArrayList<String>();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("clear")) {
            meta.setLore(null);
            hand.setItemMeta(meta);
            sender.sendMessage(ChatColor.GREEN + "Cleared item lore.");
            return true;
        }
        if (sub.equals("add") && args.length >= 2) {
            lore.add(LegacyCompat.color(joinArgs(args, 1)));
            meta.setLore(lore);
            hand.setItemMeta(meta);
            sender.sendMessage(ChatColor.GREEN + "Added lore line #" + lore.size() + ".");
            return true;
        }
        if (sub.equals("set") && args.length >= 3) {
            int idx;
            try { idx = Integer.parseInt(args[1]) - 1; } catch (NumberFormatException e) { idx = -1; }
            if (idx < 0 || idx > 32) {
                sender.sendMessage(ChatColor.RED + "Invalid line number.");
                return true;
            }
            while (lore.size() <= idx) lore.add("");
            lore.set(idx, LegacyCompat.color(joinArgs(args, 2)));
            meta.setLore(lore);
            hand.setItemMeta(meta);
            sender.sendMessage(ChatColor.GREEN + "Updated lore line #" + (idx + 1) + ".");
            return true;
        }
        if (sub.equals("remove") && args.length >= 2) {
            int idx;
            try { idx = Integer.parseInt(args[1]) - 1; } catch (NumberFormatException e) { idx = -1; }
            if (idx < 0 || idx >= lore.size()) {
                sender.sendMessage(ChatColor.RED + "Line #" + args[1] + " does not exist.");
                return true;
            }
            lore.remove(idx);
            meta.setLore(lore.isEmpty() ? null : lore);
            hand.setItemMeta(meta);
            sender.sendMessage(ChatColor.GREEN + "Removed lore line #" + (idx + 1) + ".");
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /lore <add <text>|set <line> <text>|remove <line>|clear>");
        return true;
    }

    private boolean handleEnchant(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can enchant items.");
            return true;
        }
        if (!sender.hasPermission("allium.enchant")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /enchant.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /enchant <enchantment> [level]");
            return true;
        }
        Player p = (Player) sender;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Hold an item to enchant.");
            return true;
        }
        Enchantment ench = LegacyCompat.matchEnchantment(args[0]);
        if (ench == null) {
            sender.sendMessage(ChatColor.RED + "Unknown enchantment '" + args[0] + "'.");
            return true;
        }
        int level = 1;
        if (args.length >= 2) {
            try {
                level = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Level must be an integer.");
                return true;
            }
        }
        if (level <= 0) {
            hand.removeEnchantment(ench);
            sender.sendMessage(ChatColor.GREEN + "Removed enchantment " + ench.getName() + ".");
            return true;
        }
        hand.addUnsafeEnchantment(ench, Math.min(255, level));
        sender.sendMessage(ChatColor.GREEN + "Applied " + ench.getName() + " " + Math.min(255, level) + ".");
        return true;
    }

    private boolean handleItemFlag(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can edit item flags.");
            return true;
        }
        if (!sender.hasPermission("allium.itemflag")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /itemflag.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /itemflag <flag>");
            return true;
        }
        Player p = (Player) sender;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Hold an item to toggle an item flag.");
            return true;
        }
        ItemFlag flag;
        try {
            flag = ItemFlag.valueOf(args[0].toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Unknown item flag '" + args[0] + "'.");
            return true;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) return true;
        if (meta.hasItemFlag(flag)) {
            meta.removeItemFlags(flag);
            hand.setItemMeta(meta);
            sender.sendMessage(ChatColor.GRAY + "Removed flag " + flag.name() + ".");
        } else {
            meta.addItemFlags(flag);
            hand.setItemMeta(meta);
            sender.sendMessage(ChatColor.GREEN + "Added flag " + flag.name() + ".");
        }
        return true;
    }

    private boolean handleMore(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /more.");
            return true;
        }
        if (!sender.hasPermission("allium.more")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /more.");
            return true;
        }
        Player p = (Player) sender;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Hold an item to fill its stack.");
            return true;
        }
        hand.setAmount(Math.max(1, hand.getMaxStackSize()));
        sender.sendMessage(ChatColor.GREEN + "Filled stack to " + hand.getAmount() + ".");
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleItemDb(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.itemdb")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /itemdb.");
            return true;
        }
        if (args.length > 0) {
            Material m = LegacyCompat.matchMaterial(args[0]);
            if (m == null) {
                sender.sendMessage(ChatColor.RED + "Unknown material '" + args[0] + "'.");
                return true;
            }
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Item: " + ChatColor.WHITE + m.name()
                    + ChatColor.GRAY + " (maxStack=" + m.getMaxStackSize() + ", maxDurability=" + m.getMaxDurability() + ")");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Usage: /itemdb <material>");
            return true;
        }
        ItemStack hand = ((Player) sender).getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Hold an item or pass a material name.");
            return true;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Item: " + ChatColor.WHITE + hand.getType().name()
                + ChatColor.GRAY + " x" + hand.getAmount() + " (data=" + hand.getDurability() + ", maxStack=" + hand.getMaxStackSize() + ")");
        return true;
    }

    private boolean handleCondense(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can condense items.");
            return true;
        }
        if (!sender.hasPermission("allium.condense")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /condense.");
            return true;
        }
        Player p = (Player) sender;
        Map<String, String> recipes = new HashMap<String, String>();
        recipes.put("COAL", "COAL_BLOCK");
        recipes.put("IRON_INGOT", "IRON_BLOCK");
        recipes.put("GOLD_INGOT", "GOLD_BLOCK");
        recipes.put("DIAMOND", "DIAMOND_BLOCK");
        recipes.put("EMERALD", "EMERALD_BLOCK");
        recipes.put("REDSTONE", "REDSTONE_BLOCK");
        recipes.put("WHEAT", "HAY_BLOCK");
        recipes.put("SLIME_BALL", "SLIME_BLOCK");
        recipes.put("GOLD_NUGGET", "GOLD_INGOT");
        recipes.put("IRON_NUGGET", "IRON_INGOT");

        int totalBlocks = 0;
        for (Map.Entry<String, String> entry : recipes.entrySet()) {
            Material src = LegacyCompat.matchMaterial(entry.getKey());
            Material dst = LegacyCompat.matchMaterial(entry.getValue());
            if (src == null || dst == null) continue;
            int count = 0;
            for (ItemStack item : p.getInventory().getStorageContents()) {
                if (item != null && item.getType() == src && !item.hasItemMeta()) {
                    count += item.getAmount();
                }
            }
            int blocks = count / 9;
            if (blocks <= 0) continue;
            int toConsume = blocks * 9;
            ItemStack[] contents = p.getInventory().getStorageContents();
            for (int i = 0; i < contents.length && toConsume > 0; i++) {
                ItemStack item = contents[i];
                if (item != null && item.getType() == src && !item.hasItemMeta()) {
                    int take = Math.min(toConsume, item.getAmount());
                    toConsume -= take;
                    if (item.getAmount() == take) contents[i] = null;
                    else item.setAmount(item.getAmount() - take);
                }
            }
            p.getInventory().setStorageContents(contents);
            Map<Integer, ItemStack> overflow = p.getInventory().addItem(new ItemStack(dst, blocks));
            for (ItemStack drop : overflow.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), drop);
            }
            totalBlocks += blocks;
        }
        if (totalBlocks == 0) {
            sender.sendMessage(ChatColor.YELLOW + "No condensable stacks found.");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Condensed items into " + totalBlocks + " block(s)/ingot(s).");
        }
        return true;
    }

    private boolean handleBook(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can unsign books.");
            return true;
        }
        if (!sender.hasPermission("allium.book")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /book.");
            return true;
        }
        Player p = (Player) sender;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.WRITTEN_BOOK) {
            sender.sendMessage(ChatColor.RED + "Hold a written book to unlock it for editing.");
            return true;
        }
        Material writable = LegacyCompat.matchMaterial("WRITABLE_BOOK");
        if (writable == null) writable = LegacyCompat.matchMaterial("BOOK_AND_QUILL");
        if (writable == null) return true;
        ItemStack unlocked = new ItemStack(writable, 1);
        if (hand.getItemMeta() instanceof BookMeta && unlocked.getItemMeta() instanceof BookMeta) {
            BookMeta src = (BookMeta) hand.getItemMeta();
            BookMeta dst = (BookMeta) unlocked.getItemMeta();
            dst.setPages(src.getPages());
            unlocked.setItemMeta(dst);
        }
        p.getInventory().setItemInMainHand(unlocked);
        sender.sendMessage(ChatColor.GREEN + "Unlocked book for editing.");
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleSkull(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /skull.");
            return true;
        }
        if (!sender.hasPermission("allium.skull")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /skull.");
            return true;
        }
        Player p = (Player) sender;
        String ownerName = args.length >= 1 ? args[0] : p.getName();
        OfflinePlayer op = plugin.getServer().getOfflinePlayer(ownerName);
        ItemStack head = LegacyCompat.createPlayerHead(ownerName, op);
        Map<Integer, ItemStack> overflow = p.getInventory().addItem(head);
        for (ItemStack drop : overflow.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), drop);
        }
        sender.sendMessage(ChatColor.GREEN + "Gave you the head of " + ownerName + ".");
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args, boolean selfOnly) {
        String perm = selfOnly ? "allium.i" : "allium.give";
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        Player target;
        String matRaw;
        int amount = 1;
        if (selfOnly) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use /i.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /i <material> [amount]");
                return true;
            }
            target = (Player) sender;
            matRaw = args[0];
            if (args.length >= 2) {
                try { amount = Math.max(1, Math.min(2304, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
            }
        } else {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /give <player> <material> [amount]");
                return true;
            }
            target = findOnline(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
            matRaw = args[1];
            if (args.length >= 3) {
                try { amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) {}
            }
        }
        Material mat = LegacyCompat.matchMaterial(matRaw);
        if (mat == null || mat == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Unknown item '" + matRaw + "'.");
            return true;
        }
        int remaining = amount;
        int maxStack = Math.max(1, mat.getMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            remaining -= batch;
            Map<Integer, ItemStack> overflow = target.getInventory().addItem(new ItemStack(mat, batch));
            for (ItemStack drop : overflow.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + mat.name() + " to " + target.getName() + ".");
        return true;
    }

    private boolean handleXp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.xp")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /xp.");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /xp <show|give|set|take> <player> [amount[L]]");
                return true;
            }
            Player p = (Player) sender;
            sender.sendMessage(ChatColor.GREEN + "XP: level " + p.getLevel() + " (total " + p.getTotalExperience() + " points).");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("show")) {
            Player target = args.length >= 2 ? findOnline(args[1]) : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + target.getName() + "'s XP: level " + target.getLevel()
                    + " (total " + target.getTotalExperience() + " points).");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /xp <give|set|take> <player> <amount[L]>");
            return true;
        }
        Player target = findOnline(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
            return true;
        }
        String raw = args[2];
        boolean levels = raw.endsWith("L") || raw.endsWith("l");
        int amount;
        try {
            amount = Integer.parseInt(levels ? raw.substring(0, raw.length() - 1) : raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid XP amount '" + raw + "'.");
            return true;
        }
        if (sub.equals("give")) {
            if (levels) target.giveExpLevels(amount);
            else target.giveExp(amount);
        } else if (sub.equals("take")) {
            if (levels) target.setLevel(Math.max(0, target.getLevel() - amount));
            else target.giveExp(-amount);
        } else if (sub.equals("set")) {
            if (levels) target.setLevel(Math.max(0, amount));
            else {
                target.setLevel(0);
                target.setExp(0f);
                target.setTotalExperience(0);
                target.giveExp(Math.max(0, amount));
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /xp <show|give|set|take> <player> [amount[L]]");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "Updated " + target.getName() + "'s XP (now level " + target.getLevel() + ").");
        return true;
    }

    private boolean handleExt(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.ext")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /ext.");
            return true;
        }
        Player target = resolveSelfOrOther(sender, args, "allium.ext.others", "/ext <player>");
        if (target == null) return true;
        target.setFireTicks(0);
        sender.sendMessage(ChatColor.GREEN + "Extinguished " + (target.equals(sender) ? "yourself." : target.getName() + "."));
        return true;
    }

    private boolean handleBurn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.burn")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /burn.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /burn <player> [seconds]");
            return true;
        }
        Player target = findOnline(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        int seconds = 10;
        if (args.length >= 2) {
            try { seconds = Math.max(1, Math.min(300, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
        }
        target.setFireTicks(seconds * 20);
        sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + " on fire for " + seconds + "s.");
        return true;
    }

    private boolean handleSmite(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.smite")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /smite.");
            return true;
        }
        if (args.length >= 1) {
            Player target = findOnline(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
            target.getWorld().strikeLightning(target.getLocation());
            sender.sendMessage(ChatColor.GREEN + "Smote " + target.getName() + ".");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Usage: /smite <player>");
            return true;
        }
        Player p = (Player) sender;
        Block b = p.getTargetBlock((Set<Material>) null, 120);
        if (b != null) {
            p.getWorld().strikeLightning(b.getLocation());
        }
        return true;
    }

    private boolean handleNightVision(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.nv")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /nv.");
            return true;
        }
        Player target = resolveSelfOrOther(sender, args, "allium.nv.others", "/nv <player>");
        if (target == null) return true;
        if (target.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
            target.removePotionEffect(PotionEffectType.NIGHT_VISION);
            target.sendMessage(ChatColor.GRAY + "Night vision disabled.");
        } else {
            target.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 60 * 60 * 24, 0, false, false));
            target.sendMessage(ChatColor.GREEN + "Night vision enabled.");
        }
        if (!target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "Toggled night vision for " + target.getName() + ".");
        }
        return true;
    }

    private boolean handleGlow(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.glow")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /glow.");
            return true;
        }
        Player target = resolveSelfOrOther(sender, args, "allium.glow.others", "/glow <player>");
        if (target == null) return true;
        boolean next = !target.isGlowing();
        target.setGlowing(next);
        target.sendMessage(ChatColor.GREEN + "Glow " + (next ? "enabled." : "disabled."));
        if (!target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "Glow " + (next ? "enabled" : "disabled") + " for " + target.getName() + ".");
        }
        return true;
    }

    private boolean handlePvp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pvp")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /pvp.");
            return true;
        }
        Player target = resolveSelfOrOther(sender, args, "allium.pvp.others", "/pvp <player>");
        if (target == null) return true;
        if (pvpDisabled.remove(target.getUniqueId())) {
            target.sendMessage(ChatColor.GREEN + "PvP enabled.");
        } else {
            pvpDisabled.add(target.getUniqueId());
            target.sendMessage(ChatColor.GRAY + "PvP disabled.");
        }
        if (!target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "Toggled PvP for " + target.getName() + ".");
        }
        return true;
    }

    private boolean handleRest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.rest")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /rest.");
            return true;
        }
        Player target = resolveSelfOrOther(sender, args, "allium.rest.others", "/rest <player>");
        if (target == null) return true;
        if (LegacyCompat.resetRestStatistic(target)) {
            target.sendMessage(ChatColor.GREEN + "You feel rested.");
            if (!target.equals(sender)) sender.sendMessage(ChatColor.GREEN + "Reset rest timer for " + target.getName() + ".");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Phantom insomnia tracking was introduced in Minecraft 1.13.");
        }
        return true;
    }

    private boolean handleCompass(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /compass.");
            return true;
        }
        if (!sender.hasPermission("allium.compass")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /compass.");
            return true;
        }
        float yaw = (((Player) sender).getLocation().getYaw() % 360f + 360f) % 360f;
        String[] dirs = {"South", "Southwest", "West", "Northwest", "North", "Northeast", "East", "Southeast"};
        String dir = dirs[Math.round(yaw / 45f) % 8];
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Facing: " + ChatColor.WHITE + dir + ChatColor.GRAY + " (" + Math.round(yaw) + " deg)");
        return true;
    }

    private boolean handlePlayerTime(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.ptime")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /ptime.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /ptime <day|noon|night|midnight|ticks|reset> [player]");
            return true;
        }
        Player target = args.length >= 2 ? findOnline(args[1]) : (sender instanceof Player ? (Player) sender : null);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        String raw = args[0].toLowerCase(Locale.ROOT);
        if (raw.equals("reset")) {
            target.resetPlayerTime();
            sender.sendMessage(ChatColor.GREEN + "Reset player time for " + target.getName() + ".");
            return true;
        }
        long ticks;
        switch (raw) {
            case "day": ticks = 1000L; break;
            case "noon": ticks = 6000L; break;
            case "night": ticks = 13000L; break;
            case "midnight": ticks = 18000L; break;
            default:
                try { ticks = Long.parseLong(raw); } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid time '" + args[0] + "'.");
                    return true;
                }
        }
        target.setPlayerTime(ticks, false);
        sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s personal time to " + raw + ".");
        return true;
    }

    private boolean handlePlayerWeather(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pweather")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /pweather.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /pweather <clear|rain|reset> [player]");
            return true;
        }
        Player target = args.length >= 2 ? findOnline(args[1]) : (sender instanceof Player ? (Player) sender : null);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        String raw = args[0].toLowerCase(Locale.ROOT);
        if (raw.equals("reset")) {
            target.resetPlayerWeather();
            sender.sendMessage(ChatColor.GREEN + "Reset player weather for " + target.getName() + ".");
            return true;
        }
        if (raw.equals("clear") || raw.equals("sun")) {
            target.setPlayerWeather(WeatherType.CLEAR);
        } else if (raw.equals("rain") || raw.equals("storm") || raw.equals("downfall")) {
            target.setPlayerWeather(WeatherType.DOWNFALL);
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /pweather <clear|rain|reset> [player]");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s personal weather to " + raw + ".");
        return true;
    }

    private boolean handleBreak(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /break.");
            return true;
        }
        if (!sender.hasPermission("allium.break")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /break.");
            return true;
        }
        Block b = ((Player) sender).getTargetBlock((Set<Material>) null, 20);
        if (b == null || b.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Look at a block within 20 blocks.");
            return true;
        }
        if (b.getType() == Material.BEDROCK && !sender.hasPermission("allium.break.bedrock")) {
            sender.sendMessage(ChatColor.RED + "You cannot break bedrock.");
            return true;
        }
        b.setType(Material.AIR);
        sender.sendMessage(ChatColor.GREEN + "Broke target block.");
        return true;
    }

    private boolean handleSpawner(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /spawner.");
            return true;
        }
        if (!sender.hasPermission("allium.spawner")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /spawner.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /spawner <mobType>");
            return true;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(args[0].toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Unknown entity type '" + args[0] + "'.");
            return true;
        }
        Block b = ((Player) sender).getTargetBlock((Set<Material>) null, 20);
        if (b == null || !(b.getState() instanceof CreatureSpawner)) {
            sender.sendMessage(ChatColor.RED + "Look at a mob spawner.");
            return true;
        }
        CreatureSpawner spawner = (CreatureSpawner) b.getState();
        spawner.setSpawnedType(type);
        spawner.update();
        sender.sendMessage(ChatColor.GREEN + "Changed spawner to " + type.name() + ".");
        return true;
    }

    private boolean handleTree(CommandSender sender, String[] args, boolean big) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can grow trees.");
            return true;
        }
        if (!sender.hasPermission(big ? "allium.bigtree" : "allium.tree")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        TreeType type = big ? TreeType.BIG_TREE : TreeType.TREE;
        if (!big && args.length >= 1) {
            try {
                type = TreeType.valueOf(args[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sender.sendMessage(ChatColor.RED + "Unknown tree type '" + args[0] + "'.");
                return true;
            }
        }
        Block b = ((Player) sender).getTargetBlock((Set<Material>) null, 30);
        if (b == null || b.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Look at a ground block.");
            return true;
        }
        boolean grew = b.getWorld().generateTree(b.getLocation().add(0, 1, 0), type);
        sender.sendMessage(grew ? ChatColor.GREEN + "Grew " + type.name() + "." : ChatColor.RED + "Could not generate a tree there.");
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /remove.");
            return true;
        }
        if (!sender.hasPermission("allium.remove")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /remove.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /remove <drops|mobs|monsters|all> [radius]");
            return true;
        }
        Player p = (Player) sender;
        String mode = args[0].toLowerCase(Locale.ROOT);
        double radius = 50.0;
        if (args.length >= 2) {
            try { radius = Math.max(1.0, Math.min(500.0, Double.parseDouble(args[1]))); } catch (NumberFormatException ignored) {}
        }
        int removed = 0;
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof Player) continue;
            boolean match = false;
            if (mode.equals("drops") || mode.equals("items")) match = e instanceof Item;
            else if (mode.equals("monsters")) match = e instanceof Monster;
            else if (mode.equals("mobs")) match = e instanceof LivingEntity;
            else if (mode.equals("all")) match = true;
            if (match) {
                e.remove();
                removed++;
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Removed " + removed + " entity/entities within " + Math.round(radius) + "m.");
        return true;
    }

    private boolean handleSpawnMob(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can spawn mobs.");
            return true;
        }
        if (!sender.hasPermission("allium.spawnmob")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /spawnmob.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /spawnmob <mob> [amount]");
            return true;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(args[0].toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Unknown mob '" + args[0] + "'.");
            return true;
        }
        int amount = 1;
        if (args.length >= 2) {
            try { amount = Math.max(1, Math.min(50, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
        }
        Player p = (Player) sender;
        Block target = p.getTargetBlock((Set<Material>) null, 50);
        Location spawnAt = target != null && target.getType() != Material.AIR
                ? target.getLocation().add(0.5, 1.0, 0.5)
                : p.getLocation();
        for (int i = 0; i < amount; i++) {
            spawnAt.getWorld().spawnEntity(spawnAt, type);
        }
        sender.sendMessage(ChatColor.GREEN + "Spawned " + amount + "x " + type.name() + ".");
        return true;
    }

    private boolean handleGc(CommandSender sender) {
        if (!sender.hasPermission("allium.gc")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /gc.");
            return true;
        }
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        long allocMb = rt.totalMemory() / (1024L * 1024L);
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        double[] tps = LegacyCompat.recentTps(plugin.getServer());
        String tpsText = tps != null && tps.length > 0
                ? String.format(Locale.US, "%.2f", Math.min(20.0, tps[0]))
                : "N/A";
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Server Status: " + ChatColor.GRAY + "Uptime "
                + ChatColor.WHITE + LegacyCompat.formatDuration(uptimeMs) + ChatColor.GRAY + ", TPS: " + ChatColor.GREEN + tpsText);
        sender.sendMessage(ChatColor.GRAY + "Memory: " + ChatColor.WHITE + usedMb + " MB used / " + allocMb + " MB alloc / " + maxMb + " MB max");
        int totalChunks = 0;
        int totalEntities = 0;
        for (World w : plugin.getServer().getWorlds()) {
            totalChunks += w.getLoadedChunks().length;
            totalEntities += w.getEntities().size();
        }
        sender.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + plugin.getServer().getOnlinePlayers().size()
                + "/" + plugin.getServer().getMaxPlayers()
                + ChatColor.GRAY + " | Worlds: " + ChatColor.WHITE + plugin.getServer().getWorlds().size()
                + ChatColor.GRAY + " | Chunks: " + ChatColor.WHITE + totalChunks
                + ChatColor.GRAY + " | Entities: " + ChatColor.WHITE + totalEntities);
        return true;
    }

    private boolean handleBroadcastWorld(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.broadcastworld")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /broadcastworld.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /broadcastworld <world> <message>");
            return true;
        }
        World w = plugin.getServer().getWorld(args[0]);
        if (w == null) {
            sender.sendMessage(ChatColor.RED + "Unknown world '" + args[0] + "'.");
            return true;
        }
        String formatted = ChatColor.LIGHT_PURPLE + "[Broadcast] " + ChatColor.WHITE + LegacyCompat.color(joinArgs(args, 1));
        for (Player p : w.getPlayers()) {
            p.sendMessage(formatted);
        }
        sender.sendMessage(ChatColor.GREEN + "Broadcast sent to world '" + w.getName() + "'.");
        return true;
    }

    private boolean handleHelp(CommandSender sender, String[] args) {
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Allium Legacy Compatibility Commands:");
        sender.sendMessage(ChatColor.GRAY + "Travel: " + ChatColor.WHITE + "/sethome /home /delhome /renamehome /homes /bed /warp /warps /spawn /back /tpa /tpahere /tpaccept /tpdeny /tptoggle /tpauto /tp /tphere /tpo /tpall /tppos /top /bottom /jump /world /rtp");
        sender.sendMessage(ChatColor.GRAY + "Economy & Kits: " + ChatColor.WHITE + "/balance /pay /paytoggle /eco /baltop /worth /setworth /sell /kit /kits /createkit /delkit /showkit /kitreset");
        sender.sendMessage(ChatColor.GRAY + "Moderation: " + ChatColor.WHITE + "/vanish /mute /unmute /mutes /kick /kickall /ban /tempban /unban /banlist /ipban /freeze /jail /unjail /jails /warn /notes /invsee /helpop /report /socialspy /commandspy /clearchat");
        sender.sendMessage(ChatColor.GRAY + "Player & Utility: " + ChatColor.WHITE + "/msg /reply /ignore /mail /nick /realname /list /seen /whois /near /playtime /afk /heal /feed /fly /god /gm /speed /repair /disposal /workbench /anvil /enderchest /rename /lore /enchant /give /xp /nv /glow /pvp /gc /time /weather");
        return true;
    }

    private boolean handleFireball(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can launch fireballs.");
            return true;
        }
        if (!sender.hasPermission("allium.fireball")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /fireball.");
            return true;
        }
        Player p = (Player) sender;
        p.launchProjectile(Fireball.class);
        return true;
    }

    private boolean handleAntioch(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /antioch.");
            return true;
        }
        if (!sender.hasPermission("allium.antioch")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /antioch.");
            return true;
        }
        Player p = (Player) sender;
        Block b = p.getTargetBlock((Set<Material>) null, 50);
        Location at = b != null && b.getType() != Material.AIR ? b.getLocation().add(0.5, 1.0, 0.5) : p.getLocation();
        at.getWorld().spawn(at, TNTPrimed.class);
        return true;
    }

    private boolean handleNuke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.nuke")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /nuke.");
            return true;
        }
        Player target = resolveSelfOrOther(sender, args, "allium.nuke", "/nuke <player>");
        if (target == null) return true;
        Location base = target.getLocation();
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                base.getWorld().spawn(base.clone().add(dx, 10, dz), TNTPrimed.class);
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Dropped TNT above " + target.getName() + ".");
        return true;
    }

    private boolean handleExplode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.explode")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /explode.");
            return true;
        }
        Player target = args.length >= 1 ? findOnline(args[0]) : (sender instanceof Player ? (Player) sender : null);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Usage: /explode <player> [power]");
            return true;
        }
        float power = 4.0f;
        if (args.length >= 2) {
            try { power = Math.max(0.5f, Math.min(12.0f, Float.parseFloat(args[1]))); } catch (NumberFormatException ignored) {}
        }
        target.getWorld().createExplosion(target.getLocation(), power, false);
        return true;
    }

    private boolean handleKittyCannon(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /kittycannon.");
            return true;
        }
        if (!sender.hasPermission("allium.kittycannon")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /kittycannon.");
            return true;
        }
        final Player p = (Player) sender;
        final Ocelot cat = p.getWorld().spawn(p.getEyeLocation(), Ocelot.class);
        cat.setVelocity(p.getLocation().getDirection().multiply(2.0));
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (cat.isValid()) {
                    Location loc = cat.getLocation();
                    cat.remove();
                    loc.getWorld().createExplosion(loc.getX(), loc.getY(), loc.getZ(), 0.0f, false, false);
                }
            }
        }, 20L);
        return true;
    }

    private Player resolveSelfOrOther(CommandSender sender, String[] args, String otherPerm, String usage) {
        if (args.length >= 1) {
            if (!sender.hasPermission(otherPerm)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to target other players.");
                return null;
            }
            Player target = findOnline(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return null;
            }
            return target;
        }
        if (sender instanceof Player) return (Player) sender;
        sender.sendMessage(ChatColor.RED + "Usage: " + usage);
        return null;
    }

    private Player findOnline(String name) {
        Player p = plugin.getServer().getPlayerExact(name);
        return p != null ? p : plugin.getServer().getPlayer(name);
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        List<String> out = new ArrayList<String>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(c);
        }
        return out;
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
