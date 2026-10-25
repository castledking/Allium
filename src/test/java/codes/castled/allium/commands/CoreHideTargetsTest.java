package codes.castled.allium.commands;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreHideTargetsTest {
    @Test
    void groupAddStoresTheWholeSubcommandPathAsOneYamlEntry() throws Exception {
        List<String> targets = Core.parseHideTargets(
                new String[]{"hide", "group", "default", "add", "party", "admin", "invite"}, 4);
        assertEquals(List.of("party admin invite"), targets);
        YamlConfiguration config = new YamlConfiguration();
        config.set("groups.default.commands", targets);
        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.loadFromString(config.saveToString());
        assertEquals(List.of("party admin invite"), reloaded.getStringList("groups.default.commands"));
    }

    @Test
    void commasSeparatePathsWhileSpacesSeparateSubcommands() {
        assertEquals(List.of("party invite", "spawn", "party leave"), Core.parseHideTargets(
                new String[]{"hide", "group", "default", "remove", "-t", "/PARTY", "invite,", "spawn,party", "leave"}, 5));
        assertEquals(List.of("spawn", "help"), Core.parseHideTargets(new String[]{"spawn,help"}, 0));
    }

    @Test
    void normalizedDuplicatesAndEmptyEntriesAreIgnored() {
        assertEquals(List.of("party invite"), Core.parseHideTargets(
                new String[]{"party", "invite,", "/PARTY", "", "invite,,"}, 0));
        assertEquals(List.of(), Core.parseHideTargets(new String[]{",,,"}, 0));
    }
}
