package codes.castled.allium.sound;

import codes.castled.allium.preference.MessageSoundRules;
import codes.castled.allium.preference.PreferenceDefaults;
import codes.castled.allium.preference.StoredPreferences;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SoundLevelsTest {

    private static final SoundLevels.Defaults DEFAULTS = new SoundLevels.Defaults(0.8D, 1.2D, 0.5D);

    private static PreferenceDefaults prefs(double fallback) {
        return new PreferenceDefaults(true, true, true, fallback);
    }

    private static double recipient(StoredPreferences stored, double fallback) {
        return stored.effective(prefs(fallback)).messageSoundVolume();
    }

    @Test
    void notificationVolumeIsTheRecipientsVolumeTimesTheMessagesVolume() {
        StoredPreferences none = StoredPreferences.empty(UUID.randomUUID());
        // No override: fallback-volume 0.5 x the message's own 0.6.
        assertEquals(0.3F, SoundLevels.notification(recipient(none, 0.5D), 0.6D, null, DEFAULTS).orElseThrow().volume(), 1e-6);
        // An override of 0.25 replaces the fallback; the message's volume still scales it.
        StoredPreferences quiet = none.with(MessageSoundRules.setVolume(none, 0.25D));
        assertEquals(0.15F, SoundLevels.notification(recipient(quiet, 0.5D), 0.6D, null, DEFAULTS).orElseThrow().volume(), 1e-6);
        // Plain message sounds are not scaled by the recipient's preference.
        assertEquals(0.6F, SoundLevels.message(0.6D, null, DEFAULTS).orElseThrow().volume(), 1e-6);
    }

    @Test
    void zeroOnEitherSideMutes() {
        StoredPreferences none = StoredPreferences.empty(UUID.randomUUID());
        StoredPreferences muted = none.with(MessageSoundRules.setVolume(none, 0.0D));
        assertEquals(Optional.empty(), SoundLevels.notification(recipient(muted, 0.5D), 1.0D, null, DEFAULTS), "an override of 0");
        assertEquals(Optional.empty(), SoundLevels.notification(recipient(none, 0.0D), 1.0D, null, DEFAULTS), "a fallback of 0");
        assertEquals(Optional.empty(), SoundLevels.notification(recipient(none, 0.5D), 0.0D, null, DEFAULTS), "a message volume of 0");
        SoundLevels.Defaults silentDefault = new SoundLevels.Defaults(0.0D, 1.0D, 1.0D);
        assertEquals(Optional.empty(), SoundLevels.notification(1.0D, null, null, silentDefault), "an omitted volume with a default of 0");
        assertEquals(Optional.empty(), SoundLevels.message(null, null, silentDefault));
    }

    @Test
    void resetFollowsTheFallbackNowAndAfterAConfigChange() {
        StoredPreferences none = StoredPreferences.empty(UUID.randomUUID());
        StoredPreferences loud = none.with(MessageSoundRules.setVolume(none, 0.9D));
        StoredPreferences reset = loud.with(MessageSoundRules.reset());
        assertNull(reset.messageSoundVolume());
        assertEquals(0.5F, SoundLevels.notification(recipient(reset, 0.5D), 1.0D, null, DEFAULTS).orElseThrow().volume(), 1e-6);
        // The stored row is unchanged; a new fallback in config applies at once.
        assertEquals(0.2F, SoundLevels.notification(recipient(reset, 0.2D), 1.0D, null, DEFAULTS).orElseThrow().volume(), 1e-6);
        assertEquals(Optional.empty(), SoundLevels.notification(recipient(reset, 0.0D), 1.0D, null, DEFAULTS));
    }

    @Test
    void omittedVolumeAndPitchResolveIndependently() {
        SoundLevels.Level both = SoundLevels.message(null, null, DEFAULTS).orElseThrow();
        assertEquals(0.8F, both.volume(), 1e-6);
        assertEquals(1.2F, both.pitch(), 1e-6);
        SoundLevels.Level volumeOnly = SoundLevels.message(0.4D, null, DEFAULTS).orElseThrow();
        assertEquals(0.4F, volumeOnly.volume(), 1e-6);
        assertEquals(1.2F, volumeOnly.pitch(), 1e-6, "an omitted pitch uses sounds.defaults.pitch even when the volume is set");
        SoundLevels.Level pitchOnly = SoundLevels.message(null, 0.7D, DEFAULTS).orElseThrow();
        assertEquals(0.8F, pitchOnly.volume(), 1e-6, "an omitted volume uses sounds.defaults.volume even when the pitch is set");
        assertEquals(0.7F, pitchOnly.pitch(), 1e-6);
        SoundLevels.Level notified = SoundLevels.notification(0.5D, null, null, DEFAULTS).orElseThrow();
        assertEquals(0.4F, notified.volume(), 1e-6);
        assertEquals(1.2F, notified.pitch(), 1e-6);
    }

    @Test
    void unusableAuthoredValuesCountAsOmittedAndAreNamed() {
        SoundLevels.Level level = SoundLevels.message(Double.NaN, 3.0D, DEFAULTS).orElseThrow();
        assertEquals(0.8F, level.volume(), 1e-6);
        assertEquals(1.2F, level.pitch(), 1e-6);
        assertEquals(0.8F, SoundLevels.message(-1.0D, null, DEFAULTS).orElseThrow().volume(), 1e-6);
        assertNotNull(SoundLevels.problem(Double.POSITIVE_INFINITY, null));
        assertNotNull(SoundLevels.problem(null, 0.1D));
        assertNull(SoundLevels.problem(2.5D, 2.0D), "a volume above 1 is a valid authored level");
        assertNull(SoundLevels.problem(null, null));
    }

    @Test
    void configuredDefaultsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new SoundLevels.Defaults(Double.NaN, 1.0D, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new SoundLevels.Defaults(1.0D, 0.1D, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new SoundLevels.Defaults(1.0D, 1.0D, 1.5D));
        assertThrows(IllegalArgumentException.class, () -> new SoundLevels.Defaults(1.0D, 1.0D, -0.1D));
        assertThrows(IllegalArgumentException.class, () -> SoundLevels.notification(Double.NaN, null, null, DEFAULTS));
    }
}
