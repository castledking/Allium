package codes.castled.allium.message;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable sound definition attached to a locale message entry.
 */
public record MessageSoundSpec(
        String soundId,
        @Nullable Double volume,
        @Nullable Double pitch
) {
    public MessageSoundSpec {
        if (soundId == null || soundId.isBlank()) {
            throw new IllegalArgumentException("soundId must not be null or blank");
        }
        soundId = soundId.trim();
    }
}
