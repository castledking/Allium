package net.survivalfun.core.commands.fun;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class Explode implements CommandExecutor {
    private final PluginStart plugin;
    private org.bukkit.configuration.file.FileConfiguration config;
    private int MIN_EXPLOSION_POWER;
    private int MAX_EXPLOSION_POWER;
    private int DEFAULT_EXPLOSION_POWER;
    private Lang lang;

    public Explode(PluginStart plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.lang = plugin.getLangManager();
        loadConfigValues();
    }

    /**
     * Reloads the configuration values from the config file
     */
    public void reloadConfig() {
        // Get the latest config from the plugin
        this.config = plugin.getConfig();

        // Reload the values
        loadConfigValues();

        // Log that we've reloaded
        plugin.getLogger().info("Reloaded explosion command configuration: min=" +
                MIN_EXPLOSION_POWER + ", max=" + MAX_EXPLOSION_POWER +
                ", default=" + DEFAULT_EXPLOSION_POWER);
    }

    private void loadConfigValues() {
        MIN_EXPLOSION_POWER = config.getInt("explode-command.min", 1);
        MAX_EXPLOSION_POWER = config.getInt("explode-command.max", 10);
        DEFAULT_EXPLOSION_POWER = config.getInt("explode-command.default", 2);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        int explosionPower = DEFAULT_EXPLOSION_POWER;
        Player targetPlayer = null;

        // Handle different command scenarios based on sender type and arguments
        if (sender instanceof Player playerSender) {
            // Player executing the command
            if (args.length > 0) {
                try {
                    // Try to parse first argument as explosion power
                    explosionPower = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    // First argument is not a number, try to find a player with that name
                    targetPlayer = plugin.getServer().getPlayer(args[0]);
                    if (targetPlayer == null) {
                        // Player not found
                        Text.sendErrorMessage(playerSender, "player-not-found", lang, "{player}", args[0]);
                        return true;
                    }

                    // If there's a second argument, try to parse it as explosion power
                    if (args.length > 1) {
                        try {
                            explosionPower = Integer.parseInt(args[1]);
                        } catch (NumberFormatException ex) {
                            // Simple usage
                            Text.sendErrorMessage(playerSender, "explode.invalid", lang, "{min}", MIN_EXPLOSION_POWER, "{max}", MAX_EXPLOSION_POWER);

                            return true;
                        }
                    }
                }
            }

            // Validate explosion power
            if (explosionPower < MIN_EXPLOSION_POWER || explosionPower > MAX_EXPLOSION_POWER) {
                // Get the formatted message using Color.formatErrorMessage
                Text.sendErrorMessage(playerSender, "explode.invalid", lang, "{min}", MIN_EXPLOSION_POWER, "{max}", MAX_EXPLOSION_POWER);

                return true;
            }

            // Check permissions based on target
            if (targetPlayer == null) {
                // Self-explosion
                if (!playerSender.hasPermission("core.explode.self")) {
                    Text.sendErrorMessage(playerSender, "explode.no-permission", lang);
                    return true;
                }
                targetPlayer = playerSender;
            } else if (!targetPlayer.equals(playerSender)) {
                // Exploding another player
                if (!playerSender.hasPermission("core.explode.other")) {
                    Text.sendErrorMessage(playerSender, "explode.no-permission", lang);
                    return true;
                }
            }

            // Create explosion
            createFireball(targetPlayer, explosionPower);

            // Send success message
            if (targetPlayer.equals(playerSender)) {
                sender.sendMessage(lang.get("explode.self").replace("{power}", String.valueOf(explosionPower)));
            } else {
                sender.sendMessage(lang.get("explode.other").replace("{player}", targetPlayer.getName()).replace("{power}", String.valueOf(explosionPower)));
            }
        } else if (sender instanceof ConsoleCommandSender) {
            // Console executing the command
            if (args.length == 0) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "<player> [power]"));
                return true;
            }

            // First argument must be a player name when console is the sender
            targetPlayer = plugin.getServer().getPlayer(args[0]);
            if (targetPlayer == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", args[0]);
                return true;
            }

            // If there's a second argument, try to parse it as explosion power
            if (args.length > 1) {
                try {
                    explosionPower = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    Text.sendErrorMessage(sender , "explode.invalid", lang, "{min}", MIN_EXPLOSION_POWER, "{max}", MAX_EXPLOSION_POWER);

                    return true;
                }
            }

            // Validate explosion power
            if (explosionPower < MIN_EXPLOSION_POWER || explosionPower > MAX_EXPLOSION_POWER) {
                Text.sendErrorMessage(sender, "explode.invalid", lang, "{min}", MIN_EXPLOSION_POWER, "{max}", MAX_EXPLOSION_POWER);

                return true;
            }

            // Create explosion
            createFireball(targetPlayer, explosionPower);

            // Send success message
            String message = plugin.getLangManager().format(
                    plugin.getLangManager().get("explode.other"),
                    Map.of("player", targetPlayer.getName(), "power", String.valueOf(explosionPower))
            );
            sender.sendMessage(message);
        } else {
            // Other sender types
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        return true;
    }

    private void createFireball(Player player, int power) {
        // Get player location
        double x = player.getLocation().getX();
        double y = player.getLocation().getY() + 20;
        double z = player.getLocation().getZ();

        // Base fireball command
        String summonCommand = String.format(
                "summon minecraft:fireball %f %f %f {ExplosionPower:%d,Motion:[0.0,-3.0,0.0]}",
                x, y, z, power
        );

        // Dispatch the fireball command
        player.getServer().dispatchCommand(player.getServer().getConsoleSender(), summonCommand);

        // Add additional effects based on power level
        if (power >= 5) {
            // Level 1 effects (power 5+)
            // Play thunder sound
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.8f);

            // Add some flame particles at player location
            player.getWorld().spawnParticle(org.bukkit.Particle.FLAME, player.getLocation(), 50, 1.5, 1, 1.5, 0.1);
        }

        if (power >= 10) {
            // Level 2 effects (power 10+)
            // Add lightning strike for visual effect (no damage)
            String lightningCommand = String.format(
                    "summon minecraft:lightning_bolt %f %f %f {damage:0}",
                    x, y - 15, z
            );
            player.getServer().dispatchCommand(player.getServer().getConsoleSender(), lightningCommand);

            // More intense particles
            player.getWorld().spawnParticle(org.bukkit.Particle.LAVA, player.getLocation(), 30, 2, 1, 2, 0.1);
            player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 10, 2, 1, 2, 0.1);
        }

        if (power >= 15) {
            // Level 3 effects (power 15+)
            // Add dramatic screen shake effect (using title)
            player.sendTitle("", "§c§l*BOOM*", 5, 15, 10);

            // Play wither spawn sound for extra drama
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.5f);

            // Add more particles in a wider area
            player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 5, 3, 2, 3, 0.1);
            player.getWorld().spawnParticle(Particle.LARGE_SMOKE, player.getLocation(), 100, 4, 2, 4, 0.1);
        }

        if (power >= 20) {
            // Level 4 effects (power 20+)
            // Multiple lightning strikes in a pattern
            for (int i = 0; i < 5; i++) {
                final int offset = i;
                // Schedule delayed lightning strikes in a circle
                player.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    double angle = offset * (Math.PI * 2 / 5);
                    double offsetX = Math.cos(angle) * 3;
                    double offsetZ = Math.sin(angle) * 3;

                    String delayedLightning = String.format(
                            "summon minecraft:lightning_bolt %f %f %f {damage:0}",
                            x + offsetX, player.getLocation().getY(), z + offsetZ
                    );
                    player.getServer().dispatchCommand(player.getServer().getConsoleSender(), delayedLightning);
                }, i * 5L); // 5 tick delay between each lightning
            }

            // Add end crystal explosion particles
            player.getWorld().spawnParticle(org.bukkit.Particle.FLASH, player.getLocation(), 10, 2, 2, 2, 0.1);

            // Play dragon death sound
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 0.4f, 0.5f);

            // Add dramatic screen effect
            player.sendTitle("§4§lMEGA EXPLOSION", "§c§lIncoming!!!", 10, 20, 10);
        }

        if (power >= 25) {
            // Level 5 effects (power 25+)
            // Create a temporary ring of fire (harmless, visual only)
            int radius = 5;
            for (int i = 0; i < 360; i += 10) {
                double angle = Math.toRadians(i);
                double ringX = x + (radius * Math.cos(angle));
                double ringZ = z + (radius * Math.sin(angle));

                // Spawn fire particles in a ring
                player.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                        new org.bukkit.Location(player.getWorld(), ringX, player.getLocation().getY(), ringZ),
                        10, 0.2, 0.5, 0.2, 0.05);
            }

            // Add end gateway beam effect
            player.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, player.getLocation().add(0, 1, 0),
                    200, 0, 10, 0, 0.5);

            // Play dramatic sounds
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_DEATH, 0.5f, 0.8f);

            // Broadcast a server message for truly epic explosions
            player.getServer().broadcastMessage("§4§l[!] §r§cA massive explosion has been triggered by §6" + player.getName() + "§c!");
        }

        if (power >= 30) {
            // Level 6 effects (power 30+) - The ultimate explosion
            // Create a spiral particle effect ascending to the sky
            for (int i = 0; i < 10; i++) {
                final int height = i;
                player.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    for (int angle = 0; angle < 360; angle += 15) {
                        double radians = Math.toRadians(angle);
                        double spiralRadius = 5 - (height * 0.5);
                        if (spiralRadius < 0.5) spiralRadius = 0.5;

                        double spiralX = x + (spiralRadius * Math.cos(radians));
                        double spiralY = player.getLocation().getY() + height;
                        double spiralZ = z + (spiralRadius * Math.sin(radians));

                        // Alternate particles for a more dramatic effect
                        org.bukkit.Particle particle = (angle % 30 == 0) ?
                                org.bukkit.Particle.SOUL_FIRE_FLAME : org.bukkit.Particle.FLAME;

                        player.getWorld().spawnParticle(particle,
                                new org.bukkit.Location(player.getWorld(), spiralX, spiralY, spiralZ),
                                5, 0.1, 0.1, 0.1, 0.01);
                    }
                }, i * 2L);
            }

            // Create a shockwave effect
            for (int radius = 1; radius <= 15; radius++) {
                final int r = radius;
                player.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    for (int angle = 0; angle < 360; angle += 10) {
                        double radians = Math.toRadians(angle);
                        double waveX = x + (r * Math.cos(radians));
                        double waveZ = z + (r * Math.sin(radians));

                        player.getWorld().spawnParticle(org.bukkit.Particle.CLOUD,
                                new org.bukkit.Location(player.getWorld(), waveX, player.getLocation().getY(), waveZ),
                                1, 0.1, 0.1, 0.1, 0.05);
                    }
                }, radius * 2L);
            }

            // Play the most dramatic sound combination
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.3f);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.4f);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);

            // Server-wide dramatic effect
            for (Player onlinePlayer : player.getServer().getOnlinePlayers()) {
                // Screen shake for everyone
                onlinePlayer.sendTitle("§4§l⚠ CATACLYSM ⚠", "§c§lTriggered by " + player.getName(), 10, 30, 20);

                // Play dramatic sound for all players
                onlinePlayer.playSound(onlinePlayer.getLocation(), org.bukkit.Sound.AMBIENT_CAVE, 1.0f, 0.5f);
            }
        }
    }

}
