package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.spec.ItemSpecLexer;
import codes.castled.allium.naming.OnlinePlayerMatcher;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Loaded targets only. Visibility is applied before sorting, counting, and limiting. */
public final class TeleportTargets {
    public record Resolution(List<Entity> targets, int matchedCount, boolean selector, boolean entitySelector) {}
    private record Query(char kind, World world, Location origin, Map<String, String> args, String sort, Integer limit) {}
    private record Match(Entity entity, double distanceSquared, boolean vanished) {}

    private static final Set<String> OPTIONS = Set.of("type", "name", "tag", "gamemode", "gm", "distance",
            "limit", "sort", "world", "x", "y", "z", "dx", "dy", "dz");
    private static final int DEFAULT_LIMIT = 128;
    private final PluginStart plugin;

    public TeleportTargets(PluginStart plugin) {
        this.plugin = plugin;
    }

    public static boolean selectors(CommandSender sender) {
        return sender.hasPermission("allium.selectors") || sender.hasPermission("allium.admin");
    }

    public static void requirePermission(CommandSender sender, String token) {
        if (!token.startsWith("@") || token.equalsIgnoreCase("@s")) return;
        if (!selectors(sender)) throw new SecurityException("allium.selectors");
        if (token.length() > 2048 || !token.matches("@[pares](\\[.*])?")) throw new IllegalArgumentException("Invalid selector");
        String permission = switch (token.charAt(1)) {
            case 'a' -> "allium.command.selector.all";
            case 'e' -> "allium.command.selector.entities";
            default -> null;
        };
        if (permission != null && !sender.hasPermission(permission) && !sender.hasPermission("allium.admin")) {
            throw new SecurityException(permission);
        }
    }

    public List<Entity> resolve(CommandSender sender, String token) {
        return resolveLocal(sender, token, DEFAULT_LIMIT, null, true).targets();
    }

    public Resolution resolveDetailed(CommandSender sender, String token, int maxEntities, Entity exclude) {
        return resolveLocal(sender, token, Math.max(1, maxEntities), exclude, false);
    }

    public CompletableFuture<List<Entity>> resolveAsync(CommandSender sender, String token) {
        return resolveAsync(sender, token, DEFAULT_LIMIT, null, true).thenApply(Resolution::targets);
    }

    public CompletableFuture<Resolution> resolveDetailedAsync(CommandSender sender, String token, int maxEntities, Entity exclude) {
        return resolveAsync(sender, token, Math.max(1, maxEntities), exclude, false);
    }

    private Resolution resolveLocal(CommandSender sender, String token, int maxEntities, Entity exclude, boolean truncate) {
        if (!token.startsWith("@")) {
            Player matched = OnlinePlayerMatcher.match(
                    plugin,
                    plugin.getServer().getOnlinePlayers(),
                    token,
                    player -> player != exclude && PlayerNameVisibility.canSee(sender, player));
            return matched == null
                    ? new Resolution(List.of(), 0, false, false)
                    : new Resolution(List.of(matched), 1, false, false);
        }
        if (token.equalsIgnoreCase("@s")) return self(sender, exclude);
        Query query = parse(sender, token, maxEntities);
        var matches = candidates(sender, query, false).stream()
                .filter(entity -> !(entity instanceof Player player) || PlayerNameVisibility.canSee(sender, player))
                .map(entity -> match(sender, query, exclude, entity, false))
                .filter(java.util.Objects::nonNull)
                .toList();
        return finish(query, matches, maxEntities, truncate);
    }

    /** Called on the issuer's owner and completes there. Folia collects live fields on each candidate owner. */
    private CompletableFuture<Resolution> resolveAsync(CommandSender sender, String token, int maxEntities, Entity exclude, boolean truncate) {
        if (!BukkitServerThread.isFolia()) {
            return CompletableFuture.completedFuture(resolveLocal(sender, token, maxEntities, exclude, truncate));
        }
        if (token.equalsIgnoreCase("@s")) return CompletableFuture.completedFuture(self(sender, exclude));
        if (!token.startsWith("@")) {
            return collect(sender, namedCandidates(token, exclude), entity -> {
                Player player = (Player) entity;
                return player.isOnline() ? new Match(player, 0, hiddenFromOtherViewers(sender, player)) : null;
            }, snapshots -> namedResult(snapshots.stream().filter(match -> visible(sender, match))
                    .map(match -> (Player) match.entity()).toList(), exclude));
        }
        Query query = parse(sender, token, maxEntities);
        return collect(sender, candidates(sender, query, true), entity -> match(sender, query, exclude, entity, true), snapshots -> {
            requirePermission(sender, token);
            return finish(query, snapshots.stream().filter(match -> visible(sender, match)).toList(), maxEntities, truncate);
        });
    }

    private List<Player> namedCandidates(String token, Entity exclude) {
        Player exact = plugin.getServer().getPlayerExact(token);
        if (exact != null && exact != exclude) return List.of(exact);
        return OnlinePlayerMatcher.rankedMatches(
                plugin.getServer().getOnlinePlayers(),
                token,
                player -> player != exclude,
                player -> OnlinePlayerMatcher.nickname(plugin, player));
    }

    private static Resolution namedResult(List<Player> visible, Entity exclude) {
        for (Player candidate : visible) {
            if (candidate != null && candidate != exclude) {
                return new Resolution(List.of(candidate), 1, false, false);
            }
        }
        return new Resolution(List.of(), 0, false, false);
    }

    private static Resolution self(CommandSender sender, Entity exclude) {
        if (!(sender instanceof Entity entity) || entity == exclude || !entity.isValid()) {
            return new Resolution(List.of(), 0, true, false);
        }
        return new Resolution(List.of(entity), 1, true, false);
    }

    private Query parse(CommandSender sender, String token, int maxEntities) {
        requirePermission(sender, token);
        char kind = token.charAt(1);
        Map<String, String> args = new HashMap<>();
        if (token.length() > 2) {
            String body = token.substring(3, token.length() - 1);
            if (!body.isBlank()) {
                for (String entry : ItemSpecLexer.splitTopLevel(body, ',')) {
                    String[] pair = entry.split("=", 2);
                    if (pair.length != 2 || !OPTIONS.contains(pair[0])
                            || args.put(pair[0], pair[1].replaceAll("^\"|\"$", "")) != null) {
                        throw new IllegalArgumentException("Invalid selector option");
                    }
                }
            }
        }
        World world = args.containsKey("world") ? plugin.getServer().getWorld(args.get("world"))
                : sender instanceof Entity entity ? entity.getWorld() : plugin.getServer().getWorlds().get(0);
        if (world == null) throw new IllegalArgumentException("Unknown world");
        Location origin = sender instanceof Entity entity && entity.getWorld() == world
                ? entity.getLocation().clone() : world.getSpawnLocation();
        if (args.containsKey("x")) origin.setX(number(args.get("x")));
        if (args.containsKey("y")) origin.setY(number(args.get("y")));
        if (args.containsKey("z")) origin.setZ(number(args.get("z")));
        for (String delta : List.of("dx", "dy", "dz")) {
            if (args.containsKey(delta)) number(args.get(delta));
        }
        if (args.containsKey("distance")) checkDistance(args.get("distance"), 0);
        String sort = args.getOrDefault("sort", kind == 'p' ? "nearest" : kind == 'r' ? "random" : "arbitrary");
        if (!Set.of("nearest", "furthest", "random", "arbitrary").contains(sort)) throw new IllegalArgumentException("Invalid sort");
        Integer limit = args.containsKey("limit") ? Integer.valueOf(args.get("limit")) : null;
        if (limit != null && (limit < 1 || limit > maxEntities)) {
            throw new IllegalArgumentException("Selector limit must be 1.." + maxEntities);
        }
        return new Query(kind, world, origin, Map.copyOf(args), sort, limit);
    }

    private List<? extends Entity> candidates(CommandSender sender, Query query, boolean regional) {
        return switch (query.kind()) {
            case 's' -> sender instanceof Entity entity ? List.of(entity) : List.of();
            // Folia's loaded-entity snapshot supplies references only. Mutation and live fields use entity owners.
            case 'e' -> query.world().getEntities();
            default -> regional ? List.copyOf(plugin.getServer().getOnlinePlayers()) : query.world().getPlayers();
        };
    }

    private static Match match(CommandSender sender, Query query, Entity exclude, Entity entity, boolean regional) {
        if (entity == exclude || ((query.kind() == 'p' || query.kind() == 'r') && entity == sender)
                || !entity.isValid() || entity.getWorld() != query.world()) return null;
        var args = query.args();
        for (String key : List.of("type", "name", "tag", "gamemode", "gm")) {
            String raw = args.get(key);
            if (raw == null) continue;
            boolean negate = raw.startsWith("!");
            String value = negate ? raw.substring(1) : raw;
            boolean matched = switch (key) {
                case "type" -> entity.getType().name().equalsIgnoreCase(value.replace("minecraft:", ""));
                case "name" -> entity.getName().equals(value);
                case "tag" -> entity.getScoreboardTags().contains(value);
                default -> entity instanceof Player player && player.getGameMode().name().equalsIgnoreCase(value);
            };
            if (matched == negate) return null;
        }
        Location position = entity.getLocation();
        Location origin = query.origin();
        double distance = position.distanceSquared(origin);
        if (args.containsKey("distance") && !checkDistance(args.get("distance"), Math.sqrt(distance))) return null;
        if (!inAxis(args.get("dx"), origin.getX(), position.getX())
                || !inAxis(args.get("dy"), origin.getY(), position.getY())
                || !inAxis(args.get("dz"), origin.getZ(), position.getZ())) return null;
        return new Match(entity, distance, regional && entity instanceof Player player && hiddenFromOtherViewers(sender, player));
    }

    private static boolean hiddenFromOtherViewers(CommandSender sender, Player player) {
        return sender instanceof Player && sender != player && PlayerNameVisibility.isVanished(player);
    }

    private static boolean visible(CommandSender sender, Match match) {
        if (!(sender instanceof Player viewer) || !(match.entity() instanceof Player player) || viewer == player) return true;
        return (!match.vanished() || viewer.hasPermission("allium.vanish.see")) && viewer.canSee(player);
    }

    private static Resolution finish(Query query, List<Match> matches, int maxEntities, boolean truncate) {
        var sorted = new ArrayList<>(matches);
        switch (query.sort()) {
            case "nearest" -> sorted.sort(Comparator.comparingDouble(Match::distanceSquared));
            case "furthest" -> sorted.sort(Comparator.comparingDouble(Match::distanceSquared).reversed());
            case "random" -> Collections.shuffle(sorted);
            default -> { }
        }
        boolean entitySelector = query.kind() == 'e';
        int total = sorted.size();
        int selected = query.limit() != null ? Math.min(query.limit(), total)
                : query.kind() == 'p' || query.kind() == 'r' ? Math.min(1, total) : total;
        if (!truncate && selected > maxEntities) return new Resolution(List.of(), selected, true, entitySelector);
        return new Resolution(sorted.subList(0, Math.min(maxEntities, selected)).stream().map(Match::entity).toList(),
                selected, true, entitySelector);
    }

    private CompletableFuture<Resolution> collect(CommandSender sender, List<? extends Entity> candidates,
                                                  Function<Entity, Match> read, Function<List<Match>, Resolution> finish) {
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(finish.apply(List.of()));
        var scheduler = plugin.getScheduler();
        List<CompletableFuture<Match>> reads = new ArrayList<>(candidates.size());
        for (Entity entity : candidates) {
            var snapshot = new CompletableFuture<Match>();
            reads.add(snapshot);
            Runnable task = () -> {
                try { snapshot.complete(read.apply(entity)); }
                catch (RuntimeException failure) { snapshot.completeExceptionally(failure); }
            };
            try {
                var owner = BukkitServerThread.entity(plugin, entity);
                if (owner.isServerThread()) task.run();
                else if (scheduler.runOn(owner, task, () -> snapshot.complete(null), 0) == TaskHandle.NONE) snapshot.complete(null);
            } catch (RuntimeException failure) {
                snapshot.completeExceptionally(failure);
            }
        }
        var result = new CompletableFuture<Resolution>();
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            // A retired entity may be the last callback. Defer the issuer phase out of that callback.
            Runnable reply = () -> {
                try {
                    if (error != null) result.completeExceptionally(error);
                    else result.complete(finish.apply(reads.stream().map(reading -> reading.getNow(null))
                            .filter(java.util.Objects::nonNull).toList()));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            };
            try {
                if (BukkitServerThread.runFor(plugin, scheduler, sender, reply, () -> result.cancel(false), 0) == TaskHandle.NONE) {
                    result.cancel(false);
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static boolean checkDistance(String raw, double distance) {
        String[] range = raw.split("\\.\\.", -1);
        if (range.length == 1) return distance == number(range[0]);
        if (range.length != 2) throw new IllegalArgumentException("Invalid distance range");
        double min = range[0].isEmpty() ? Double.NEGATIVE_INFINITY : number(range[0]);
        double max = range[1].isEmpty() ? Double.POSITIVE_INFINITY : number(range[1]);
        return distance >= min && distance <= max;
    }

    private static boolean inAxis(String raw, double start, double position) {
        if (raw == null) return true;
        double end = start + number(raw);
        return position >= Math.min(start, end) && position <= Math.max(start, end) + 1;
    }

    private static double number(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Nonfinite coordinate");
        return number;
    }
}
