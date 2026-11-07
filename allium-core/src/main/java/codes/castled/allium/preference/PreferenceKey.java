package codes.castled.allium.preference;

import java.util.Locale;
import java.util.Optional;

/** A stored per-player preference; unset means the configured default applies. */
public enum PreferenceKey {

    ACCEPT_MESSAGES("accept-messages", "accept_messages", Kind.BOOLEAN),
    ACCEPT_PAYMENTS("accept-payments", "accept_payments", Kind.BOOLEAN),
    ACCEPT_TELEPORT_REQUESTS("accept-teleport-requests", "accept_teleport_requests", Kind.BOOLEAN),
    /** Message sound volume override in [0, 1]; unset follows the configured fallback. */
    MESSAGE_SOUND_VOLUME("message-sound-volume", "message_sound_volume", Kind.VOLUME),
    /**
     * Restoration state for {@code /mfx toggle}: the last positive override to restore when
     * unmuting; unset restores the configured fallback.
     */
    MESSAGE_SOUND_RESTORE("message-sound-restore", "message_sound_restore", Kind.VOLUME);

    public enum Kind {
        BOOLEAN,
        VOLUME
    }

    private final String configName;
    private final String column;
    private final Kind kind;

    PreferenceKey(String configName, String column, Kind kind) {
        this.configName = configName;
        this.column = column;
        this.kind = kind;
    }

    public String configName() {
        return configName;
    }

    public String column() {
        return column;
    }

    public Kind kind() {
        return kind;
    }

    public static Optional<PreferenceKey> fromName(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (PreferenceKey key : values()) {
            if (key.configName.equals(normalized)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** @throws IllegalArgumentException for a value this key cannot hold */
    public Object validate(Object value) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException(configName + " is true or false");
                }
                yield value;
            }
            case VOLUME -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException(configName + " is a number from 0 to 1");
                }
                double v = number.doubleValue();
                if (!Double.isFinite(v) || v < 0.0D || v > 1.0D) {
                    throw new IllegalArgumentException(configName + " must be a finite number from 0 to 1, not " + v);
                }
                yield v;
            }
        };
    }
}
