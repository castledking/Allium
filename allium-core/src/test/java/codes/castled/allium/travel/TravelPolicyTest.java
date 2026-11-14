package codes.castled.allium.travel;

import codes.castled.allium.teleport.SavedLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TravelPolicyTest {
    @Test
    void namesAcceptOnlyOneToThirtyTwoAsciiLettersDigitsUnderscoresAndHyphens() {
        assertEquals("home", TravelNames.normalize("HOME"));
        assertEquals("a_2-b", TravelNames.normalize("A_2-b"));
        assertEquals("a".repeat(32), TravelNames.normalize("A".repeat(32)));
        for (String invalid : List.of("", "a".repeat(33), " home", "home ", "a b", "a.b", "a/b", "a:b",
                "a\nb", "a\u0000b", "\u00e9", "\u212a", "\u0130", "\uff21", "\ud83c\udfe0")) {
            assertThrows(IllegalArgumentException.class, () -> TravelNames.normalize(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> TravelNames.normalize(null));
    }

    @Test
    void normalizationDoesNotDependOnTheServerLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("island", TravelNames.normalize("ISLAND"));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void multipleHomeGrantsAndStaffOverridesUseMaximumRatherThanAddition() {
        assertEquals(7, HomePolicy.maximum(2, List.of(3, 7, 5), false));
        assertEquals(2, HomePolicy.maximum(2, List.of(1), 0));
        assertEquals(20, HomePolicy.maximum(2, List.of(3, 7), 20));
        assertEquals(7, HomePolicy.maximum(2, List.of(7), 4));
        assertEquals(0, HomePolicy.maximum(-1, List.of(-20), -10));
        assertEquals(0, HomePolicy.maximum(0, List.of(), false));
        assertEquals(1000, HomePolicy.maximum(0, List.of(), true));
        assertEquals(1000, HomePolicy.maximum(Integer.MAX_VALUE, List.of(), false));
        assertEquals(1000, HomePolicy.maximum(1, List.of(Integer.MAX_VALUE), 0));
        assertEquals(1000, HomePolicy.maximum(1, List.of(), Integer.MAX_VALUE));
    }

    @Test
    void storedSnapshotsNormalizeNamesAndRequirePositiveRevisions() {
        SavedLocation location = new SavedLocation(UUID.randomUUID(), "world", 0, 64, 0, 0, 0);
        assertEquals("home", new StoredDestination("HOME", location, 1).name());
        assertThrows(IllegalArgumentException.class, () -> new StoredDestination("home", location, 0));
        assertThrows(IllegalArgumentException.class, () -> new StoredDestination("home", location, -1));
        assertThrows(NullPointerException.class, () -> new StoredDestination("home", null, 1));
    }
}
