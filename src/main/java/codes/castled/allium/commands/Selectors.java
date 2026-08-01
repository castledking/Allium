package codes.castled.allium.commands;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.lang.Lang;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Single entry point for Minecraft-style entity selectors.
 *
 * <p>Supports {@code @p}, {@code @r}, {@code @a}, {@code @e} and {@code @s} with a vanilla-style
 * argument block, e.g. {@code @e[type=villager,distance=0..50,limit=5,sort=nearest]}.
 *
 * <p>Supported arguments: {@code type}, {@code name}, {@code tag}, {@code gamemode} (alias
 * {@code gm}), {@code distance}, {@code limit}, {@code sort}, {@code world}, {@code x}/{@code y}/
 * {@code z} (origin override) and {@code dx}/{@code dy}/{@code dz} (box volume). {@code type},
 * {@code name}, {@code tag} and {@code gamemode} accept a leading {@code !} for negation.
 */
public class Selectors {

    /** Selector heads this parser understands, in the order they are offered for completion. */
    private static final List<String> BASE_SELECTORS = List.of("@p", "@r", "@a", "@e", "@s");

    /** Argument names offered inside the bracket block. */
    private static final List<String> ARGUMENT_KEYS = List.of(
        "type", "distance", "limit", "sort", "name", "tag", "gamemode", "world",
        "x", "y", "z", "dx", "dy", "dz"
    );

    private static final List<String> SORT_VALUES = List.of("nearest", "furthest", "random", "arbitrary");

    public static final String PERMISSION_ALL = "allium.command.selector.all";
    public static final String PERMISSION_ENTITIES = "allium.command.selector.entities";

    private final Lang lang;
    private final PluginStart plugin;

    public Selectors(Lang lang, PluginStart plugin) {
        this.lang = lang;
        this.plugin = plugin;
    }

    /**
     * Outcome of resolving a selector. Carries a failure reason so callers can send a precise
     * message instead of guessing why an empty list came back.
     */
    public static final class Result {
        /** Why a selector produced no targets. */
        public enum Failure { NONE, MALFORMED, NO_PERMISSION, NO_MATCHES }

        private final List<Entity> entities;
        private final Failure failure;
        private final String detail;

        private Result(List<Entity> entities, Failure failure, String detail) {
            this.entities = entities;
            this.failure = failure;
            this.detail = detail;
        }

        static Result of(List<Entity> entities) {
            return entities.isEmpty()
                ? new Result(List.of(), Failure.NO_MATCHES, null)
                : new Result(List.copyOf(entities), Failure.NONE, null);
        }

        static Result malformed(String detail) {
            return new Result(List.of(), Failure.MALFORMED, detail);
        }

        static Result noPermission(String detail) {
            return new Result(List.of(), Failure.NO_PERMISSION, detail);
        }

        public List<Entity> entities() {
            return entities;
        }

        public boolean isSuccess() {
            return failure == Failure.NONE;
        }

        public Failure failure() {
            return failure;
        }

        /** The offending selector fragment or the permission that was missing; may be null. */
        public String detail() {
            return detail;
        }

        /** Only the players among the matched entities. */
        public List<Player> players() {
            return entities.stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .collect(Collectors.toList());
        }

        /** Everything except players — what /tpmob and friends operate on. */
        public List<Entity> nonPlayers() {
            return entities.stream()
                .filter(entity -> !(entity instanceof Player))
                .collect(Collectors.toList());
        }
    }

    /** True when the token looks like a selector rather than a player name. */
    public static boolean isSelector(String token) {
        return token != null && token.startsWith("@");
    }

    /**
     * Resolves a selector, or a bare player name, into matching entities.
     *
     * @param token    the selector or player name
     * @param executor the command sender; may be console, in which case position-relative
     *                 arguments resolve against the first world's spawn
     */
    public Result select(String token, CommandSender executor) {
        if (token == null || token.isEmpty()) {
            return Result.malformed(token);
        }

        if (!isSelector(token)) {
            Player named = Bukkit.getPlayerExact(token);
            return named != null ? Result.of(List.of(named)) : Result.of(List.of());
        }

        Player executorPlayer = executor instanceof Player p ? p : null;
        String body = token.substring(1);

        int bracketStart = body.indexOf('[');
        String head;
        Map<String, String> arguments = new LinkedHashMap<>();

        if (bracketStart == -1) {
            head = body;
        } else {
            int bracketEnd = body.lastIndexOf(']');
            if (bracketEnd < bracketStart) {
                return Result.malformed(token);
            }
            head = body.substring(0, bracketStart);
            if (!parseArguments(body.substring(bracketStart + 1, bracketEnd), arguments)) {
                return Result.malformed(token);
            }
        }

        Location origin = resolveOrigin(executorPlayer, arguments);
        if (origin == null) {
            return Result.malformed(token);
        }
        World world = resolveWorld(origin, arguments);
        if (world == null) {
            return Result.malformed("world=" + arguments.get("world"));
        }

        List<Entity> candidates;
        switch (head) {
            case "s" -> {
                if (!(executor instanceof Entity self)) {
                    return Result.malformed("@s");
                }
                candidates = new ArrayList<>(List.of(self));
            }
            case "p", "r", "a" -> candidates = new ArrayList<>(world.getPlayers());
            case "e" -> {
                if (!hasPermission(executor, PERMISSION_ENTITIES)) {
                    return Result.noPermission(PERMISSION_ENTITIES);
                }
                candidates = new ArrayList<>(world.getEntities());
            }
            default -> {
                return Result.malformed("@" + head);
            }
        }

        if (head.equals("a") && !hasPermission(executor, PERMISSION_ALL)) {
            return Result.noPermission(PERMISSION_ALL);
        }

        candidates.removeIf(entity -> !matches(entity, arguments, origin));

        // @p and @r never target the executor; @a and @e do, matching vanilla's "other players" feel
        // that the rest of this plugin already assumes.
        if (executorPlayer != null && (head.equals("p") || head.equals("r"))) {
            candidates.remove(executorPlayer);
        }

        applyOrdering(candidates, head, arguments, origin);
        applyLimit(candidates, head, arguments);

        return Result.of(candidates);
    }

    /**
     * Backwards-compatible entry point: returns matches and swallows the failure reason.
     *
     * @deprecated prefer {@link #select(String, CommandSender)} so the caller can report why a
     *             selector matched nothing.
     */
    @Deprecated
    public List<Entity> parseSelector(String selector, Player executor) {
        return select(selector, executor).entities();
    }

    /** Parses {@code key=value,key=value} into the given map. Returns false on malformed input. */
    private boolean parseArguments(String block, Map<String, String> out) {
        if (block.isBlank()) {
            return true;
        }
        for (String pair : block.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                return false;
            }
            out.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim());
        }
        return true;
    }

    private Location resolveOrigin(Player executor, Map<String, String> arguments) {
        Location base = executor != null
            ? executor.getLocation().clone()
            : Bukkit.getWorlds().get(0).getSpawnLocation().clone();

        try {
            if (arguments.containsKey("x")) base.setX(Double.parseDouble(arguments.get("x")));
            if (arguments.containsKey("y")) base.setY(Double.parseDouble(arguments.get("y")));
            if (arguments.containsKey("z")) base.setZ(Double.parseDouble(arguments.get("z")));
        } catch (NumberFormatException ex) {
            return null;
        }
        return base;
    }

    private World resolveWorld(Location origin, Map<String, String> arguments) {
        String requested = arguments.get("world");
        if (requested == null) {
            return origin.getWorld();
        }
        World world = Bukkit.getWorld(requested);
        if (world != null) {
            origin.setWorld(world);
        }
        return world;
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender == null || sender.hasPermission(permission);
    }

    private boolean matches(Entity entity, Map<String, String> arguments, Location origin) {
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            String key = entry.getKey();
            String raw = entry.getValue();

            // Origin, volume, ordering and limit arguments are not per-entity predicates.
            switch (key) {
                case "x", "y", "z", "limit", "sort", "world" -> {
                    continue;
                }
                default -> { /* fall through to the predicates below */ }
            }

            boolean negated = raw.startsWith("!");
            String value = negated ? raw.substring(1) : raw;
            boolean matched;

            switch (key) {
                case "type" -> matched = matchesType(entity, value);
                case "name" -> matched = value.equalsIgnoreCase(displayName(entity));
                case "tag" -> matched = entity.getScoreboardTags().contains(value);
                case "gamemode", "gm" -> matched = entity instanceof Player player
                    && player.getGameMode() == parseGameMode(value);
                case "distance" -> matched = matchesDistance(entity.getLocation(), origin, value);
                case "dx", "dy", "dz" -> matched = matchesVolume(entity.getLocation(), origin, arguments);
                default -> matched = true; // unknown arguments are ignored rather than fatal
            }

            if (matched == negated) {
                return false;
            }
        }
        return true;
    }

    private String displayName(Entity entity) {
        if (entity instanceof Player player) {
            return player.getName();
        }
        String custom = entity.getCustomName();
        return custom != null ? custom : entity.getName();
    }

    private GameMode parseGameMode(String value) {
        try {
            return GameMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean matchesType(Entity entity, String typeValue) {
        String normalized = typeValue.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (normalized.equals("player")) {
            return entity instanceof Player;
        }
        try {
            return entity.getType() == EntityType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Handles {@code n}, {@code ..max}, {@code min..} and {@code min..max}. */
    private boolean matchesDistance(Location entityLocation, Location origin, String range) {
        if (origin.getWorld() == null || !origin.getWorld().equals(entityLocation.getWorld())) {
            return false;
        }
        double distance = entityLocation.distance(origin);
        try {
            int separator = range.indexOf("..");
            if (separator == -1) {
                return Math.abs(distance - Double.parseDouble(range)) < 0.5;
            }
            String min = range.substring(0, separator);
            String max = range.substring(separator + 2);
            if (!min.isEmpty() && distance < Double.parseDouble(min)) {
                return false;
            }
            return max.isEmpty() || distance <= Double.parseDouble(max);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Vanilla {@code dx/dy/dz} box test, anchored at the origin. */
    private boolean matchesVolume(Location entityLocation, Location origin, Map<String, String> arguments) {
        if (origin.getWorld() == null || !origin.getWorld().equals(entityLocation.getWorld())) {
            return false;
        }
        try {
            return withinAxis(entityLocation.getX(), origin.getX(), arguments.get("dx"))
                && withinAxis(entityLocation.getY(), origin.getY(), arguments.get("dy"))
                && withinAxis(entityLocation.getZ(), origin.getZ(), arguments.get("dz"));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private boolean withinAxis(double value, double originValue, String delta) {
        if (delta == null) {
            return true;
        }
        double size = Double.parseDouble(delta);
        double min = Math.min(originValue, originValue + size);
        double max = Math.max(originValue, originValue + size);
        return value >= min && value <= max;
    }

    private void applyOrdering(List<Entity> candidates, String head, Map<String, String> arguments, Location origin) {
        String sort = arguments.get("sort");
        if (sort == null) {
            // Vanilla defaults: @p is nearest-first, @r is shuffled, everything else is arbitrary.
            sort = switch (head) {
                case "p" -> "nearest";
                case "r" -> "random";
                default -> "arbitrary";
            };
        }

        switch (sort.toLowerCase(Locale.ROOT)) {
            case "nearest" -> candidates.sort(Comparator.comparingDouble(e -> distanceSquared(e, origin)));
            case "furthest" -> candidates.sort(Comparator.comparingDouble((Entity e) -> distanceSquared(e, origin)).reversed());
            case "random" -> Collections.shuffle(candidates);
            default -> { /* arbitrary: leave as-is */ }
        }
    }

    private double distanceSquared(Entity entity, Location origin) {
        if (origin.getWorld() == null || !origin.getWorld().equals(entity.getWorld())) {
            return Double.MAX_VALUE;
        }
        return entity.getLocation().distanceSquared(origin);
    }

    private void applyLimit(List<Entity> candidates, String head, Map<String, String> arguments) {
        int limit;
        String requested = arguments.get("limit");
        if (requested != null) {
            try {
                limit = Integer.parseInt(requested);
            } catch (NumberFormatException ex) {
                return;
            }
        } else {
            // @p and @r are single-target by definition; @a and @e are unbounded.
            limit = head.equals("p") || head.equals("r") || head.equals("s") ? 1 : Integer.MAX_VALUE;
        }

        if (limit >= 0 && candidates.size() > limit) {
            candidates.subList(limit, candidates.size()).clear();
        }
    }

    /**
     * Completes a partially typed selector token. Suggestions are whole tokens, since Bukkit
     * replaces the entire argument.
     *
     * @param sender  used to hide selector heads the sender may not use
     * @param token   the argument typed so far
     * @return matching completions, empty if the token is not selector-like
     */
    public static List<String> tabComplete(CommandSender sender, String token) {
        if (token == null || !token.startsWith("@")) {
            return List.of();
        }

        int bracketStart = token.indexOf('[');
        if (bracketStart == -1) {
            List<String> heads = new ArrayList<>();
            for (String head : BASE_SELECTORS) {
                if (!head.startsWith(token)) {
                    continue;
                }
                if (head.equals("@a") && !sender.hasPermission(PERMISSION_ALL)) continue;
                if (head.equals("@e") && !sender.hasPermission(PERMISSION_ENTITIES)) continue;
                heads.add(head);
                // Offer the argument block once the head itself is fully typed.
                if (head.equals(token)) {
                    heads.add(head + "[");
                }
            }
            return heads;
        }

        if (token.endsWith("]")) {
            return List.of();
        }

        // Everything up to and including the last separator is fixed; only the tail is completed.
        int lastSeparator = Math.max(bracketStart, token.lastIndexOf(','));
        String prefix = token.substring(0, lastSeparator + 1);
        String tail = token.substring(lastSeparator + 1);

        int equals = tail.indexOf('=');
        if (equals == -1) {
            return ARGUMENT_KEYS.stream()
                .filter(key -> key.startsWith(tail.toLowerCase(Locale.ROOT)))
                .map(key -> prefix + key + "=")
                .collect(Collectors.toList());
        }

        String key = tail.substring(0, equals).toLowerCase(Locale.ROOT);
        String partialValue = tail.substring(equals + 1);
        String negation = partialValue.startsWith("!") ? "!" : "";
        String bareValue = partialValue.substring(negation.length()).toLowerCase(Locale.ROOT);
        String valuePrefix = prefix + key + "=" + negation;

        return valuesFor(key, bareValue).stream()
            .map(value -> valuePrefix + value)
            .limit(64)
            .collect(Collectors.toList());
    }

    private static List<String> valuesFor(String key, String partial) {
        return switch (key) {
            case "type" -> {
                List<String> types = Arrays.stream(EntityType.values())
                    .map(type -> type.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(partial))
                    .collect(Collectors.toList());
                if ("player".startsWith(partial)) {
                    types.add(0, "player");
                }
                yield types;
            }
            case "sort" -> SORT_VALUES.stream().filter(v -> v.startsWith(partial)).collect(Collectors.toList());
            case "gamemode", "gm" -> Arrays.stream(GameMode.values())
                .map(mode -> mode.name().toLowerCase(Locale.ROOT))
                .filter(name -> name.startsWith(partial))
                .collect(Collectors.toList());
            case "world" -> Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .collect(Collectors.toList());
            case "name" -> Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .collect(Collectors.toList());
            case "distance" -> partial.isEmpty() ? List.of("0..10", "0..50", "..16") : List.of();
            case "limit" -> partial.isEmpty() ? List.of("1", "5", "10") : List.of();
            default -> List.of();
        };
    }
}
