package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class LegacyPotionCommand {
    private static final class PendingEffect {
        private PotionEffectType type;
        private Integer amplifier;
        private Integer ticks;

        boolean complete() { return type != null && amplifier != null && ticks != null; }
        boolean empty() { return type == null && amplifier == null && ticks == null; }
    }

    private final LegacyMessageManager messages;

    LegacyPotionCommand(LegacyMessageManager messages) {
        this.messages = messages;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            messages.sendPrefixed(sender, ChatColor.RED + "Only players can use /potion.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("allium.potion")) return noPermission(player);
        if (args.length == 0) {
            Set<String> allowed = allowedEffects(player);
            if (allowed.isEmpty()) notify(player, "none-allowed", "&cYou do not have permission for any potion effects.");
            else notify(player, "list", "&dAvailable potion effects: {effects}", "{effects}", String.join(", ", allowed));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta itemMeta = held == null ? null : held.getItemMeta();
        if (!(itemMeta instanceof PotionMeta)) {
            notify(player, "hold", "&cHold a potion or tipped arrow in your main hand.");
            return true;
        }
        PotionMeta meta = (PotionMeta) itemMeta;
        if ("clear".equalsIgnoreCase(args[0])) {
            if (args.length != 1) return false;
            meta.clearCustomEffects();
            replace(player, held, meta);
            notify(player, "cleared", "&aCleared all custom effects from the held potion.");
            return true;
        }
        if ("apply".equalsIgnoreCase(args[0])) {
            if (args.length != 1) return false;
            if (!player.hasPermission("allium.potion.apply")) return noPermission(player);
            List<PotionEffect> effects = meta.getCustomEffects();
            if (effects.isEmpty()) {
                notify(player, "no-effects", "&cThat potion has no custom effects to apply.");
            } else {
                for (PotionEffect effect : effects) player.addPotionEffect(effect, true);
                notify(player, "applied", "&aApplied {count} custom effect(s) from the held potion.",
                        "{count}", Integer.toString(effects.size()));
            }
            return true;
        }
        return edit(player, held, meta, args);
    }

    private boolean edit(Player player, ItemStack held, PotionMeta meta, String[] args) {
        List<PotionEffect> effects = new ArrayList<>();
        Color color = null;
        PendingEffect pending = new PendingEffect();
        for (String arg : args) {
            int split = arg.indexOf(':');
            if (split <= 0 || split == arg.length() - 1) return invalid(player, arg);
            String key = arg.substring(0, split).toLowerCase(Locale.ROOT);
            String value = arg.substring(split + 1);
            switch (key) {
                case "effect":
                case "e":
                    if (pending.type != null) return invalid(player, arg);
                    pending.type = resolve(value);
                    if (pending.type == null) return invalid(player, arg);
                    if (!allowed(player, pending.type)) {
                        notify(player, "no-effect-perm", "&cYou do not have permission for {effect}.",
                                "{effect}", key(pending.type));
                        return true;
                    }
                    break;
                case "power":
                case "p":
                case "amplifier":
                case "a":
                    boolean power = "power".equals(key) || "p".equals(key);
                    Integer level = bounded(value, power ? 256 : 255);
                    if (pending.amplifier != null || level == null) return invalid(player, arg);
                    pending.amplifier = power ? Math.max(0, level - 1) : level;
                    break;
                case "duration":
                case "d":
                    Integer seconds = bounded(value, 1_000_000);
                    if (pending.ticks != null || seconds == null) return invalid(player, arg);
                    pending.ticks = seconds * 20;
                    break;
                case "color":
                case "colour":
                case "c":
                    color = LegacyCompat.parseDyeColor(value);
                    if (color == null) return invalid(player, arg);
                    break;
                default:
                    return invalid(player, arg);
            }
            if (pending.complete()) {
                int ticks = pending.type.isInstant() ? Math.max(1, pending.ticks) : pending.ticks;
                effects.add(new PotionEffect(pending.type, ticks, pending.amplifier));
                pending = new PendingEffect();
            }
        }
        if (!pending.empty() || (effects.isEmpty() && color == null)) {
            notify(player, "invalid", "&cGive effect:<name>, power:<level> and duration:<seconds>.");
            return true;
        }

        Set<PotionEffectType> distinct = new HashSet<>();
        for (PotionEffect effect : meta.getCustomEffects()) distinct.add(effect.getType());
        for (PotionEffect effect : effects) distinct.add(effect.getType());
        if (distinct.size() > 1 && !player.hasPermission("allium.potions.multiple") && !effects.isEmpty()) {
            notify(player, "no-multiple", "&cYou do not have permission to add multiple effects.");
            return true;
        }
        for (PotionEffect effect : effects) meta.addCustomEffect(effect, true);
        if (color != null) meta.setColor(color);
        replace(player, held, meta);
        String summary = effects.stream().map(effect -> key(effect.getType()) + " " + (effect.getAmplifier() + 1))
                .collect(Collectors.joining(", "));
        if (color != null) summary = summary.isEmpty() ? "colour" : summary + ", colour";
        notify(player, "updated", "&aUpdated held potion: {summary}.", "{summary}", summary);
        return true;
    }

    private static void replace(Player player, ItemStack held, PotionMeta meta) {
        ItemStack copy = held.clone();
        copy.setItemMeta(meta);
        player.getInventory().setItemInMainHand(copy);
    }

    private static PotionEffectType resolve(String input) {
        String name = input.toLowerCase(Locale.ROOT).replace('-', '_');
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type != null && (key(type).equals(name) || type.getName().equalsIgnoreCase(name))) return type;
        }
        return null;
    }

    private static String key(PotionEffectType type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "slow": return "slowness";
            case "fast_digging": return "haste";
            case "slow_digging": return "mining_fatigue";
            case "increase_damage": return "strength";
            case "heal": return "instant_health";
            case "harm": return "instant_damage";
            case "jump": return "jump_boost";
            case "confusion": return "nausea";
            case "damage_resistance": return "resistance";
            default: return name;
        }
    }

    private static boolean allowed(CommandSender sender, PotionEffectType type) {
        return sender.hasPermission("allium.potions.*") || sender.hasPermission("allium.potions." + key(type))
                || sender.hasPermission("allium.potions." + type.getName().toLowerCase(Locale.ROOT));
    }

    private static Set<String> allowedEffects(CommandSender sender) {
        Set<String> effects = new TreeSet<>();
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type != null && allowed(sender, type)) effects.add(key(type));
        }
        return effects;
    }

    private static Integer bounded(String input, int maximum) {
        try {
            int value = Integer.parseInt(input);
            return value >= 0 && value <= maximum ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (args.length < 1) return Collections.emptyList();
        if (prefix.startsWith("effect:") || prefix.startsWith("e:")) {
            String key = prefix.substring(0, prefix.indexOf(':') + 1);
            return allowedEffects(sender).stream().map(effect -> key + effect).filter(value -> value.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        List<String> values = new ArrayList<>(Arrays.asList("effect:", "power:", "amplifier:", "duration:", "color:"));
        if (args.length == 1) {
            values.add("clear");
            if (sender.hasPermission("allium.potion.apply")) values.add("apply");
        }
        return values.stream().filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
    }

    private boolean noPermission(Player player) {
        messages.sendPrefixed(player, ChatColor.RED + "You do not have permission to do that.");
        return true;
    }

    private boolean invalid(Player player, String input) {
        notify(player, "invalid-meta", "&cInvalid potion option {value}.", "{value}", input);
        return true;
    }

    private void notify(Player player, String key, String fallback, String... replacements) {
        messages.send(player, "utility.potion." + key, "{prefix} " + fallback, replacements);
    }
}
