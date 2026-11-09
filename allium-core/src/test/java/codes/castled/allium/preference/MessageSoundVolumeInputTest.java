package codes.castled.allium.preference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSoundVolumeInputTest {

    @Test
    void plainDecimalsFromZeroToOneAreAccepted() {
        assertEquals(OptionalDouble.of(0.0D), MessageSoundRules.parseVolume("0"));
        assertEquals(OptionalDouble.of(0.35D), MessageSoundRules.parseVolume("0.35"));
        assertEquals(OptionalDouble.of(0.5D), MessageSoundRules.parseVolume(".5"));
        assertEquals(OptionalDouble.of(1.0D), MessageSoundRules.parseVolume("1"));
        assertEquals(OptionalDouble.of(1.0D), MessageSoundRules.parseVolume(" 1.0 "));
    }

    @Test
    void anythingJavaWouldOtherwiseParseOrOutOfRangeIsRefused() {
        for (String bad : List.of("NaN", "Infinity", "-Infinity", "-0.1", "-0", "1.5", "2", "1e309", "1e-1", "0x1p-1",
                "0.5f", "0.5d", "+0.5", "abc", "", " ", "0.5.5", "1.", "１", "0,5", "50%")) {
            assertTrue(MessageSoundRules.parseVolume(bad).isEmpty(), bad);
        }
        assertTrue(MessageSoundRules.parseVolume(null).isEmpty());
    }

    @Test
    void zeroThroughTheCommandRemembersThePreviousOverrideForToggle() {
        StoredPreferences stored = StoredPreferences.empty(java.util.UUID.randomUUID())
                .with(PreferenceChange.set(PreferenceKey.MESSAGE_SOUND_VOLUME, 0.5D));
        StoredPreferences muted = stored.with(MessageSoundRules.setVolume(stored, MessageSoundRules.parseVolume("0").orElseThrow()));
        assertEquals(0.0D, muted.messageSoundVolume());
        assertEquals(0.5D, muted.messageSoundRestore());
        PreferenceDefaults defaults = new PreferenceDefaults(true, true, true, 1.0D);
        MessageSoundRules.Toggle toggle = MessageSoundRules.toggle(muted, defaults);
        assertEquals(MessageSoundRules.Outcome.RESTORED, toggle.outcome());
        assertEquals(0.5D, muted.with(toggle.change()).effective(defaults).messageSoundVolume());
    }
}
