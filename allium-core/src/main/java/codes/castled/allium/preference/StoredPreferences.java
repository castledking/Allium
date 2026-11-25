package codes.castled.allium.preference;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One player's stored preferences. A {@code null} field is unset: the configured default
 * applies, and changing that default in config changes it for this player too.
 *
 * @param version incremented by every write; 0 means no row exists
 */
public record StoredPreferences(UUID uuid, @Nullable Boolean acceptMessages, @Nullable Boolean acceptPayments,
                                @Nullable Boolean acceptTeleportRequests, @Nullable Double messageSoundVolume,
                                @Nullable Double messageSoundRestore, @Nullable Boolean socialSpy,
                                @Nullable Boolean payConfirm, @Nullable Boolean tpAuto,
                                @Nullable Boolean pvpEnabled, long version) {

    public StoredPreferences {
        Objects.requireNonNull(uuid, "uuid");
    }

    /** Compatibility constructor for the pre-pvp row. */
    public StoredPreferences(UUID uuid, @Nullable Boolean acceptMessages, @Nullable Boolean acceptPayments,
                             @Nullable Boolean acceptTeleportRequests, @Nullable Double messageSoundVolume,
                             @Nullable Double messageSoundRestore, @Nullable Boolean socialSpy,
                             @Nullable Boolean payConfirm, @Nullable Boolean tpAuto, long version) {
        this(uuid, acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, messageSoundRestore,
                socialSpy, payConfirm, tpAuto, null, version);
    }

    /** Compatibility constructor for the pre-pay-confirm/tp-auto row. */
    public StoredPreferences(UUID uuid, @Nullable Boolean acceptMessages, @Nullable Boolean acceptPayments,
                             @Nullable Boolean acceptTeleportRequests, @Nullable Double messageSoundVolume,
                             @Nullable Double messageSoundRestore, @Nullable Boolean socialSpy, long version) {
        this(uuid, acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, messageSoundRestore,
                socialSpy, null, null, null, version);
    }

    /** Compatibility constructor for the pre-social-spy row. */
    public StoredPreferences(UUID uuid, Boolean acceptMessages, Boolean acceptPayments, Boolean acceptTeleportRequests,
                             Double messageSoundVolume, Double messageSoundRestore, long version) {
        this(uuid, acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, messageSoundRestore,
                null, null, null, null, version);
    }

    public static StoredPreferences empty(UUID uuid) {
        return new StoredPreferences(uuid, null, null, null, null, null, null, null, null, null, 0L);
    }

    @Nullable
    public Object get(PreferenceKey key) {
        return switch (key) {
            case SOCIAL_SPY -> socialSpy;
            case ACCEPT_MESSAGES -> acceptMessages;
            case ACCEPT_PAYMENTS -> acceptPayments;
            case ACCEPT_TELEPORT_REQUESTS -> acceptTeleportRequests;
            case PAY_CONFIRM -> payConfirm;
            case TP_AUTO -> tpAuto;
            case PVP_ENABLED -> pvpEnabled;
            case MESSAGE_SOUND_VOLUME -> messageSoundVolume;
            case MESSAGE_SOUND_RESTORE -> messageSoundRestore;
        };
    }

    /** This state with a patch applied locally (the version is unchanged). */
    public StoredPreferences with(PreferenceChange change) {
        Boolean spy = socialSpy;
        Boolean messages = acceptMessages;
        Boolean payments = acceptPayments;
        Boolean teleports = acceptTeleportRequests;
        Boolean confirm = payConfirm;
        Boolean autoTp = tpAuto;
        Boolean pvp = pvpEnabled;
        Double volume = messageSoundVolume;
        Double restore = messageSoundRestore;
        for (Map.Entry<PreferenceKey, Optional<Object>> e : change.entries().entrySet()) {
            Object value = e.getValue().orElse(null);
            switch (e.getKey()) {
                case SOCIAL_SPY -> spy = (Boolean) value;
                case ACCEPT_MESSAGES -> messages = (Boolean) value;
                case ACCEPT_PAYMENTS -> payments = (Boolean) value;
                case ACCEPT_TELEPORT_REQUESTS -> teleports = (Boolean) value;
                case PAY_CONFIRM -> confirm = (Boolean) value;
                case TP_AUTO -> autoTp = (Boolean) value;
                case PVP_ENABLED -> pvp = (Boolean) value;
                case MESSAGE_SOUND_VOLUME -> volume = (Double) value;
                case MESSAGE_SOUND_RESTORE -> restore = (Double) value;
            }
        }
        return new StoredPreferences(uuid, messages, payments, teleports, volume, restore, spy, confirm, autoTp, pvp, version);
    }

    public EffectivePreferences effective(PreferenceDefaults defaults) {
        double volume = messageSoundVolume != null ? messageSoundVolume : defaults.messageSoundVolume();
        return new EffectivePreferences(
                acceptMessages != null ? acceptMessages : defaults.acceptMessages(),
                acceptPayments != null ? acceptPayments : defaults.acceptPayments(),
                acceptTeleportRequests != null ? acceptTeleportRequests : defaults.acceptTeleportRequests(),
                volume, messageSoundVolume != null, Boolean.TRUE.equals(socialSpy),
                Boolean.TRUE.equals(payConfirm), Boolean.TRUE.equals(tpAuto),
                pvpEnabled != null ? pvpEnabled : defaults.pvpEnabled());
    }
}
