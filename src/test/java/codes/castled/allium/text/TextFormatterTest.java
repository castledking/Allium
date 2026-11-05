package codes.castled.allium.text;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.Messages;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextFormatterTest {

    private static final LegacyComponentSerializer BUNGEE_HEX_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static void assertBothPathsMatch(String input, String expectedLegacy) {
        FormattedText parsed = TextFormatter.parse(input);
        String actualLegacy = parsed.toLegacy();
        assertEquals(expectedLegacy, actualLegacy, "Legacy path mismatch for: " + input);

        Component component = AdventureTextBridge.toComponent(parsed);
        String adventureVisibleLegacy = AdventureTextBridge.componentToVisibleLegacy(component);
        assertEquals(expectedLegacy, adventureVisibleLegacy, "Adventure bridge visible parity mismatch for: " + input);
    }

    @Test
    void emptyAndSingleCharacterStringsOnBothPaths() {
        assertBothPathsMatch("", "");
        assertBothPathsMatch("<gradient:#ff0000:#00ff00></gradient>", "");
        assertBothPathsMatch("<rainbow></rainbow>", "");

        // Unstyled 1-char string
        assertBothPathsMatch("A", "A");
        assertEquals("A", BUNGEE_HEX_SERIALIZER.serialize(AdventureTextBridge.toComponent(TextFormatter.parse("A"))));

        // Legacy 1-char string
        assertBothPathsMatch("&aA", "§aA");
        assertEquals("§aA", BUNGEE_HEX_SERIALIZER.serialize(AdventureTextBridge.toComponent(TextFormatter.parse("&aA"))));

        // Hex 1-char string
        assertBothPathsMatch("&#12ab34Z", "§x§1§2§a§b§3§4Z");
        assertEquals("§x§1§2§a§b§3§4Z", BUNGEE_HEX_SERIALIZER.serialize(AdventureTextBridge.toComponent(TextFormatter.parse("&#12ab34Z"))));

        // 2-stop gradient on 1-char string uses first stop
        assertBothPathsMatch("<gradient:#ff0000:#0000ff>X</gradient>", "§x§f§f§0§0§0§0X");
        assertEquals("§x§f§f§0§0§0§0X", BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse("<gradient:#ff0000:#0000ff>X</gradient>"))));

        // Rainbow on 1-char string uses hue 0.0 (#ff0000)
        assertBothPathsMatch("<rainbow>X</rainbow>", "§x§f§f§0§0§0§0X");
        assertEquals("§x§f§f§0§0§0§0X", BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse("<rainbow>X</rainbow>"))));
    }

    @Test
    void legacyAndHexFormattingOnBothPaths() {
        // Legacy colors & decorations
        assertBothPathsMatch("&aHello &lWorld", "§aHello §lWorld");
        // Inline color resets prior decorations
        assertBothPathsMatch("&a&lBold &bNotBold", "§a§lBold §bNotBold");
        assertEquals("§a§lBold §bNotBold", BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse("&a&lBold &bNotBold"))));

        // Hex formats: &#rrggbb, #rrggbb, §x§r§r§g§g§b§b, <#rrggbb>
        assertBothPathsMatch("&#ff8800Hi", "§x§f§f§8§8§0§0Hi");
        assertBothPathsMatch("#00ff88Hi", "§x§0§0§f§f§8§8Hi");
        assertBothPathsMatch("§x§1§2§3§4§5§6Hi", "§x§1§2§3§4§5§6Hi");
        assertBothPathsMatch("&x&a&b&c&d&e&fHi", "§x§a§b§c§d§e§fHi");
        assertBothPathsMatch("<#112233>Hi</#112233>", "§x§1§1§2§2§3§3Hi");
    }

    @Test
    void twoStopAndMultiStopGradientsOnBothPaths() {
        // 2-stop gradient over 3 chars: #ff0000 -> #800080 -> #0000ff
        String twoStop = "<gradient:#ff0000:#0000ff>ABC</gradient>";
        String expectedTwoStop = "§x§f§f§0§0§0§0A§x§8§0§0§0§8§0B§x§0§0§0§0§f§fC";
        assertBothPathsMatch(twoStop, expectedTwoStop);
        assertEquals(expectedTwoStop, BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse(twoStop))));

        // 3-stop gradient over 5 chars: #ff0000 -> #00ff00 -> #0000ff
        // t = 0.0 (#ff0000), 0.25 (#808000), 0.5 (#00ff00), 0.75 (#008080), 1.0 (#0000ff)
        String threeStop = "<gradient:#ff0000:#00ff00:#0000ff>12345</gradient>";
        String expectedThreeStop = "§x§f§f§0§0§0§01§x§8§0§8§0§0§02§x§0§0§f§f§0§03§x§0§0§8§0§8§04§x§0§0§0§0§f§f5";
        assertBothPathsMatch(threeStop, expectedThreeStop);
        assertEquals(expectedThreeStop, BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse(threeStop))));
    }

    @Test
    void rainbowFormattingOnBothPaths() {
        // 3 glyphs across hue wheel: 0/3 (0.0 = #ff0000), 1/3 (0.3333 = #00ff00), 2/3 (0.6667 = #0000ff)
        String rainbow = "<rainbow>RGB</rainbow>";
        String expectedRainbow = "§x§f§f§0§0§0§0R§x§0§0§f§f§0§0G§x§0§0§0§0§f§fB";
        assertBothPathsMatch(rainbow, expectedRainbow);
        assertEquals(expectedRainbow, BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse(rainbow))));

        // Phase-shifted rainbow by 1/3 (so index 0 starts at green #00ff00)
        String shifted = "<rainbow:0.3333333333333333>RGB</rainbow>";
        String expectedShifted = "§x§0§0§f§f§0§0R§x§0§0§0§0§f§fG§x§f§f§0§0§0§0B";
        assertBothPathsMatch(shifted, expectedShifted);
    }

    @Test
    void nestedAndResetFormattingOnBothPaths() {
        // Bold inside gradient persists across per-glyph hex colors
        String boldGradient = "<gradient:#ff0000:#0000ff>&lAB</gradient>";
        String expectedBoldGradient = "§x§f§f§0§0§0§0§lA§x§0§0§0§0§f§f§lB";
        assertBothPathsMatch(boldGradient, expectedBoldGradient);
        assertEquals(expectedBoldGradient, BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse(boldGradient))));

        // Nested gradient inside outer gradient resumes outer gradient after closing inner
        String nested = "<gradient:#ff0000:#0000ff>A<gradient:#00ff00:#ffff00>XY</gradient>B</gradient>";
        String expectedNested = "§x§f§f§0§0§0§0A§x§0§0§f§f§0§0X§x§f§f§f§f§0§0Y§x§0§0§0§0§f§fB";
        assertBothPathsMatch(nested, expectedNested);
        assertEquals(expectedNested, BUNGEE_HEX_SERIALIZER.serialize(
                AdventureTextBridge.toComponent(TextFormatter.parse(nested))));

        // Reset via &r and <reset> clears active color and decorations
        assertBothPathsMatch("&a&lColored&rPlain", "§a§lColored§rPlain");
        assertBothPathsMatch("<gradient:#ff0000:#00ff00>AB<reset>CD</gradient>", "§x§f§f§0§0§0§0A§x§0§0§f§f§0§0B§rCD");
    }

    @Test
    void customGlyphColorSourceHookSupportsAnimatedContract() {
        // S01.5b readiness check: supply color(glyphIndex, length, timeMs, seed) -> RGB
        long timeMs = 200L;
        long seed = 42L;
        GlyphColorSource animatedHook = (glyphIndex, length) -> {
            int frame = (int) (timeMs / 100L);
            return ((glyphIndex + frame + (int) seed) % 2 == 0) ? 0x112233 : 0x445566;
        };
        FormattedText parsed = TextFormatter.parse("AB", animatedHook);
        assertEquals("§x§1§1§2§2§3§3A§x§4§4§5§5§6§6B", parsed.toLegacy());
    }

    @Test
    void messageManagerRoutesThroughFormatterAndPlaysSoundDirectly() {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        YamlConfiguration config = new YamlConfiguration();
        config.set("sounds.defaults.volume", 0.8);
        config.set("sounds.defaults.pitch", 1.2);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDescription()).thenReturn(
                new PluginDescriptionFile("Allium", "1.0.0", "codes.castled.allium.PluginStart"));

        LanguageManager lang = mock(LanguageManager.class);
        when(lang.get(Messages.PREFIX)).thenReturn("<gradient:#ff0000:#00ff00>Allium</gradient> ");
        when(lang.get(Messages.HEAL)).thenReturn("{prefix}&aHealed!");
        when(lang.getSound(Messages.HEAL)).thenReturn(
                new MessageSoundSpec("minecraft:entity.experience_orb.pickup", null, null));

        MessageManager mm = new MessageManager(plugin, lang);

        // Test non-Audience Spigot player fallback + sound playback
        Player spigotPlayer = mock(Player.class);
        Location loc = new Location(null, 10, 64, 10);
        when(spigotPlayer.getLocation()).thenReturn(loc);

        mm.send(spigotPlayer, Messages.HEAL);

        String expectedLegacy = TextFormatter.toLegacy("<gradient:#ff0000:#00ff00>Allium</gradient> &aHealed!");
        verify(spigotPlayer).sendMessage(expectedLegacy);
        verify(spigotPlayer).playSound(eq(loc), eq("minecraft:entity.experience_orb.pickup"), eq(0.8f), eq(1.2f));

        // Test Paper Audience sender path
        interface AudienceCommandSender extends CommandSender, Audience {}
        AudienceCommandSender paperSender = mock(AudienceCommandSender.class);
        mm.send(paperSender, Messages.HEAL);
        verify(paperSender).sendMessage(any(Component.class));
        verify(paperSender, never()).sendMessage(anyString());
    }
}
