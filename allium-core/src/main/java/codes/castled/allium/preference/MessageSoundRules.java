package codes.castled.allium.preference;

/**
 * The message-sound volume rules behind {@code /mfx volume|toggle|reset} (commands arrive in a
 * later slice; the stored state and its restoration live here so it survives reconnects).
 */
public final class MessageSoundRules {

    private MessageSoundRules() {
    }

    /** What a toggle did, so the command can say so. */
    public enum Outcome {
        /** An audible volume went to 0. */
        MUTED,
        /** From 0, the remembered positive override came back. */
        RESTORED,
        /** From 0 with nothing remembered, the override was removed and the configured volume applies. */
        FALLBACK,
        /** As FALLBACK, but the configured volume is 0, so sounds stay muted. */
        FALLBACK_SILENT
    }

    /** A toggle result: the patch, the effective volume afterwards and what happened. */
    public record Toggle(PreferenceChange change, double volume, Outcome outcome) {
        /** Unmuting found nothing audible to return to (the configured fallback is 0). */
        public boolean stillMuted() {
            return outcome == Outcome.FALLBACK_SILENT;
        }
    }

    /** {@code /mfx volume <v>}: stores the override; muting with 0 remembers the previous positive override. */
    public static PreferenceChange setVolume(StoredPreferences stored, double volume) {
        double v = (Double) PreferenceKey.MESSAGE_SOUND_VOLUME.validate(volume);
        if (v > 0.0D) {
            return PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, v).andUnset(PreferenceKey.MESSAGE_SOUND_RESTORE);
        }
        PreferenceChange change = PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.0D);
        Double previous = stored.messageSoundVolume();
        return previous != null && previous > 0.0D ? change.andSet(PreferenceKey.MESSAGE_SOUND_RESTORE, previous) : change;
    }

    /**
     * {@code /mfx toggle}: an audible volume goes to 0 (remembering a positive override); from 0 it
     * restores the remembered override, or the configured fallback when there is none.
     */
    public static Toggle toggle(StoredPreferences stored, PreferenceDefaults defaults) {
        double current = stored.effective(defaults).messageSoundVolume();
        if (current > 0.0D) {
            PreferenceChange change = PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.0D);
            Double override = stored.messageSoundVolume();
            change = override != null && override > 0.0D
                    ? change.andSet(PreferenceKey.MESSAGE_SOUND_RESTORE, override)
                    : change.andUnset(PreferenceKey.MESSAGE_SOUND_RESTORE);
            return new Toggle(change, 0.0D, Outcome.MUTED);
        }
        Double restore = stored.messageSoundRestore();
        if (restore != null && restore > 0.0D) {
            return new Toggle(PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, restore)
                    .andUnset(PreferenceKey.MESSAGE_SOUND_RESTORE), restore, Outcome.RESTORED);
        }
        PreferenceChange change = PreferenceChange.unset(PreferenceKey.MESSAGE_SOUND_VOLUME).andUnset(PreferenceKey.MESSAGE_SOUND_RESTORE);
        return new Toggle(change, defaults.messageSoundVolume(),
                defaults.messageSoundVolume() > 0.0D ? Outcome.FALLBACK : Outcome.FALLBACK_SILENT);
    }

    /** {@code /mfx reset}: removes the override and the restoration state. */
    public static PreferenceChange reset() {
        return PreferenceChange.unset(PreferenceKey.MESSAGE_SOUND_VOLUME).andUnset(PreferenceKey.MESSAGE_SOUND_RESTORE);
    }
}
