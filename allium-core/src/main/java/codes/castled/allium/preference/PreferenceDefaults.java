package codes.castled.allium.preference;

/** Configured defaults for players who never set a preference ({@code preferences.defaults}). */
public record PreferenceDefaults(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                 double messageSoundVolume) {

    public PreferenceDefaults {
        if (!Double.isFinite(messageSoundVolume) || messageSoundVolume < 0.0D || messageSoundVolume > 1.0D) {
            throw new IllegalArgumentException("the default message sound volume must be from 0 to 1");
        }
    }
}
