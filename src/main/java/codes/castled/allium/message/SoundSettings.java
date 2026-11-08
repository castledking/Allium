package codes.castled.allium.message;

import codes.castled.allium.sound.SoundLevels;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** Reads {@code sounds.defaults.*} and {@code sounds.messages.fallback-volume}; an invalid value is reported and replaced by 1.0. */
public final class SoundSettings {

    private SoundSettings() {
    }

    public static SoundLevels.Defaults read(@Nullable ConfigurationSection root, Consumer<String> warn) {
        double volume = value(root, "sounds.defaults.volume", v -> Double.isFinite(v) && v >= 0.0D, "a finite number of 0 or more", warn);
        double pitch = value(root, "sounds.defaults.pitch", v -> Double.isFinite(v) && v >= SoundLevels.MIN_PITCH && v <= SoundLevels.MAX_PITCH,
                "from " + SoundLevels.MIN_PITCH + " to " + SoundLevels.MAX_PITCH, warn);
        double fallback = value(root, "sounds.messages.fallback-volume", v -> Double.isFinite(v) && v >= 0.0D && v <= 1.0D, "from 0 to 1", warn);
        return new SoundLevels.Defaults(volume, pitch, fallback);
    }

    private static double value(@Nullable ConfigurationSection root, String path, java.util.function.DoublePredicate valid,
                                String rule, Consumer<String> warn) {
        if (root == null || !root.contains(path)) {
            return 1.0D;
        }
        double value = root.getDouble(path, Double.NaN);
        if (!valid.test(value)) {
            warn.accept(path + " must be " + rule + " (found '" + root.get(path) + "'); using 1.0.");
            return 1.0D;
        }
        return value;
    }
}
