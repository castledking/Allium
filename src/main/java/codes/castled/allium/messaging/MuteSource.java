package codes.castled.allium.messaging;

import java.util.UUID;

/** Whether moderation has muted a player. Moderation plugs in later; the default mutes no one. */
@FunctionalInterface
public interface MuteSource {

    MuteSource NONE = player -> false;

    boolean isMuted(UUID player);
}
