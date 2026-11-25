package codes.castled.allium.preference;

/**
 * What a player's preferences are after defaults are applied.
 *
 * @param volumeOverridden whether the volume is the player's own value rather than the fallback
 */
public record EffectivePreferences(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                   double messageSoundVolume, boolean volumeOverridden, boolean socialSpy,
                                   boolean payConfirm, boolean tpAuto, boolean pvpEnabled) {
    public EffectivePreferences(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                double messageSoundVolume, boolean volumeOverridden, boolean socialSpy,
                                boolean payConfirm, boolean tpAuto) {
        this(acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, volumeOverridden, socialSpy,
                payConfirm, tpAuto, true);
    }

    public EffectivePreferences(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                double messageSoundVolume, boolean volumeOverridden, boolean socialSpy) {
        this(acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, volumeOverridden, socialSpy,
                false, false, true);
    }

    public EffectivePreferences(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                double messageSoundVolume, boolean volumeOverridden) {
        this(acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, volumeOverridden, false,
                false, false, true);
    }
}
