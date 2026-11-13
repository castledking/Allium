package codes.castled.allium.mail;

import org.bukkit.Server;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One guarded native accessor: Bukkit's dimension folder is not the player-data directory. */
final class PlayerDataFiles {
    private PlayerDataFiles() {}

    static Path directory(Server server) {
        return guardedDirectory(server);
    }

    /** Uses CraftBukkit's own PlayerDataStorage#getPlayerDir, including relocated 26.x saves. */
    static Path guardedDirectory(Object craftServer) {
        try {
            Object players = craftServer.getClass().getMethod("getHandle").invoke(craftServer);
            List<Field> candidates = new ArrayList<>();
            for (Class<?> type = players.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        if (field.getType().getMethod("getPlayerDir").getReturnType() == File.class) candidates.add(field);
                    } catch (NoSuchMethodException ignored) { /* a different PlayerList field */ }
                }
            }
            if (candidates.size() != 1) throw new IllegalStateException("expected exactly one player storage field, found " + candidates.size());
            Field field = candidates.get(0);
            if (!field.trySetAccessible()) throw new IllegalStateException("player storage field is inaccessible: " + field);
            Object storage = field.get(players);
            if (storage == null) throw new IllegalStateException("native player storage is not initialized");
            File directory = (File) field.getType().getMethod("getPlayerDir").invoke(storage);
            if (directory == null) throw new IllegalStateException("native player-data directory is missing");
            return directory.toPath().toAbsolutePath().normalize();
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Mail cannot verify saved receipts: native player-data directory unavailable on "
                    + craftServer.getClass().getName() + ". Mail is disabled rather than guessing a save path.", error);
        }
    }
}
