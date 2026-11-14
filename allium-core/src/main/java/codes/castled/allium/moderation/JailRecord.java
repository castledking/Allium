package codes.castled.allium.moderation;

import codes.castled.allium.teleport.SavedLocation;

/** A named, platform-neutral jail destination. Names are canonical lowercase ASCII. */
public record JailRecord(String name, SavedLocation location) {
    public JailRecord {
        name = ModerationRepository.jailName(name);
        ModerationRepository.validateLocation(location);
    }
}
