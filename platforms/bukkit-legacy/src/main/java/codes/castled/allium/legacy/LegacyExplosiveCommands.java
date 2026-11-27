package codes.castled.allium.legacy;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Ocelot;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

final class LegacyExplosiveCommands implements Listener {
    private static final String CANNON_TAG = "allium.legacy_cannon";
    private static final String NUKE_TAG = "allium.legacy_nuke";

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final BiPredicate<CommandSender, Player> canSee;
    private final EntityType cat = findType("CAT");
    private final EntityType bee = findType("BEE");

    LegacyExplosiveCommands(JavaPlugin plugin, LegacyMessageManager messages, BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.messages = messages;
        this.canSee = canSee;
    }

    boolean cannon(CommandSender sender, String command, String[] args) {
        if (!sender.hasPermission("allium." + command)) return refuse(sender);
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only", "{prefix} &cThis command can only be used by players.");
            return true;
        }
        if (args.length != 0) return false;

        Player player = (Player) sender;
        boolean kitten = "kittycannon".equals(command);
        EntityType type = kitten ? cat == null ? EntityType.OCELOT : cat : bee;
        if (type == null) {
            messages.send(sender, "explosion.cannon-unavailable", "{prefix} &cThis mob is unavailable on this Minecraft version.");
            return true;
        }
        Location eye = player.getEyeLocation();
        Entity flier = player.getWorld().spawnEntity(eye, type);
        if (!flier.isValid()) return true;
        if (!flier.addScoreboardTag(CANNON_TAG)) {
            flier.remove();
            return true;
        }
        if (kitten) prepareKitten(flier);
        if (flier instanceof LivingEntity) ((LivingEntity) flier).setAI(false);
        flier.setInvulnerable(true);
        flier.setVelocity(eye.getDirection().multiply(2.0D));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> pop(flier), 20L);
        plugin.getLogger().info(command + " used by " + player.getName());
        return true;
    }

    private static void prepareKitten(Entity kitten) {
        if (kitten instanceof Ageable) ((Ageable) kitten).setBaby();
        if (kitten instanceof Tameable) ((Tameable) kitten).setTamed(true);
        if ("OCELOT".equals(kitten.getType().name()) && kitten instanceof Ocelot) {
            Ocelot.Type[] coats = {Ocelot.Type.BLACK_CAT, Ocelot.Type.RED_CAT, Ocelot.Type.SIAMESE_CAT};
            ((Ocelot) kitten).setCatType(coats[ThreadLocalRandom.current().nextInt(coats.length)]);
        }
    }

    private static void pop(Entity flier) {
        if (!flier.isValid()) return;
        Location at = flier.getLocation();
        flier.remove();
        World world = at.getWorld();
        if (world != null) world.createExplosion(at.getX(), at.getY(), at.getZ(), 0.0f, false, false);
    }

    boolean nuke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.nuke")) return refuse(sender);
        Set<Player> targets = new LinkedHashSet<>();
        if (args.length == 0) {
            if (!(sender instanceof Player)) return false;
            targets.add((Player) sender);
        } else {
            for (String name : args) {
                Player target = plugin.getServer().getPlayerExact(name);
                if (target == null || !canSee.test(sender, target)) {
                    messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", name);
                    return true;
                }
                targets.add(target);
            }
        }
        for (Player target : targets) {
            dropNuke(target);
            messages.send(target, "explosion.nuke.incoming", "{prefix} &5May death rain upon them.");
        }
        String names = targets.stream().map(Player::getName).collect(Collectors.joining(", "));
        if (targets.size() != 1 || !targets.contains(sender)) {
            messages.send(sender, "explosion.nuke.sent", "{prefix} &5Nuked &f{players}&5.", "{players}", names);
        }
        plugin.getLogger().info("nuke used by " + sender.getName() + " for " + names);
        return true;
    }

    List<String> completeNuke(CommandSender sender, String[] args, String prefix) {
        if (args.length == 0 || !sender.hasPermission("allium.nuke")) return Collections.emptyList();
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < args.length - 1; i++) selected.add(args[i].toLowerCase(Locale.ROOT));
        return plugin.getServer().getOnlinePlayers().stream().filter(player -> canSee.test(sender, player))
                .map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)
                        && !selected.contains(name.toLowerCase(Locale.ROOT)))
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
    }

    private static void dropNuke(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        int y = world.getHighestBlockYAt(at) + 64;
        for (int dx = -10; dx <= 10; dx += 5) {
            for (int dz = -10; dz <= 10; dz += 5) {
                Location spawn = new Location(world, at.getBlockX() + dx + 0.5D, y, at.getBlockZ() + dz + 0.5D);
                TNTPrimed tnt = world.spawn(spawn, TNTPrimed.class);
                if (!tnt.addScoreboardTag(NUKE_TAG)) tnt.remove();
                else tnt.setIsIncendiary(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (fromNuke(event.getEntity())) event.setFire(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (fromNuke(event.getEntity())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent && event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION
                && fromNuke(((HangingBreakByEntityEvent) event).getRemover())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Arrays.stream(event.getChunk().getEntities()).filter(LegacyExplosiveCommands::temporary).forEach(Entity::remove);
    }

    void close() {
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntities().stream().filter(LegacyExplosiveCommands::temporary).forEach(Entity::remove);
        }
    }

    private static boolean temporary(Entity entity) {
        return fromNuke(entity) || entity.getScoreboardTags().contains(CANNON_TAG);
    }

    private static boolean fromNuke(Entity entity) {
        return entity instanceof TNTPrimed && entity.getScoreboardTags().contains(NUKE_TAG);
    }

    private static EntityType findType(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }
}
