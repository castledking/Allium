package codes.castled.allium.info;

import codes.castled.allium.placeholder.PlaceholderFormats;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaytimeTest {

    @Test
    void shortDurationsKeepTheTwoLargestParts() {
        assertEquals("12d 6h", PlaceholderFormats.durationShort(12 * 86_400 + 6 * 3_600 + 30 * 60 + 5));
        assertEquals("6h 30m", PlaceholderFormats.durationShort(6 * 3_600 + 30 * 60 + 5));
        assertEquals("1d 5m", PlaceholderFormats.durationShort(86_400 + 300), "parts that are zero are skipped, as in the full form");
        assertEquals("45s", PlaceholderFormats.durationShort(45));
        assertEquals("0s", PlaceholderFormats.durationShort(0));
    }

    @Test
    void statsFilesGiveThePlayTimeInSeconds() {
        assertEquals(3_600, Playtime.statSeconds("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":72000,\"minecraft:jump\":3}},\"DataVersion\":3465}"));
        assertEquals(60, Playtime.statSeconds("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_one_minute\":1200}}}"));
        assertEquals(0, Playtime.statSeconds("{\"stats\":{}}"));
    }

    @Test
    void datesUseThePatternAndFallBackWhenItIsInvalid() {
        long noon = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli();
        assertTrue(Playtime.formatter("MMM d, yyyy").format(Instant.ofEpochMilli(noon)).matches("Nov (19|20), 2026"));
        assertTrue(Playtime.formatter("not a pattern {").format(Instant.ofEpochMilli(noon)).startsWith("Nov"));
    }

    @Test
    void creativeWithoutTheBypassIsRestrictedCreative() {
        Player p = mock(Player.class);
        when(p.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertEquals("restricted-creative", codes.castled.allium.placeholder.AlliumPlaceholders.gamemode(p));
        when(p.hasPermission("allium.gamemode.creative.bypass")).thenReturn(true);
        assertEquals("creative", codes.castled.allium.placeholder.AlliumPlaceholders.gamemode(p));
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        assertEquals("survival", codes.castled.allium.placeholder.AlliumPlaceholders.gamemode(p));
    }
}
