package codes.castled.allium.creative.manage;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** Who creative flags are read from or written to: a player (by UUID) or a permissions group (by name). */
public record CreativeHolder(Kind kind, String name, @Nullable UUID uuid) {

    public enum Kind { PLAYER, GROUP }

    public CreativeHolder {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        if (kind == Kind.PLAYER) {
            Objects.requireNonNull(uuid, "a player needs a UUID");
        }
    }

    public static CreativeHolder player(UUID uuid, String name) {
        return new CreativeHolder(Kind.PLAYER, name, uuid);
    }

    public static CreativeHolder group(String name) {
        return new CreativeHolder(Kind.GROUP, name, null);
    }

    public boolean isGroup() {
        return kind == Kind.GROUP;
    }

    /** {@code player Steve} or {@code group builder}, for messages and logs. */
    public String describe() {
        return (isGroup() ? "group " : "player ") + name;
    }
}
