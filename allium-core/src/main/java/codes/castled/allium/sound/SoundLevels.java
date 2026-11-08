package codes.castled.allium.sound;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * How loud and at what pitch a message sound plays.
 *
 * <ul>
 *   <li>A message's own volume and pitch (from its lang entry) each fall back
 *       <b>independently</b> to {@code sounds.defaults.volume} and {@code sounds.defaults.pitch}.</li>
 *   <li>A private-message notification multiplies that volume by the recipient's message sound
 *       volume: their {@code /mfx} override, else {@code sounds.messages.fallback-volume}. So a
 *       zero on either side mutes reliably, and authored relative levels are kept.</li>
 *   <li>An authored value that is not usable (non-finite, a negative volume, a pitch outside
 *       {@value #MIN_PITCH}..{@value #MAX_PITCH}) counts as omitted; {@link #problem} names it for a
 *       bounded diagnostic.</li>
 * </ul>
 */
public final class SoundLevels {

    public static final double MIN_PITCH = 0.5D;
    public static final double MAX_PITCH = 2.0D;

    private SoundLevels() {
    }

    /**
     * {@code sounds.defaults.volume}, {@code sounds.defaults.pitch} and
     * {@code sounds.messages.fallback-volume}, validated.
     */
    public record Defaults(double volume, double pitch, double messageFallbackVolume) {
        public Defaults {
            if (!validVolume(volume)) {
                throw new IllegalArgumentException("sounds.defaults.volume must be a finite number of 0 or more, not " + volume);
            }
            if (!validPitch(pitch)) {
                throw new IllegalArgumentException("sounds.defaults.pitch must be from " + MIN_PITCH + " to " + MAX_PITCH + ", not " + pitch);
            }
            if (!Double.isFinite(messageFallbackVolume) || messageFallbackVolume < 0.0D || messageFallbackVolume > 1.0D) {
                throw new IllegalArgumentException("sounds.messages.fallback-volume must be from 0 to 1, not " + messageFallbackVolume);
            }
        }

        public static final Defaults STANDARD = new Defaults(1.0D, 1.0D, 1.0D);
    }

    /** A sound to play; never silent (a silent result is {@link Optional#empty()}). */
    public record Level(float volume, float pitch) {
    }

    /** Any message sound: its own volume and pitch, else the defaults. Empty when the volume is 0. */
    public static Optional<Level> message(@Nullable Double volume, @Nullable Double pitch, Defaults defaults) {
        return level(1.0D, volume, pitch, defaults);
    }

    /**
     * A private-message notification for a recipient whose message sound volume (override or
     * fallback, as {@code EffectivePreferences#messageSoundVolume()}) is {@code recipientVolume}.
     */
    public static Optional<Level> notification(double recipientVolume, @Nullable Double volume, @Nullable Double pitch, Defaults defaults) {
        if (!Double.isFinite(recipientVolume) || recipientVolume < 0.0D || recipientVolume > 1.0D) {
            throw new IllegalArgumentException("recipient volume must be from 0 to 1, not " + recipientVolume);
        }
        return level(recipientVolume, volume, pitch, defaults);
    }

    private static Optional<Level> level(double factor, @Nullable Double volume, @Nullable Double pitch, Defaults defaults) {
        double base = volume != null && validVolume(volume) ? volume : defaults.volume();
        double effective = factor * base;
        if (effective <= 0.0D) {
            return Optional.empty();
        }
        double p = pitch != null && validPitch(pitch) ? pitch : defaults.pitch();
        return Optional.of(new Level((float) effective, (float) p));
    }

    /** Why an authored volume or pitch is ignored, or null when both are usable or omitted. */
    @Nullable
    public static String problem(@Nullable Double volume, @Nullable Double pitch) {
        if (volume != null && !validVolume(volume)) {
            return "volume " + volume + " is not a finite number of 0 or more";
        }
        if (pitch != null && !validPitch(pitch)) {
            return "pitch " + pitch + " is not from " + MIN_PITCH + " to " + MAX_PITCH;
        }
        return null;
    }

    static boolean validVolume(double volume) {
        return Double.isFinite(volume) && volume >= 0.0D;
    }

    static boolean validPitch(double pitch) {
        return Double.isFinite(pitch) && pitch >= MIN_PITCH && pitch <= MAX_PITCH;
    }
}
