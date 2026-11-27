package codes.castled.allium.portable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Own-home commands shared by the native servers and the legacy Bukkit plugin. */
public final class Homes {
    public static final int DEFAULT_LIMIT = 1;
    public static final int MAX_LIMIT = 1000;
    public static final String PLAYERS_ONLY = "Only players can use home commands.";

    public enum Action {
        SET("sethome", "createhome"),
        VISIT("home"),
        DELETE("delhome", "remhome", "rmhome"),
        LIST("homes");

        public final String command;
        private final String[] aliases;

        Action(String command, String... aliases) {
            this.command = command;
            this.aliases = aliases;
        }

        public String[] labels() {
            String[] labels = new String[aliases.length + 1];
            labels[0] = command;
            System.arraycopy(aliases, 0, labels, 1, aliases.length);
            return labels;
        }

        public boolean acceptsName() {
            return this != LIST;
        }

        public boolean suggestsNames() {
            return this == VISIT || this == DELETE;
        }
    }

    public static final class Position {
        public final String world;
        public final double x, y, z;
        public final float yaw, pitch;

        public Position(String world, double x, double y, double z, float yaw, float pitch) {
            if (world.isEmpty() || world.indexOf('\t') >= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Invalid home position");
            }
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private String encode() {
            return String.join("\t", world, Double.toString(x), Double.toString(y), Double.toString(z),
                    Float.toString(yaw), Float.toString(pitch));
        }

        private static Position decode(String raw) {
            String[] fields = raw.split("\t", -1);
            if (fields.length != 6) throw new IllegalArgumentException("Invalid home data");
            return new Position(fields[0], Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3]), Float.parseFloat(fields[4]), Float.parseFloat(fields[5]));
        }
    }

    private final Path file;
    private final int limit;
    private final Supplier<Position> current;
    private final Predicate<Position> teleport;

    public Homes(Path directory, UUID owner, int limit, Supplier<Position> current, Predicate<Position> teleport) {
        this.file = directory.resolve(owner + ".properties");
        this.limit = Math.max(0, Math.min(MAX_LIMIT, limit));
        this.current = current;
        this.teleport = teleport;
    }

    public int execute(Action action, String raw, Consumer<String> reply) {
        String name = normalize(raw);
        if (action.acceptsName() && name.isEmpty()) {
            return fail(reply, "Use 1–32 letters, digits, underscores or hyphens for a home name.");
        }
        if (action.acceptsName() && name.equals("bed")) {
            return fail(reply, "The name 'bed' is reserved.");
        }
        try {
            Properties homes = read();
            switch (action) {
                case SET:
                    if (homes.containsKey(name)) return fail(reply, "Home '" + name + "' already exists.");
                    if (homes.size() >= limit) return fail(reply, "You have reached your home limit (" + limit + ").");
                    homes.setProperty(name, current.get().encode());
                    save(homes);
                    reply.accept("Home '" + name + "' set.");
                    return 1;
                case VISIT:
                    String saved = homes.getProperty(name);
                    if (saved == null) return fail(reply, "Home '" + name + "' was not found.");
                    if (!teleport.test(Position.decode(saved))) {
                        return fail(reply, "Could not teleport to home '" + name + "'. The destination may be unavailable.");
                    }
                    reply.accept("Teleported to home '" + name + "'.");
                    return 1;
                case DELETE:
                    if (homes.remove(name) == null) return fail(reply, "Home '" + name + "' was not found.");
                    save(homes);
                    reply.accept("Home '" + name + "' deleted.");
                    return 1;
                case LIST:
                    reply.accept(homes.isEmpty() ? "You have no homes." : "Homes: " + String.join(", ", names(homes, "")));
                    return 1;
                default:
                    throw new AssertionError(action);
            }
        } catch (IOException | IllegalArgumentException failure) {
            return fail(reply, "Your home data could not be read or saved.");
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return "home";
        return raw.matches("[A-Za-z0-9_-]{1,32}") ? raw.toLowerCase(Locale.ROOT) : "";
    }

    public List<String> suggestions(String prefix) {
        try {
            return names(read(), prefix.toLowerCase(Locale.ROOT));
        } catch (IOException | IllegalArgumentException failure) {
            return Collections.emptyList();
        }
    }

    public int rename(String oldRaw, String newRaw, Consumer<String> reply) {
        String oldName = normalize(oldRaw);
        String newName = normalize(newRaw);
        if (oldName.isEmpty() || newName.isEmpty()) {
            return fail(reply, "Use 1–32 letters, digits, underscores or hyphens for a home name.");
        }
        if (newName.equals("bed")) {
            return fail(reply, "The name 'bed' is reserved.");
        }
        try {
            Properties homes = read();
            String saved = homes.getProperty(oldName);
            if (saved == null) return fail(reply, "Home '" + oldName + "' was not found.");
            if (homes.containsKey(newName)) return fail(reply, "Home '" + newName + "' already exists.");
            homes.remove(oldName);
            homes.setProperty(newName, saved);
            save(homes);
            reply.accept("Renamed home '" + oldName + "' to '" + newName + "'.");
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            return fail(reply, "Your home data could not be read or saved.");
        }
    }

    private static List<String> names(Properties homes, String prefix) {
        List<String> names = new ArrayList<>();
        for (String name : homes.stringPropertyNames()) {
            if (name.startsWith(prefix)) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    private Properties read() throws IOException {
        Properties homes = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            homes.load(reader);
        } catch (NoSuchFileException missing) {
            return homes;
        }
        return homes;
    }

    private void save(Properties homes) throws IOException {
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "homes-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                homes.store(writer, null);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int fail(Consumer<String> reply, String message) {
        reply.accept(message);
        return 0;
    }
}
