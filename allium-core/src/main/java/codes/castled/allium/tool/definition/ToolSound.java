package codes.castled.allium.tool.definition;

import java.util.Objects;

/**
 * A sound a tool plays in the world, such as the Mob Disarmer's shield break at the mob.
 *
 * @param sound  a namespaced sound ID, or {@code ""} for no sound
 * @param volume 0 or more; 1.0 is normal
 * @param pitch  0.5 to 2.0
 */
public record ToolSound(String sound, float volume, float pitch) {

    public static final ToolSound NONE = new ToolSound("", 1.0F, 1.0F);

    public ToolSound {
        Objects.requireNonNull(sound, "sound");
        sound = sound.trim();
    }

    public boolean isNone() {
        return sound.isEmpty();
    }
}
