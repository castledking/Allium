package codes.castled.allium.lang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyLangImportTest {

    /** The recode's defaults for the keys used here; a key without a default is unknown. */
    private static final Map<String, String> DEFAULTS = Map.of(
            "messaging.received", "&7[&f{sender} &7→ &fme&7] &f{message}",
            "messaging.sent", "&7[&fme &7→ &f{recipient}&7] &f{message}",
            "messaging.no-reply-target", "{prefix} &cYou have nobody to reply to.",
            "player.heal", "{prefix} &aYou have been healed.",
            "player.feed-other", "{prefix} &aFed &f{player}&a.",
            "error.player-not-found", "{prefix} &cPlayer &f{player} &cnot found.",
            "prefix", "&7[&dAllium&7]");

    private static LegacyLangImport.Report convert(Map<String, ?> document) {
        return LegacyLangImport.convert(document, DEFAULTS::get);
    }

    private static LegacyLangImport.Entry entry(LegacyLangImport.Report report, String key) {
        return report.entries().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void aListWithASoundLineBecomesTextPlusSoundAndMessagesKeepTheirPlaceholders() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("sent", List.of("&7(To &f{recipient}&7) &r{message}", "[sound] block.note_block.pling 0.5 2"));
        msg.put("received", List.of("&7(From &f{sender}&7) &r{message}", "[sound] block.note_block.pling 0.5 1.5"));
        msg.put("reply", "You have no one to reply to.");
        LegacyLangImport.Report report = convert(Map.of("msg", msg));

        LegacyLangImport.Entry received = entry(report, "messaging.received");
        assertEquals("&7(From &f{sender}&7) &r{message}", received.text());
        assertEquals("minecraft:block.note_block.pling", received.sound());
        assertEquals(0.5D, received.volume());
        assertEquals(1.5D, received.pitch());
        // The recode's no-reply default carries {prefix}; pre-recode Allium added it in code.
        assertEquals("{prefix} You have no one to reply to.", entry(report, "messaging.no-reply-target").text());
        assertNull(entry(report, "messaging.no-reply-target").sound());
    }

    @Test
    void anOmittedVolumeOrPitchStaysOmittedSoEachFallsBackSeparately() {
        Map<String, Object> heal = Map.of("self", List.of("&aYou have been healed.", "[sound] entity.player.levelup"));
        Map<String, Object> feed = Map.of("other", List.of("&aYou have satiated &e{name}&a.", "[sound ENTITY_GENERIC_EAT 0.7]"));
        LegacyLangImport.Report report = convert(Map.of("heal", heal, "feed", feed));
        LegacyLangImport.Entry healed = entry(report, "player.heal");
        assertEquals("minecraft:entity.player.levelup", healed.sound());
        assertNull(healed.volume());
        assertNull(healed.pitch());
        LegacyLangImport.Entry fed = entry(report, "player.feed-other");
        assertEquals("ENTITY_GENERIC_EAT", fed.sound(), "enum names stay as they are");
        assertEquals(0.7D, fed.volume());
        assertNull(fed.pitch());
        assertEquals("{prefix} &aYou have satiated &e{player}&a.", fed.text(), "{name} becomes {player}");
    }

    @Test
    void particleAndCommandLinesAreDroppedAndReportedSoALangFileCanNeverRunACommand() {
        Map<String, Object> heal = Map.of("self", List.of("&aYou have been healed.", "[sound] entity.player.levelup 0.5 1.5",
                "[particle] HEART 5 0.1 0 0.5 0", "[command op {player}]", "[sound] entity.generic.eat 1 1"));
        LegacyLangImport.Report report = convert(Map.of("heal", heal));
        LegacyLangImport.Entry healed = entry(report, "player.heal");
        assertEquals("{prefix} &aYou have been healed.", healed.text());
        assertEquals("minecraft:entity.player.levelup", healed.sound());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("[particle]")));
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("[command]")));
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("second [sound]")));
        assertFalse(healed.text().contains("op"));
    }

    @Test
    void miniMessageColoursBecomeCodesGradientsStayAndInteractiveTagsAreRemoved() {
        List<String> notes = new ArrayList<>();
        String converted = LegacyLangImport.convertTags(
                "<red>Error:</red> <bold>bad</bold> <gradient:#CD2B39:#B82E3A>x</gradient> <#00ff00>hex "
                        + "<click:run_command:/op me>click</click> <hover:show_text:'hi'>hover</hover><newline>end", notes);
        assertEquals("&cError: &lbad <gradient:#CD2B39:#B82E3A>x</gradient> <#00ff00>hex click hover\nend", converted);
        assertTrue(notes.stream().anyMatch(n -> n.contains("click") && n.contains("hover")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("closing")));
    }

    @Test
    void theLegacyPrefixMovesAsItIsAndBracketedTextIsNotADirective() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("core-prefix", "<gradient:#B2C3EE:#ADF3FD>Core</gradient>");
        doc.put("player-not-found", List.of("[Server] Player {name} was not found."));
        LegacyLangImport.Report report = convert(doc);
        assertEquals("<gradient:#B2C3EE:#ADF3FD>Core</gradient>", entry(report, "prefix").text());
        assertEquals("{prefix} [Server] Player {player} was not found.", entry(report, "error.player-not-found").text());
    }

    @Test
    void unmappedMessagesAreListedAndConfigurationKeysAreNot() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 7);
        doc.put("error-prefix", "<red>");
        doc.put("styles", Map.of("state", Map.of("true", "&a")));
        doc.put("warp", Map.of("set", "&aWarp set."));
        doc.put("heal", Map.of("self", "&aHealed.", "other", "&aYou have been healed by &e{player}&a."));
        LegacyLangImport.Report report = convert(doc);
        assertEquals(List.of("warp.set", "heal.other"), report.unmapped());
        assertEquals(List.of("player.heal"), report.entries().stream().map(LegacyLangImport.Entry::key).toList());
    }

    @Test
    void aPlaceholderAlliumsOwnTextDoesNotUseIsReported() {
        LegacyLangImport.Report report = convert(Map.of("msg", Map.of("reply", "No one to reply to, {player}.")));
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("{player}") && n.contains("messaging.no-reply-target")), report.notes().toString());
        LegacyLangImport.Report fine = convert(Map.of("feed", Map.of("other", "&aFed &e{name}&a.")));
        assertTrue(fine.notes().isEmpty(), "{name} becomes {player}, which the recode fills: " + fine.notes());
    }

    @Test
    void aBadSoundNumberIsLeftOutWithANote() {
        LegacyLangImport.Report report = convert(Map.of("heal", Map.of("self", List.of("&aHealed.", "[sound] entity.player.levelup loud 1.2"))));
        LegacyLangImport.Entry healed = entry(report, "player.heal");
        assertNull(healed.volume());
        assertEquals(1.2D, healed.pitch());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("'loud'")));
    }
}
