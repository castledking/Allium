package codes.castled.allium.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Identifies the server that owns the native player data used by recovery. */
public final class ServerIdentity {
    private ServerIdentity() {}

    public static String load(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(".server-id");
        if (!Files.exists(file)) {
            Files.createDirectories(dataDirectory);
            String id = "server-" + UUID.randomUUID();
            try {
                Files.writeString(file, id + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return id;
            } catch (FileAlreadyExistsException alreadyCreated) {
                // Another module created the durable identity first.
            }
        }
        String id = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (!id.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new IOException("invalid " + file + "; restore the original server ID to preserve recovery ownership");
        }
        return id;
    }
}
