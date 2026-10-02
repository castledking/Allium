package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every MiniMessage tag in the shipped boost catalogue has to be a real one.
 *
 * <p>MiniMessage does not complain about an unknown tag. It leaves it as literal
 * text, which is why a boost whose display was {@code "<purple>Token Fortune"}
 * showed the player {@code <purple>Token Fortune +0.15} — there is no colour
 * called {@code purple}, only {@code light_purple} and {@code dark_purple}, but
 * nothing anywhere said so at load time.
 *
 * <p>So it is asserted here instead: a tag that survives into the rendered plain
 * text is a tag MiniMessage did not understand.
 */
class BoostDisplayTagTest {

    /** Colour names Minecraft actually has. */
    private static final List<String> COLOURS = List.of(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
        "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
        "yellow", "white");

    private static final List<String> DECORATIONS = List.of(
        "bold", "italic", "underlined", "strikethrough", "obfuscated");

    @Test
    void everyBoostDisplayNameRendersWithoutLeavingTagsVisible() {
        List<String> offenders = new ArrayList<>();
        for (var boost : boosts()) {
            String display = boost.value();
            String plain = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(display).toString();
            if (plain.contains("<")) {
                offenders.add(boost.id() + " -> " + display + "  (rendered: " + plain + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
            "these display names contain a tag MiniMessage does not understand, which "
                + "shows the player the raw text:\n  " + String.join("\n  ", offenders));
    }

    @Test
    void theColourNamesUsedAreRealOnes() {
        // The check above is behavioural; this names the mistake explicitly so the
        // failure message says which names are wrong rather than what broke.
        for (var boost : boosts()) {
            for (String tag : tagsIn(boost.value())) {
                boolean known = COLOURS.contains(tag) || DECORATIONS.contains(tag)
                    || tag.equals("color") || tag.equals("gradient");
                assertTrue(known, boost.id() + " uses <" + tag
                    + ">, which is not a colour or decoration Minecraft has");
            }
        }
    }

    private record Boost(String id, String value) {}

    /** Reads every boost's display name out of the shipped catalogue. */
    private static List<Boost> boosts() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = BoostDisplayTagTest.class
                .getResourceAsStream("/tradingcards/boosts.yml")) {
            assertTrue(in != null, "tradingcards/boosts.yml is not on the classpath");
            yaml.loadFromString(new String(in.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("could not read the shipped catalogue", e);
        }
        var section = yaml.getConfigurationSection("boosts");
        assertTrue(section != null, "boosts.yml has no boosts section");
        List<Boost> out = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String display = section.getString(id + ".display");
            if (display != null && !display.isBlank()) {
                out.add(new Boost(id, display));
            }
        }
        assertFalse(out.isEmpty(), "no boost display names were read");
        return out;
    }

    private static List<String> tagsIn(String value) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
