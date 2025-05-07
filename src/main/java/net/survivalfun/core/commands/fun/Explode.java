package net.survivalfun.core.commands.fun;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
    /**
     * Creates a sphere of TNT using WorldEdit (if available) and primes it
     * @param player The player who triggered the explosion
     * @param power The explosion power (determines sphere size)
     */
    private void createTNTSphere(Player player, int power) {
        // Calculate sphere radius based on power (capped for safety)
        int radius = 10; // Cap at radius 10 for safety

        // Get player location
        Location playerLoc = player.getLocation();

        // Log the attempt
        plugin.getLogger().info("Creating TNT sphere with radius " + radius + " at " +
                playerLoc.getX() + ", " + playerLoc.getY() + ", " + playerLoc.getZ());

        // Check if WorldEdit is available
        Plugin worldEditPlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin != null && worldEditPlugin.isEnabled()) {
            try {
                // Warn all players
                Bukkit.broadcastMessage("§4§l⚠ MEGA EXPLOSION INCOMING! ⚠");
                Bukkit.broadcastMessage("§c§lTriggered by " + player.getName() + " with power level " + power + "!");

                // Use WorldEdit console commands to create the sphere
                ConsoleCommandSender console = Bukkit.getConsoleSender();

                // Execute WorldEdit commands as console (which has all permissions)
                String world = playerLoc.getWorld().getName().toLowerCase();
                Bukkit.dispatchCommand(console, "/world " + world);
                String pos1Command = String.format("/pos1 %d,%d,%d",
                        playerLoc.getBlockX(), (playerLoc.getBlockY() + 1), playerLoc.getBlockZ());
                String pos2Command = String.format("/pos2 %d,%d,%d",
                        playerLoc.getBlockX(), (playerLoc.getBlockY() + 1), playerLoc.getBlockZ());
                Bukkit.dispatchCommand(console, pos1Command);
                Bukkit.dispatchCommand(console, pos2Command);
                Bukkit.dispatchCommand(console, "/hsphere tnt " + radius);
                Bukkit.dispatchCommand(console, "/hsphere light[level=10] " + (radius - 3));

                double x = playerLoc.getX();
                double y = playerLoc.getY() + 1;
                double z = playerLoc.getZ();
                // Show countdown titles to players within the radius
                Location explosionCenter = new Location(playerLoc.getWorld(), x, y, z);
                int nukeRadius = 45;

                // Get players in radius (store in a list to avoid recalculating each time)
                List<Player> playersInRadius = new ArrayList<>();
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.getWorld().equals(explosionCenter.getWorld()) &&
                            onlinePlayer.getLocation().distance(explosionCenter) <= nukeRadius) {
                        playersInRadius.add(onlinePlayer);
                    }
                }

                // Initial warning
                for (Player p : playersInRadius) {
                    p.sendTitle("§4§l⚠ CATACLYSM IMMINENT ⚠", "§c§lCOUNTDOWN INITIATED", 10, 30, 10);

                    // Initial warning sound
                    float volume = (float)(1.0 - (p.getLocation().distance(explosionCenter) / nukeRadius));
                    volume = Math.max(0.2f, volume);
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, volume, 0.7f);
                }

                // Countdown from 3 to 0
                for (int i = 3; i >= 0; i--) {
                    final int count = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        for (Player p : playersInRadius) {
                            if (count > 0) {
                                // Countdown numbers
                                p.sendTitle("§4§l" + count, "§c§lBRACE FOR IMPACT", 5, 15, 5);

                                // Beeping sound that gets faster and more intense
                                float pitch = 1.0f + (3 - count) * 0.1f;
                                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
                            } else {
                                // Final warning when countdown reaches 0
                                p.sendTitle("§4§l⚠ DETONATION ⚠", "§c§lRUN FOR YOUR LIFE!", 10, 70, 20);

                                // Final explosion sound
                                float volume = (float)(1.0 - (p.getLocation().distance(explosionCenter) / nukeRadius));
                                volume = Math.max(0.3f, volume);
                                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, volume, 0.5f);
                                p.playSound(p.getLocation(), org.bukkit.Sound.AMBIENT_CAVE, volume, 0.5f);

                                // Screen shake effect using small teleports
                                Location originalPosition = p.getLocation();
                                Random random = new Random();

                                // Create a screen shake effect with small teleports
                                for (int j = 0; j < 6; j++) {
                                    final int jFinal = j;
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        if (p.isOnline()) {
                                            // Small random offset
                                            double offsetX = (random.nextDouble() - 0.5) * 0.2;
                                            double offsetZ = (random.nextDouble() - 0.5) * 0.2;
                                            Location shakeLoc = originalPosition.clone().add(offsetX, 0, offsetZ);
                                            shakeLoc.setYaw(originalPosition.getYaw() + (float)((random.nextDouble() - 0.5) * 2));
                                            shakeLoc.setPitch(originalPosition.getPitch() + (float)((random.nextDouble() - 0.5) * 2));

                                            p.teleport(shakeLoc);
                                        }
                                    }, jFinal * 2L);
                                }

                                // Return to original position
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    if (p.isOnline()) {
                                        p.teleport(originalPosition);
                                    }
                                }, 14L);
                            }
                        }
                    }, (3 - i) * 20L); // 1 second between each countdown number
                }

                // Schedule a task to prime all the TNT blocks
                Bukkit.getScheduler().runTaskLater(plugin, () -> {

                    int count = primeTNTBlocks(new Location(playerLoc.getWorld(), x, y, z), radius);
                    plugin.getLogger().info("Primed " + count + " TNT blocks");

                    // Add dramatic effects
                    player.getWorld().playSound(playerLoc, org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f);
                    player.getWorld().playSound(playerLoc, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);

                    Bukkit.dispatchCommand(console, "removenear light[level=10] " + radius);
                    Bukkit.dispatchCommand(console, "removenear light[level=10] " + (radius - 3));
                }, 69L); // Longer delay to ensure WorldEdit has time to create the sphere

            } catch (Exception e) {
                plugin.getLogger().severe("Error creating TNT sphere with WorldEdit: " + e.getMessage());
                e.printStackTrace();

            }
        } else {
            plugin.getLogger().info("WorldEdit not available, using manual TNT sphere creation");
        }
    }
    /**
     * Primes all TNT blocks within a radius of the given location
     * @param center The center location
     * @param radius The radius to check
     * @return The number of TNT blocks primed
     */
    private int primeTNTBlocks(Location center, int radius) {
        int count = 0;
        int radiusSquared = radius * radius;

        // Check all blocks in a cube around the center
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // Only process blocks within the sphere
                    int i = x * x + y * y + z * z;
                    if (i <= radiusSquared) {
                        Location blockLoc = center.clone().add(x, y, z);
                        Block block = blockLoc.getBlock();

                        // Check if the block is TNT
                        if (block.getType() == Material.TNT) {
                            // Replace TNT block with primed TNT entity
                            block.setType(Material.AIR);

                            // Create primed TNT with random fuse time for cascading effect
                            TNTPrimed primedTNT = center.getWorld().spawn(
                                    blockLoc.clone().add(0.5, 0.5, 0.5), // Center of the block
                                    TNTPrimed.class
                            );

                            // Set random fuse time between 20-80 ticks (1-4 seconds)
                            primedTNT.setFuseTicks(20 + new Random().nextInt(60));

                            // Set yield based on distance from center for more dramatic effect
                            double distanceRatio = 1.0 - (Math.sqrt(i) / radius);
                            primedTNT.setYield((float)(4 * distanceRatio)); // Higher yield near center

                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    /**
     * Creates a sphere of TNT manually without using WorldEdit
     * @param player The player who triggered the explosion
     * @param radius The radius of the TNT sphere
     */
    private void createManualTNTSphere(Player player, int radius) {
        Location center = player.getLocation();
        int radiusSquared = radius * radius;
        int count = 0;

        // Warn all players
        Bukkit.broadcastMessage("§4§l⚠ MEGA EXPLOSION INCOMING! ⚠");
        Bukkit.broadcastMessage("§c§lTriggered by " + player.getName() + "!");

        // Create the sphere
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // Only place blocks within the sphere
                    if (x*x + y*y + z*z <= radiusSquared) {
                        Location blockLoc = center.clone().add(x, y, z);

                        // Create primed TNT directly instead of placing blocks
                        TNTPrimed primedTNT = center.getWorld().spawn(
                                blockLoc.clone().add(0.5, 0.5, 0.5), // Center of the block
                                TNTPrimed.class
                        );

                        // Set random fuse time between 20-80 ticks (1-4 seconds)
                        primedTNT.setFuseTicks(20 + new Random().nextInt(60));

                        // Set yield based on distance from center
                        double distanceRatio = 1.0 - (Math.sqrt(x*x + y*y + z*z) / radius);
                        primedTNT.setYield((float)(4 * distanceRatio)); // Higher yield near center

                        count++;

                        // Limit the number of TNT entities to prevent server crashes
                        if (count >= 1000) {
                            plugin.getLogger().warning("TNT count limit reached, stopping at 1000 TNT entities");
                            break;
                        }
                    }
                }
                if (count >= 1000) break;
            }
            if (count >= 1000) break;
        }

        plugin.getLogger().info("Created " + count + " primed TNT entities manually");

        // Add dramatic effects
        player.getWorld().playSound(center, org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f);
        player.getWorld().playSound(center, org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);

        // Show title to all players
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.sendTitle("§4§l⚠ CATACLYSM ⚠", "§c§lRUN FOR YOUR LIFE!", 10, 70, 20);
        }
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
                        Text.sendErrorMessage(playerSender, "player-not-found", lang, "{name}", args[0]);
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
                if (!playerSender.hasPermission("core.explode")) {
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
                sender.sendMessage(lang.get("explode.other").replace("{name}", targetPlayer.getName()).replace("{power}", String.valueOf(explosionPower)));
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
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
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
        // Ensure power is within the valid range (1-127)
        int effectivePower = Math.min(127, Math.max(1, power));

        // Get player location
        double x = player.getLocation().getX();
        double y = player.getLocation().getY() + 1;
        double z = player.getLocation().getZ();

        // Log the attempt
        plugin.getLogger().info("Creating fireball with power " + effectivePower + " at " +
                x + ", " + y + ", " + z);

        // Create the fireball using the summon command
        String summonCommand = String.format(
                "summon minecraft:fireball %f %f %f {ExplosionPower:%d,Motion:[0.0,-3.0,0.0]}",
                x, y, z, effectivePower
        );

        // Dispatch the command
        boolean success = player.getServer().dispatchCommand(
                player.getServer().getConsoleSender(),
                summonCommand
        );

        if (!success) {
            plugin.getLogger().warning("Failed to create fireball with command: " + summonCommand);
            // Try alternative method if command fails
            try {
                org.bukkit.entity.Fireball fireball = player.getWorld().spawn(
                        new Location(player.getWorld(), x, y, z),
                        org.bukkit.entity.Fireball.class
                );
                fireball.setVelocity(new org.bukkit.util.Vector(0, -3, 0));
                fireball.setYield(effectivePower);
                fireball.setIsIncendiary(true);
                plugin.getLogger().info("Created fireball using direct API");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to create fireball using direct API: " + e.getMessage());
            }
        }

        // Define effect tiers based on power ranges
        // Each tier adds to the effects of previous tiers
        applyFireballEffects(player, effectivePower, x, y, z);
    }

    /**
     * Applies visual and sound effects based on the fireball power level
     * @param player The player who triggered the explosion
     * @param power The explosion power (1-127)
     * @param x The x-coordinate of the fireball
     * @param y The y-coordinate of the fireball
     * @param z The z-coordinate of the fireball
     */
    private void applyFireballEffects(Player player, int power, double x, double y, double z) {
        // Tier 1: Basic Effects (power 1-15)
        if (power >= 1) {
            // Basic flame particles
            player.getWorld().spawnParticle(org.bukkit.Particle.FLAME, player.getLocation(),
                    20 + power * 2, 1.0, 0.5, 1.0, 0.05);

            // Basic explosion sound
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.0f);
        }

        // Tier 2: Enhanced Effects (power 16-30)
        if (power >= 16) {
            // Add thunder sound
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.9f);

            // More intense particles
            player.getWorld().spawnParticle(org.bukkit.Particle.LAVA, player.getLocation(),
                    10 + (power - 15) * 2, 1.5, 0.5, 1.5, 0.1);

            // Add a single lightning strike for visual effect (no damage)
            String lightningCommand = String.format(
                    "summon minecraft:lightning_bolt %f %f %f {damage:0}",
                    x, y - 15, z
            );
            player.getServer().dispatchCommand(player.getServer().getConsoleSender(), lightningCommand);
        }

        // Tier 3: Advanced Effects (power 31-50)
        if (power >= 31) {
            // Add screen shake effect (using title)
            player.sendTitle("", "§c§l*BOOM*", 5, 15, 10);

            // Play wither spawn sound
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.7f);

            // Add explosion particles
            player.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, player.getLocation(),
                    5 + (power - 30) / 4, 2.0, 1.0, 2.0, 0.1);

            // Add smoke particles
            player.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, player.getLocation(),
                    50 + (power - 30) * 2, 3.0, 1.5, 3.0, 0.1);
        }

        // Tier 4: Epic Effects (power 51-75)
        if (power >= 51) {
            // Multiple lightning strikes in a pattern
            for (int i = 0; i < 5; i++) {
                final int offset = i;
                player.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    double angle = offset * (Math.PI * 2 / 5);
                    double offsetX = Math.cos(angle) * 3;
                    double offsetZ = Math.sin(angle) * 3;

                    String delayedLightning = String.format(
                            "summon minecraft:lightning_bolt %f %f %f {damage:0}",
                            x + offsetX, player.getLocation().getY(), z + offsetZ
                    );
                    player.getServer().dispatchCommand(player.getServer().getConsoleSender(), delayedLightning);
                }, i * 5L);
            }

            // Add end crystal explosion particles
            player.getWorld().spawnParticle(org.bukkit.Particle.FLASH, player.getLocation(),
                    8 + (power - 50) / 5, 2.0, 1.0, 2.0, 0.1);

            // Play dragon death sound
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 0.4f, 0.6f);

            // Add dramatic screen effect
            player.sendTitle("§4§lMEGA EXPLOSION", "§c§lIncoming!!!", 10, 20, 10);
        }

        // Tier 5: Legendary Effects (power 76-100)
        if (power >= 76) {
            // Create a ring of fire particles
            int radius = 5 + (power - 75) / 5;
            for (int i = 0; i < 360; i += 10) {
                double angle = Math.toRadians(i);
                double ringX = x + (radius * Math.cos(angle));
                double ringZ = z + (radius * Math.sin(angle));

                player.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                        new org.bukkit.Location(player.getWorld(), ringX, player.getLocation().getY(), ringZ),
                        10, 0.2, 0.5, 0.2, 0.05);
            }

            // Add end gateway beam effect
            player.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, player.getLocation().add(0, 1, 0),
                    150 + (power - 75) * 3, 0, 10, 0, 0.5);

            // Play dramatic sounds
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_WITHER_DEATH, 0.5f, 0.8f);

            // Broadcast a server message
            player.getServer().broadcastMessage("§4§l[!] §r§cA massive explosion has been triggered by §6" +
                    player.getName() + "§c!");
        }

        // Tier 6: Apocalyptic Effects (power 101-127)
        if (power >= 101) {
            // Create a spiral particle effect ascending to the sky
            for (int i = 0; i < 15; i++) {
                final int height = i;
                player.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    for (int angle = 0; angle < 360; angle += 15) {
                        double radians = Math.toRadians(angle);
                        double spiralRadius = 7 - (height * 0.4);
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
            int shockwaveRadius = 10 + (power - 100) / 3;
            for (int radius = 1; radius <= shockwaveRadius; radius++) {
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
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.3f);
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.4f);
            player.getWorld().playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);

            // Server-wide dramatic effect
            for (Player onlinePlayer : player.getServer().getOnlinePlayers()) {
                // Screen shake for everyone
                onlinePlayer.sendTitle("§4§l⚠ CATACLYSM ⚠",
                        "§c§lTriggered by " + player.getName(), 10, 30, 20);

                // Play dramatic sound for all players
                onlinePlayer.playSound(onlinePlayer.getLocation(),
                        org.bukkit.Sound.AMBIENT_CAVE, 1.0f, 0.5f);
            }

            // For maximum power (120+), create a TNT sphere for additional destruction
            if (power >= 120) {
                createTNTSphere(player, power);
            }
        }
    }

    private void summonCommand(Player player, int power, double x, double y, double z) {
        try {
            // Create a direct command that uses the latest syntax for 1.21.5
            String summonCommand = String.format(
                    "summon minecraft:fireball %f %f %f {ExplosionPower:%d,Motion:[0.0,-3.0,0.0],power:%d}",
                    x, y, z, power, power
            );

            // Execute the command synchronously to ensure it runs on the main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean success = player.getServer().dispatchCommand(
                        player.getServer().getConsoleSender(),
                        summonCommand
                );
                plugin.getLogger().info("Command dispatch result: " + success + " for command: " + summonCommand);
            });

            // As a backup, also try to spawn the fireball directly
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    // Create the fireball entity directly
                    org.bukkit.entity.Fireball fireball = player.getWorld().spawn(
                            new Location(player.getWorld(), x, y, z),
                            org.bukkit.entity.Fireball.class
                    );

                    // Set properties
                    fireball.setVelocity(new org.bukkit.util.Vector(0, -3, 0));
                    fireball.setYield(power);
                    fireball.setIsIncendiary(true);
                    fireball.setDirection(new org.bukkit.util.Vector(0, -3, 0));

                    plugin.getLogger().info("Created backup fireball with direct API");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to create backup fireball: " + e.getMessage());
                }
            }, 5L); // Slight delay for the backup method

        } catch (Exception e) {
            plugin.getLogger().severe("Error creating fireball: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
