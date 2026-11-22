package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.message.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

public final class RemoveCommand implements SpecCommandHandler {

    private static final List<String> GROUPS = List.of("all", "tamed", "named", "drops", "arrows", "boats",
            "minecarts", "xp", "paintings", "itemframes", "endercrystals", "monsters", "animals", "ambient", "mobs");
    private static final String USAGE = "/remove <type> [radius] [world]";
    public static final CommandSpec SPEC = new CommandSpec(
            "remove", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("butcher", "killall", "mobkill"),
            "Remove matching entities from loaded chunks", USAGE, "/remove <type> <world>",
            "allium.remove", null, List.of(CommandArgSpec.required("type", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("radiusOrWorld", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("world", null, SuggestionSpec.none())), List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    private final PluginStart plugin;

    public RemoveCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        var sender = context.sender();
        String[] args = context.args();
        Player player = sender instanceof Player p ? p : null;
        if (args.length < (player == null ? 2 : 1) || args.length > 3) {
            context.sendInvalidArgs(SPEC.selfUsage());
            return;
        }
        List<String> groups = List.of(args[0].toLowerCase(Locale.ROOT).split(",", -1));
        List<Predicate<Entity>> filters = new ArrayList<>();
        for (String group : groups) {
            Predicate<Entity> filter = filter(group);
            if (filter == null) {
                context.sendInvalidArgs(SPEC.selfUsage());
                return;
            }
            filters.add(filter);
        }
        int radius = 0;
        String worldName = null;
        if (args.length >= 2) {
            if (args[1].matches("[+-]?[0-9]+")) {
                try {
                    radius = Integer.parseInt(args[1]);
                } catch (NumberFormatException invalid) {
                    radius = -1;
                }
                if (radius <= 0 || radius > 512 || player == null) {
                    UtilitySupport.send(plugin, sender, Messages.NUMBER_INVALID, "{input}", args[1]);
                    return;
                }
                if (args.length == 3) worldName = args[2];
            } else if (args.length == 2) {
                worldName = args[1];
            } else {
                context.sendInvalidArgs(SPEC.selfUsage());
                return;
            }
        }
        World world = worldName == null ? player.getWorld() : plugin.getServer().getWorld(worldName);
        if (world == null || radius > 0 && !world.equals(player.getWorld())) {
            UtilitySupport.send(plugin, sender, Messages.WORLD_NOT_FOUND, "{world}", worldName);
            return;
        }
        if (radius == 0 && !sender.hasPermission("allium.remove.world")) {
            UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION, "{cmd}", "remove across a world");
            return;
        }
        Location center = radius > 0 ? player.getLocation() : null;
        int removed = 0;
        var protection = plugin.getProtectionService();
        for (var chunk : world.getLoadedChunks()) {
            if (center != null && (Math.abs(chunk.getX() - (center.getBlockX() >> 4)) > (radius >> 4) + 1
                    || Math.abs(chunk.getZ() - (center.getBlockZ() >> 4)) > (radius >> 4) + 1)) continue;
            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof HumanEntity || entity.hasMetadata("NPC")
                        || center != null && center.distanceSquared(entity.getLocation()) > (double) radius * radius
                        || entity instanceof Tameable pet && pet.isTamed() && !groups.contains("tamed")
                        || entity.getCustomName() != null && !groups.contains("named")
                        || filters.stream().noneMatch(test -> test.test(entity))) continue;
                if (player != null && protection != null
                        && !protection.checkEntityInteraction(player, entity, "remove", null).allowed()) continue;
                entity.remove();
                removed++;
            }
        }
        UtilitySupport.send(plugin, sender, Messages.REMOVE_COUNT, "{count}", Integer.toString(removed));
        UtilitySupport.audit(plugin, sender, "remove", "world=" + world.getName() + " groups=" + args[0]
                + " radius=" + radius + " removed=" + removed);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            int comma = prefix.lastIndexOf(',') + 1;
            var types = new java.util.TreeSet<>(GROUPS);
            for (EntityType type : EntityType.values()) {
                if (type != EntityType.UNKNOWN && type != EntityType.PLAYER) types.add(type.getKey().getKey());
            }
            return types.stream().filter(type -> type.startsWith(prefix.substring(comma)))
                    .limit(64).map(type -> args[0].substring(0, comma) + type).toList();
        }
        if (args.length == 2 || args.length == 3 && args[1].matches("[0-9]+")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private static Predicate<Entity> filter(String group) {
        return switch (group) {
            case "all", "*" -> entity -> true;
            case "tamed" -> entity -> entity instanceof Tameable pet && pet.isTamed();
            case "named" -> entity -> entity.getCustomName() != null;
            case "drops" -> Item.class::isInstance;
            case "arrows" -> AbstractArrow.class::isInstance;
            case "boats" -> Boat.class::isInstance;
            case "minecarts" -> Minecart.class::isInstance;
            case "xp" -> ExperienceOrb.class::isInstance;
            case "paintings" -> Painting.class::isInstance;
            case "itemframes" -> ItemFrame.class::isInstance;
            case "endercrystals" -> EnderCrystal.class::isInstance;
            case "monsters" -> entity -> entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast;
            case "animals" -> entity -> entity instanceof Animals || entity instanceof WaterMob;
            case "ambient" -> Ambient.class::isInstance;
            case "mobs" -> LivingEntity.class::isInstance;
            default -> {
                EntityType type = MobSpawner.lookup(group).map(MobSpawner.MobEntry::type).orElse(null);
                yield type == null ? null : entity -> entity.getType() == type;
            }
        };
    }
}
