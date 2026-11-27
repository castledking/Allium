package codes.castled.allium.legacy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 kits module:
 * {@code /kit}, {@code /kits}, {@code /createkit}, {@code /delkit}, {@code /showkit}, {@code /kitreset}.
 */
final class LegacyKitsModule implements Listener {

    private static final String PREVIEW_PREFIX = ChatColor.DARK_PURPLE + "Kit Preview: ";

    static final class KitDef {
        final String name;
        final long cooldownSeconds;
        final List<ItemStack> items;

        KitDef(String name, long cooldownSeconds, List<ItemStack> items) {
            this.name = name;
            this.cooldownSeconds = Math.max(0L, cooldownSeconds);
            this.items = items;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final Map<String, KitDef> kits = new LinkedHashMap<String, KitDef>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<UUID, Map<String, Long>>();

    LegacyKitsModule(JavaPlugin plugin, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    private void send(CommandSender recipient, String message) {
        messages.sendPrefixed(recipient, message);
    }

    void enable() {
        loadKits();
        loadCooldowns();
    }

    void reload() {
        loadKits();
        loadCooldowns();
    }

    void disable() {
        saveKits();
        saveCooldowns();
    }

    int kitCount() {
        return kits.size();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView() != null && event.getView().getTitle() != null
                && event.getView().getTitle().startsWith(PREVIEW_PREFIX)) {
            event.setCancelled(true);
        }
    }

    boolean handles(String name) {
        switch (name) {
            case "kit":
            case "kits":
            case "createkit":
            case "delkit":
            case "showkit":
            case "kitreset":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "kit": return handleKit(sender, args);
            case "kits": return handleKits(sender);
            case "createkit": return handleCreateKit(sender, args);
            case "delkit": return handleDelKit(sender, args);
            case "showkit": return handleShowKit(sender, args);
            case "kitreset": return handleKitReset(sender, args);
            default: return false;
        }
    }

    List<String> onTabComplete(CommandSender sender, String name, String[] args, String prefix) {
        switch (name) {
            case "kit":
                if (args.length == 1) return filterKits(sender, prefix, true);
                if (args.length == 2 && sender.hasPermission("allium.kit.others")) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "delkit":
            case "showkit":
                return args.length == 1 ? filterKits(sender, prefix, false) : Collections.<String>emptyList();
            case "kitreset":
                if (args.length == 1) return onlinePlayers(prefix);
                if (args.length == 2) {
                    List<String> opts = filterKits(sender, prefix, false);
                    if ("all".startsWith(prefix)) opts.add(0, "all");
                    return opts;
                }
                return Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleKits(CommandSender sender) {
        if (!sender.hasPermission("allium.kits")) {
            send(sender, ChatColor.RED + "You do not have permission to list kits.");
            return true;
        }
        List<String> available = new ArrayList<String>();
        for (String name : kits.keySet()) {
            if (hasKitPermission(sender, name)) {
                available.add(name);
            }
        }
        if (available.isEmpty()) {
            send(sender, ChatColor.GRAY + "No kits are available.");
            return true;
        }
        send(sender, ChatColor.LIGHT_PURPLE + "Kits (" + available.size() + "): "
                + ChatColor.WHITE + join(available, ", "));
        return true;
    }

    private boolean handleKit(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return handleKits(sender);
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        KitDef def = kits.get(key);
        if (def == null) {
            send(sender, ChatColor.RED + "Kit '" + args[0] + "' does not exist.");
            return true;
        }
        Player target;
        boolean givingOther = false;
        if (args.length >= 2) {
            if (!sender.hasPermission("allium.kit.others")) {
                send(sender, ChatColor.RED + "You do not have permission to give kits to other players.");
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                send(sender, ChatColor.RED + "Player '" + args[1] + "' is not online.");
                return true;
            }
            givingOther = !target.equals(sender);
        } else {
            if (!(sender instanceof Player)) {
                send(sender, ChatColor.RED + "Usage: /kit <name> <player>");
                return true;
            }
            target = (Player) sender;
        }

        if (!givingOther && !hasKitPermission(sender, key)) {
            send(sender, ChatColor.RED + "You do not have permission to claim kit '" + def.name + "'.");
            return true;
        }
        if (!givingOther && def.cooldownSeconds > 0 && !sender.hasPermission("allium.kit.exemptdelay")) {
            long now = System.currentTimeMillis();
            long next = getNextAvailable(target.getUniqueId(), key);
            if (next > now) {
                send(sender, ChatColor.RED + "You can claim kit '" + def.name + "' again in "
                        + LegacyCompat.formatDuration(next - now) + ".");
                return true;
            }
            setNextAvailable(target.getUniqueId(), key, now + def.cooldownSeconds * 1000L);
        }

        for (ItemStack item : def.items) {
            if (item == null || item.getType() == Material.AIR) continue;
            Map<Integer, ItemStack> overflow = target.getInventory().addItem(item.clone());
            for (ItemStack drop : overflow.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
        }
        if (givingOther) {
            send(sender, ChatColor.GREEN + "Gave kit '" + def.name + "' to " + target.getName() + ".");
            send(target, ChatColor.GREEN + "You received kit '" + def.name + "'.");
        } else {
            send(sender, ChatColor.GREEN + "Claimed kit '" + def.name + "'.");
        }
        return true;
    }

    private boolean handleCreateKit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can create kits from their inventory.");
            return true;
        }
        if (!sender.hasPermission("allium.createkit")) {
            send(sender, ChatColor.RED + "You do not have permission to create kits.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /createkit <name> [cooldownSeconds]");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_-]{1,32}")) {
            send(sender, ChatColor.RED + "Kit names may contain 1-32 letters, numbers, underscores, or hyphens.");
            return true;
        }
        long cooldown = 0L;
        if (args.length >= 2) {
            try {
                cooldown = Math.max(0L, Long.parseLong(args[1]));
            } catch (NumberFormatException e) {
                long parsed = LegacyCompat.parseDurationMillis(args[1]);
                if (parsed < 0L) {
                    send(sender, ChatColor.RED + "Invalid cooldown '" + args[1] + "'.");
                    return true;
                }
                cooldown = parsed / 1000L;
            }
        }
        Player player = (Player) sender;
        List<ItemStack> items = new ArrayList<ItemStack>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                items.add(stack.clone());
            }
        }
        if (items.isEmpty()) {
            send(sender, ChatColor.RED + "Your inventory is empty.");
            return true;
        }
        kits.put(key, new KitDef(key, cooldown, items));
        saveKits();
        send(sender, ChatColor.GREEN + "Created kit '" + key + "' with " + items.size()
                + " item stack(s) (cooldown: " + cooldown + "s).");
        return true;
    }

    private boolean handleDelKit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.delkit")) {
            send(sender, ChatColor.RED + "You do not have permission to delete kits.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /delkit <name>");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        if (kits.remove(key) == null) {
            send(sender, ChatColor.RED + "Kit '" + args[0] + "' does not exist.");
            return true;
        }
        saveKits();
        send(sender, ChatColor.GREEN + "Deleted kit '" + key + "'.");
        return true;
    }

    private boolean handleShowKit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can preview kits.");
            return true;
        }
        if (!sender.hasPermission("allium.showkit")) {
            send(sender, ChatColor.RED + "You do not have permission to preview kits.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /showkit <name>");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        KitDef def = kits.get(key);
        if (def == null) {
            send(sender, ChatColor.RED + "Kit '" + args[0] + "' does not exist.");
            return true;
        }
        int size = Math.min(54, Math.max(9, ((def.items.size() + 8) / 9) * 9));
        Inventory preview = Bukkit.createInventory(null, size, PREVIEW_PREFIX + def.name);
        for (int i = 0; i < def.items.size() && i < size; i++) {
            preview.setItem(i, def.items.get(i).clone());
        }
        ((Player) sender).openInventory(preview);
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean handleKitReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.kitreset")) {
            send(sender, ChatColor.RED + "You do not have permission to reset kit cooldowns.");
            return true;
        }
        if (args.length < 2) {
            send(sender, ChatColor.RED + "Usage: /kitreset <player> <kit|all>");
            return true;
        }
        Player online = plugin.getServer().getPlayerExact(args[0]);
        OfflinePlayer target = online != null ? online : plugin.getServer().getOfflinePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        Map<String, Long> map = cooldowns.get(target.getUniqueId());
        if (args[1].equalsIgnoreCase("all")) {
            cooldowns.remove(target.getUniqueId());
            saveCooldowns();
            send(sender, ChatColor.GREEN + "Reset all kit cooldowns for " + args[0] + ".");
            return true;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (map != null) {
            map.remove(key);
            saveCooldowns();
        }
        send(sender, ChatColor.GREEN + "Reset cooldown for kit '" + key + "' on " + args[0] + ".");
        return true;
    }

    private boolean hasKitPermission(CommandSender sender, String kitKey) {
        return sender.hasPermission("allium.kit.*")
                || sender.hasPermission("allium.kits.*")
                || sender.hasPermission("allium.kits." + kitKey)
                || sender.hasPermission("allium.kit." + kitKey);
    }

    private long getNextAvailable(UUID playerId, String kitKey) {
        Map<String, Long> map = cooldowns.get(playerId);
        if (map == null) return 0L;
        Long val = map.get(kitKey);
        return val == null ? 0L : val;
    }

    private void setNextAvailable(UUID playerId, String kitKey, long nextMs) {
        Map<String, Long> map = cooldowns.get(playerId);
        if (map == null) {
            map = new HashMap<String, Long>();
            cooldowns.put(playerId, map);
        }
        map.put(kitKey, nextMs);
        saveCooldowns();
    }

    @SuppressWarnings("unchecked")
    private void loadKits() {
        kits.clear();
        File file = new File(plugin.getDataFolder(), "kits-legacy.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("kits");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            long cd = s.getLong("cooldown", 0L);
            List<?> rawList = s.getList("items");
            List<ItemStack> items = new ArrayList<ItemStack>();
            if (rawList != null) {
                for (Object obj : rawList) {
                    if (obj instanceof ItemStack) {
                        items.add((ItemStack) obj);
                    }
                }
            }
            kits.put(key.toLowerCase(Locale.ROOT), new KitDef(key.toLowerCase(Locale.ROOT), cd, items));
        }
    }

    private void saveKits() {
        File file = new File(plugin.getDataFolder(), "kits-legacy.yml");
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, KitDef> e : kits.entrySet()) {
            yml.set("kits." + e.getKey() + ".cooldown", e.getValue().cooldownSeconds);
            yml.set("kits." + e.getKey() + ".items", e.getValue().items);
        }
        try {
            yml.save(file);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not save kits-legacy.yml: " + e.getMessage());
        }
    }

    private void loadCooldowns() {
        cooldowns.clear();
        File file = new File(plugin.getDataFolder(), "kit-cooldowns.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("cooldowns");
        if (root == null) return;
        long now = System.currentTimeMillis();
        for (String uuidStr : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(uuidStr);
                ConfigurationSection sub = root.getConfigurationSection(uuidStr);
                if (sub == null) continue;
                Map<String, Long> map = new HashMap<String, Long>();
                for (String kitKey : sub.getKeys(false)) {
                    long until = sub.getLong(kitKey, 0L);
                    if (until > now) map.put(kitKey, until);
                }
                if (!map.isEmpty()) cooldowns.put(id, map);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveCooldowns() {
        File file = new File(plugin.getDataFolder(), "kit-cooldowns.yml");
        YamlConfiguration yml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Long>> e : cooldowns.entrySet()) {
            for (Map.Entry<String, Long> k : e.getValue().entrySet()) {
                if (k.getValue() > now) {
                    yml.set("cooldowns." + e.getKey() + "." + k.getKey(), k.getValue());
                }
            }
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save kit-cooldowns.yml: " + e.getMessage());
        }
    }

    private List<String> filterKits(CommandSender sender, String prefix, boolean checkPerm) {
        List<String> out = new ArrayList<String>();
        for (String k : kits.keySet()) {
            if (k.startsWith(prefix) && (!checkPerm || hasKitPermission(sender, k))) {
                out.add(k);
            }
        }
        Collections.sort(out);
        return out;
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        Collections.sort(out);
        return out;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
