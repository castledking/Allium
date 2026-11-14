package codes.castled.allium.travel;

import codes.castled.allium.teleport.SavedLocation;

import java.util.Objects;

/** The exact row a confirmation refers to; revisions are never reused after deletion. */
public record StoredDestination(String name, SavedLocation location, long revision) {
    public StoredDestination {
        name = TravelNames.normalize(name);
        Objects.requireNonNull(location, "location");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    }
}
