package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.security.FlightRestoration;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.tfly.TFlyManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class Fly implements CommandExecutor, TabCompleter {

    private static final List<String> TFLY_ACTIONS = Arrays.asList("set", "add", "take", "reset");

    private final PluginStart plugin;
    private final Lang lang;

    public Fly(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("fly")) return false;

        TFlyManager tflyManager = plugin.getTFlyManager();

        // Admin tfly time: /fly <player> set|add|take|reset [time]
        if (args.length >= 2 && tflyManager != null) {
            String action = args[1].toLowerCase(Locale.ENGLISH);
            if (TFLY_ACTIONS.contains(action)) {
                return handleTflyAdmin(sender, label, args, tflyManager);
            }
        }

        // Toggle: /fly or /fly <player>
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                lang.sendMessage(sender, "command-usage", "{cmd}", label, "{args}", "<player> [set|add|take|reset] [time]");
                return true;
            }
            if (!canUseFlySelf(player, tflyManager)) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                return true;
            }
            toggleFlyOrTfly(player, sender, tflyManager);
            return true;
        }

        if (args.length == 1) {
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
                return true;
            }

            if (sender instanceof Player source && source.getUniqueId().equals(target.getUniqueId())) {
                if (!canUseFlySelf(source, tflyManager)) {
                    Text.sendErrorMessage(source, "no-permission", lang, "{cmd}", label);
                    return true;
                }
                toggleFlyOrTfly(source, sender, tflyManager);
                return true;
            }

            if (!sender.hasPermission("allium.fly.others") && !sender.hasPermission("allium.tfly.others")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " on others.");
                return true;
            }
            if (target.getGameMode() == GameMode.SPECTATOR) {
                Text.sendErrorMessage(sender, lang.get("player-not-found"), lang, "{name}", target.getName());
                return true;
            }
            toggleFlyOrTflyOther(target, sender, tflyManager);
            return true;
        }

        lang.sendMessage(sender, "command-usage", "{cmd}", label, "{args}", "<player> [set|add|take|reset] [time]");
        return true;
    }

    private boolean handleTflyAdmin(CommandSender sender, String label, String[] args, TFlyManager tflyManager) {
        if (!sender.hasPermission("allium.tfly.set") && !sender.hasPermission("allium.tfly.add") && !sender.hasPermission("allium.tfly.take") && !sender.hasPermission("allium.tfly.reset")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + args[0]);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ENGLISH);

        if (action.equals("reset")) {
            if (!sender.hasPermission("allium.tfly.reset")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            tflyManager.setTFlyTime(target.getUniqueId(), 0);
            tflyManager.setTFlyEnabled(target.getUniqueId(), false);
            tflyManager.disableFlight(target);
            sender.sendMessage("§aTfly reset for " + target.getName());
            if (target.isOnline()) target.sendMessage("§eYour tfly was reset.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /fly <player> " + action + " <time>");
            return true;
        }
        long timeSeconds = parseTime(args[2]);
        if (timeSeconds < 0) {
            sender.sendMessage("§cInvalid time: " + args[2]);
            return true;
        }
        UUID uuid = target.getUniqueId();
        long current = tflyManager.getTFlyTime(uuid);

        switch (action) {
            case "set":
                if (!sender.hasPermission("allium.tfly.set")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                tflyManager.setTFlyTime(uuid, timeSeconds);
                sender.sendMessage("§aSet " + target.getName() + " tfly to " + TFlyManager.formatTime(timeSeconds));
                if (target.isOnline()) target.sendMessage("§eYour tfly was set to " + TFlyManager.formatTime(timeSeconds));
                break;
            case "add":
                if (!sender.hasPermission("allium.tfly.add")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                long newTime = current + timeSeconds;
                tflyManager.setTFlyTime(uuid, newTime);
                sender.sendMessage("§aAdded " + TFlyManager.formatTime(timeSeconds) + " for " + target.getName() + ". Total: " + TFlyManager.formatTime(newTime));
                if (target.isOnline()) target.sendMessage("§eYou received " + TFlyManager.formatTime(timeSeconds) + " tfly. Total: " + TFlyManager.formatTime(newTime));
                break;
            case "take":
                if (!sender.hasPermission("allium.tfly.take")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                long remaining = Math.max(0, current - timeSeconds);
                tflyManager.setTFlyTime(uuid, remaining);
                if (remaining == 0 && tflyManager.isTFlyEnabled(uuid)) {
                    tflyManager.setTFlyEnabled(uuid, false);
                    tflyManager.disableFlight(target);
                }
                sender.sendMessage("§aTook " + TFlyManager.formatTime(timeSeconds) + " from " + target.getName() + ". Remaining: " + TFlyManager.formatTime(remaining));
                if (target.isOnline()) target.sendMessage("§e" + TFlyManager.formatTime(timeSeconds) + " tfly was taken. Remaining: " + TFlyManager.formatTime(remaining));
                break;
            default:
                break;
        }
        return true;
    }

    private void toggleFlyOrTfly(Player player, CommandSender sender, TFlyManager tflyManager) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (player.getAllowFlight()) return;
        }

        boolean hasPermanentFly = player.hasPermission("allium.fly");
        long tflyTime = tflyManager != null ? tflyManager.getTFlyTime(player.getUniqueId()) : 0;

        // Prefer permanent fly if they have it; otherwise use tfly if they have time
        if (hasPermanentFly) {
            togglePermanentFly(player, sender);
            return;
        }
        if (tflyTime > 0) {
            toggleTfly(player, sender, tflyManager);
            return;
        }
        if (player.hasPermission("allium.tfly")) {
            player.sendMessage("§cYou have no tfly time. Get more from vouchers or an admin.");
        } else {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "fly");
        }
    }

    private void toggleFlyOrTflyOther(Player target, CommandSender sender, TFlyManager tflyManager) {
        if (sender.hasPermission("allium.fly.others")) {
            togglePermanentFly(target, sender);
            return;
        }

        long tflyTime = tflyManager != null ? tflyManager.getTFlyTime(target.getUniqueId()) : 0;
        if (tflyManager != null && sender.hasPermission("allium.tfly.others") && tflyTime > 0) {
            toggleTfly(target, sender, tflyManager);
            return;
        }

        sender.sendMessage("§c" + target.getName() + " has no tfly time to toggle.");
    }

    private boolean canUseFlySelf(Player player, TFlyManager tflyManager) {
        if (player.hasPermission("allium.fly") || player.hasPermission("allium.tfly")) {
            return true;
        }
        return tflyManager != null && tflyManager.getTFlyTime(player.getUniqueId()) > 0;
    }

    private void togglePermanentFly(Player player, CommandSender sender) {
        if (player.getGameMode() == GameMode.SPECTATOR && player.getAllowFlight()) return;

        String firstColorOfFlyToggle = lang.getFirstColorCode("fly.toggle");
        boolean sameSenderAndPlayer = sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId());

        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
            player.setFlying(false);
            boolean midAir = !player.isOnGround() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
            if (midAir) {
                FlightRestoration restoration = plugin.getFlyOnRejoinListener();
                if (restoration != null) {
                    restoration.applySlowFallingUntilLanded(player);
                } else {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60, 0, true, false));
                }
            }
            String stateValue = lang.get("styles.state.false") + "disabled" + firstColorOfFlyToggle;
            if (!sameSenderAndPlayer) lang.sendMessage(sender, "fly.toggle", "state", stateValue, "name", "for " + player.getName());
            lang.sendMessage(player, "fly.toggle", "state", stateValue, "name", "");
        } else {
            player.setAllowFlight(true);
            player.removePotionEffect(PotionEffectType.SLOW_FALLING);
            String stateValue = lang.get("styles.state.true") + "enabled" + firstColorOfFlyToggle;
            if (!sameSenderAndPlayer) lang.sendMessage(sender, "fly.toggle", "state", stateValue, "name", "for " + player.getName());
            lang.sendMessage(player, "fly.toggle", "state", stateValue, "name", "");
        }
    }

    private void toggleTfly(Player player, CommandSender sender, TFlyManager tflyManager) {
        UUID uuid = player.getUniqueId();
        long timeRemaining = tflyManager.getTFlyTime(uuid);
        boolean newState = !tflyManager.isTFlyEnabled(uuid);
        tflyManager.setTFlyEnabled(uuid, newState);
        if (newState) {
            player.setAllowFlight(true);
            player.sendMessage("§aTfly enabled. Time remaining: " + TFlyManager.formatTime(timeRemaining));
            if (sender != player) sender.sendMessage("§aTfly enabled for " + player.getName() + ". Time remaining: " + TFlyManager.formatTime(timeRemaining));
        } else {
            tflyManager.disableFlight(player);
            player.sendMessage("§eTfly disabled. Time remaining: " + TFlyManager.formatTime(timeRemaining));
            if (sender != player) sender.sendMessage("§eTfly disabled for " + player.getName() + ". Time remaining: " + TFlyManager.formatTime(timeRemaining));
        }
    }

    private long parseTime(String s) {
        if (s == null || s.isEmpty()) return -1;
        s = s.trim().toLowerCase(Locale.ENGLISH);
        long mult = 1;
        if (s.endsWith("s")) { mult = 1; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 60; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("h")) { mult = 3600; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("d")) { mult = 86400; s = s.substring(0, s.length() - 1); }
        try {
            return Long.parseLong(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("fly")) return Collections.emptyList();
        TFlyManager tflyManager = plugin.getTFlyManager();
        if (args.length == 1) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            boolean canOthers = sender.hasPermission("allium.fly.others") || sender.hasPermission("allium.tfly.others");
            if (!canOthers) return Collections.emptyList();
            String a = args[0].toLowerCase(Locale.ENGLISH);
            return names.stream().filter(n -> n.toLowerCase(Locale.ENGLISH).startsWith(a)).collect(Collectors.toList());
        }
        if (args.length == 2 && tflyManager != null && (sender.hasPermission("allium.tfly.set") || sender.hasPermission("allium.tfly.add") || sender.hasPermission("allium.tfly.take") || sender.hasPermission("allium.tfly.reset"))) {
            return TFLY_ACTIONS.stream().filter(ac -> ac.startsWith(args[1].toLowerCase(Locale.ENGLISH))).collect(Collectors.toList());
        }
        if (args.length == 3 && tflyManager != null && sender.hasPermission("allium.tfly.set")) {
            return Arrays.asList("60", "10m", "1h", "1d");
        }
        return Collections.emptyList();
    }
}
