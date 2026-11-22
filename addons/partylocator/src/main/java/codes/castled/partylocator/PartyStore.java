package codes.castled.partylocator;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Parties survive restarts in {@code parties.yml}: each party's name, leader and members. Legacy
 * kept them in memory only. A save writes a temporary file and moves it over the old one, so a
 * crash mid-write leaves the previous file whole. Invites are not stored; they are short-lived.
 */
final class PartyStore {
    private final Path file;

    PartyStore(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /** The text a save of {@code parties} writes, built on the server thread so the snapshot is consistent. */
    static String snapshot(Collection<Party> parties) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Party party : parties) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", party.name());
            row.put("leader", party.leader().toString());
            row.put("members", party.members().stream().map(UUID::toString).toList());
            rows.add(row);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("parties", rows);
        return yaml.saveToString();
    }

    /** Writes a snapshot; safe off the server thread. */
    void write(String snapshot) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, snapshot, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The saved parties; empty when there is no file. Rows with a malformed UUID are skipped. */
    List<Party> load() throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(file)) return List.of();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        List<Party> parties = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("parties")) {
            try {
                String name = String.valueOf(row.get("name"));
                Party party = new Party(name, UUID.fromString(String.valueOf(row.get("leader"))));
                if (row.get("members") instanceof List<?> members) {
                    for (Object member : members) party.add(UUID.fromString(String.valueOf(member)));
                }
                if (!name.isBlank() && !"null".equals(name)) parties.add(party);
            } catch (IllegalArgumentException malformed) {
                // A hand-edited row with a bad UUID loses that party rather than the whole file.
            }
        }
        return parties;
    }
}
