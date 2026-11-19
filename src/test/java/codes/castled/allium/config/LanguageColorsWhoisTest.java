package codes.castled.allium.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageColorsWhoisTest {

    @Test
    void stockOneLineWhoisFieldsBecomeOneLinePerFieldAndEditedTextIsKept() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                info:
                  whois:
                    flags:
                      - "&7Op: {op} &8| &7Muted: {muted} &8| &7Banned: {banned} &8| &7Whitelisted: {whitelisted} &8| &7Frozen: {frozen} &8| &7Jailed: {jailed}"
                    state:
                      - "&7Gamemode: &f{gamemode} &8| &7Fly: {fly} &8| &7Flying: {flying} &8| &7God: {god} &8| &7Vanished: {vanished} &8| &7AFK: {afk}"
                      - "[sound] ui.button.click 1.0"
                    vitals:
                      - "&7HP {health}, my own layout"
                """);
        assertEquals(2, LanguageColors.upgrade(yaml));
        assertEquals(List.of("&7Op: {op}", "&7Muted: {muted}", "&7Banned: {banned}", "&7Whitelisted: {whitelisted}",
                "&7Frozen: {frozen}", "&7Jailed: {jailed}"), yaml.getStringList("info.whois.flags"));
        assertEquals(List.of("&7Gamemode: &f{gamemode}", "&7Fly: {fly}", "&7Flying: {flying}", "&7God: {god}",
                "&7Vanished: {vanished}", "&7AFK: {afk}", "[sound] ui.button.click 1.0"), yaml.getStringList("info.whois.state"));
        assertEquals(List.of("&7HP {health}, my own layout"), yaml.getStringList("info.whois.vitals"));
        assertEquals(0, LanguageColors.upgrade(yaml), "an upgraded file is left alone");
    }
}
