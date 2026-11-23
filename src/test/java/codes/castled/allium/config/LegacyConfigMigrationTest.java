package codes.castled.allium.config;

import codes.castled.allium.chat.ChatSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConfigMigrationTest {
    private static final String LEGACY = """
            version: 0.1.5a
            lang: en
            server-name: Survival Fun
            chat:
              deletion_resend:
                clear_lines: 369
                header_enabled: true
              channels:
                local-channel:
                  aliases: [nearby]
                list:
                  default:
                    format: 'Legacy <message>'
            chat-hover:
              groups:
                default:
                  name:
                    hover: [Legacy hover]
            teleport:
              delay: 5
              expire: 120
              cooldown: 30
              tppet-radius: 30
            home:
              cooldown: 60
            economy:
              symbol-before-amount: false
              decimal-places: 2
            restore:
              snapshot-retention-days: 30
            give:
              drop-overflow-items: false
            mail:
              cooldown: 15
            handcuffs:
              commands-on-quit: ['ban {player} Gone']
            explode-command:
              default: 3
              max: 8
            creeper-explosion-regen-speed: 0
            auto-restart:
              enabled: true
            ore-generation:
              enabled: true
            pregen:
              chunks-per-tick: 4
            modguard:
              enabled: true
            anti-bot:
              commands-to-punish: [pl]
            old-addon:
              settings: [keep]
            """;

    @Test
    void aFirstLoadStartsFromThisAlliumsConfigAndMapsEveryLegacyKey() throws Exception {
        var legacy = yaml(LEGACY);
        var defaults = bundled();
        assertTrue(LegacyConfigMigration.legacy(legacy));
        var plan = LegacyConfigMigration.plan(legacy, defaults);
        var target = bundled();
        var rootResult = LegacyConfigMigration.apply(plan.into("config.yml"), target, null);

        assertEquals("en_US", target.getString("settings.locale"));
        assertEquals("Survival Fun", target.getString("discord.server-name"));
        assertEquals(5, target.getInt("teleport.warmup-seconds"));
        assertEquals(120, target.getInt("teleport.request-expiry-seconds"));
        assertEquals(30, target.getInt("teleport.cooldowns.tpa"));
        assertEquals(30, target.getInt("teleport.cooldowns.tpahere"));
        assertEquals(60, target.getInt("teleport.cooldowns.home"));
        assertEquals(30, target.getInt("restore.retention-days"));
        assertTrue(target.getBoolean("economy.currency-symbol-suffix"));
        assertEquals("partial", target.getString("items.give.overflow"));
        assertEquals(1, target.getInt("mail.rate-limit.sends"));
        assertEquals(15, target.getInt("mail.rate-limit.per-seconds"));
        assertEquals(List.of("ban {player} Gone"), target.getStringList("restraints.commands-on-quit"));
        assertTrue(target.getBoolean("chat.deletion_resend.header_enabled"));
        assertEquals(List.of("Legacy hover"), target.getStringList("chat-hover.groups.default.name.hover"));
        assertEquals(369, ChatSettings.read(target).clearEntries());
        assertEquals("Legacy <message>", ChatSettings.read(target).resolve("global").format());
        assertEquals("local", ChatSettings.read(target).resolve("nearby").id());

        // Nothing legacy-only comes along: other owners read those from config.legacy.yml.
        for (String gone : List.of("ore-generation", "pregen", "modguard", "anti-bot", "old-addon", "auto-restart", "lang",
                "server-name", "handcuffs", "home", "explode-command", "creeper-explosion-regen-speed", "version",
                "teleport.delay", "teleport.tppet-radius", "economy.decimal-places")) {
            assertFalse(target.isSet(gone), gone);
        }
        assertTrue(target.getConfigurationSection("modules") != null, "this Allium's own sections stay");

        // The other files get their share.
        assertEquals(List.of("enabled"), plan.into("autorestart.yml").stream().map(LegacyConfigMigration.Move::path).toList());
        var explosions = plan.into("explosions.yml");
        assertTrue(explosions.contains(new LegacyConfigMigration.Move("explode-command.max", "explosions.yml", "explode.max-power", 8)));
        assertTrue(explosions.contains(new LegacyConfigMigration.Move("creeper-explosion-regen-speed", "explosions.yml",
                "regeneration.sources.creeper", false)));

        String report = LegacyConfigMigration.report(plan, rootResult.outcomes(), "test");
        assertTrue(report.contains("applied       teleport.delay -> teleport.warmup-seconds"), report);
        assertTrue(report.contains("delegated     ore-generation.enabled: owned by the Ores addon"), report);
        assertTrue(report.contains("retired       old-addon.settings: not a setting of this Allium"), report);
        assertTrue(report.contains("applied       chat.deletion_resend.clear_lines"), report);
    }

    @Test
    void anEarlierCarryOverLosesItsLegacySectionsButKeepsLocalEdits() throws Exception {
        var defaults = bundled();
        var current = bundled();
        current.set("config-version", 1);
        current.set("teleport.warmup-seconds", 9);
        current.set("teleport.delay", 5);
        current.set("ore-generation.enabled", true);
        current.set("pregen.chunks-per-tick", 4);
        current.set("modguard.enabled", true);
        current.set("explode-command.max", 8);
        current.set("anti-bot.commands-to-punish", List.of("pl"));

        assertFalse(LegacyConfigMigration.legacy(current));
        var leftovers = LegacyConfigMigration.leftovers(current, defaults);
        assertTrue(leftovers.containsAll(List.of("ore-generation", "pregen", "modguard", "explode-command", "anti-bot", "teleport.delay")),
                leftovers.toString());
        var source = LegacyConfigMigration.extractLeftovers(current, defaults);
        var plan = LegacyConfigMigration.plan(source, defaults);
        var result = LegacyConfigMigration.apply(plan.into("config.yml"), current, defaults);
        LegacyConfigMigration.removeLegacy(current, plan, defaults);

        assertEquals(List.of("teleport.warmup-seconds"), result.kept());
        assertEquals(9, current.getInt("teleport.warmup-seconds"));
        for (String gone : List.of("ore-generation", "pregen", "modguard", "explode-command", "anti-bot", "teleport.delay")) {
            assertFalse(current.isSet(gone), gone);
        }
        assertTrue(current.isSet("teleport.cooldowns.tpa"));
        assertTrue(LegacyConfigMigration.leftovers(current, defaults).isEmpty());

        // Report reflects actual conflict-kept, delegated, and retired outcomes rather than claiming moved.
        String report = LegacyConfigMigration.report(plan, result.outcomes(), "cleanup");
        assertTrue(report.contains("conflict-kept teleport.delay -> teleport.warmup-seconds"), report);
        assertFalse(report.contains("applied       teleport.delay"), report);
        assertTrue(report.contains("delegated     ore-generation.enabled: owned by the Ores addon"), report);
        assertTrue(report.contains("retired       anti-bot.commands-to-punish:"), report);
    }

    @Test
    void nestedOnlyLegacySettingsAreDetectedWithoutAnyTopLevelLegacySectionAndReportAllOutcomes() throws Exception {
        var defaults = bundled();
        var current = bundled();
        current.set("config-version", ConfigVersionManager.CURRENT_VERSION);

        // Zero top-level legacy sections: ONLY nested legacy leaves under existing sections.
        int defaultExpire = defaults.getInt("teleport.request-expiry-seconds");
        current.set("teleport.delay", 7);                        // applied (warmup-seconds at default -> 7)
        current.set("teleport.expire", defaultExpire);           // equal (already matches destination)
        current.set("restore.retention-days", 99);               // operator customized destination
        current.set("restore.snapshot-retention-days", 45);      // conflict-kept (keeps 99)
        current.set("economy.symbol-before-amount", false);      // special nested -> applied
        current.set("mail.cooldown", 25);                        // special nested -> applied
        current.set("teleport.tppet-radius", 30);                // nested retired
        current.set("nickname.sync-to-essentials", true);        // nested retired under existing section

        assertFalse(LegacyConfigMigration.legacy(current));
        var leftovers = LegacyConfigMigration.leftovers(current, defaults);
        assertEquals(List.of(
                "teleport.delay",
                "teleport.expire",
                "restore.snapshot-retention-days",
                "teleport.tppet-radius",
                "nickname.sync-to-essentials",
                "economy.symbol-before-amount",
                "mail.cooldown"
        ), leftovers, "nested-only legacy paths must be discovered without any top-level legacy section");

        var source = LegacyConfigMigration.extractLeftovers(current, defaults);
        var plan = LegacyConfigMigration.plan(source, defaults);
        var result = LegacyConfigMigration.apply(plan.into("config.yml"), current, defaults);
        LegacyConfigMigration.removeLegacy(current, plan, defaults);

        // Applied values landed cleanly; customized destination kept; all nested legacy leaves removed.
        assertEquals(7, current.getInt("teleport.warmup-seconds"));
        assertEquals(defaultExpire, current.getInt("teleport.request-expiry-seconds"));
        assertEquals(99, current.getInt("restore.retention-days"));
        assertTrue(current.getBoolean("economy.currency-symbol-suffix"));
        assertEquals(1, current.getInt("mail.rate-limit.sends"));
        assertEquals(25, current.getInt("mail.rate-limit.per-seconds"));
        for (String gone : List.of("teleport.delay", "teleport.expire", "restore.snapshot-retention-days",
                "economy.symbol-before-amount", "mail.cooldown", "teleport.tppet-radius", "nickname.sync-to-essentials")) {
            assertFalse(current.isSet(gone), "nested legacy leaf should be removed: " + gone);
        }
        assertTrue(LegacyConfigMigration.leftovers(current, defaults).isEmpty());

        // Verify all outcomes in the sidecar report: applied, equal, conflict-kept, retired.
        String report = LegacyConfigMigration.report(plan, result.outcomes(), "nested-only");
        assertTrue(report.contains("applied       teleport.delay -> teleport.warmup-seconds"), report);
        assertTrue(report.contains("equal         teleport.expire -> teleport.request-expiry-seconds (already equal)"), report);
        assertTrue(report.contains("conflict-kept restore.snapshot-retention-days -> restore.retention-days"), report);
        assertTrue(report.contains("applied       economy.symbol-before-amount -> economy.currency-symbol-suffix"), report);
        assertTrue(report.contains("applied       mail.cooldown -> mail.rate-limit.per-seconds"), report);
        assertTrue(report.contains("retired       teleport.tppet-radius:"), report);
        assertTrue(report.contains("retired       nickname.sync-to-essentials:"), report);
    }

    @Test void chatFormatGroupsCommentsSurviveYamlSaveAndMigration() throws Exception {
        YamlConfiguration target = bundled();
        YamlConfiguration legacy = yaml("""
                chat-format:
                  default: '<prefix><display_name>&7: &f<message>'
                """);
        var plan = LegacyConfigMigration.plan(legacy, bundled());
        LegacyConfigMigration.apply(plan.into("config.yml"), target, null);
        String saved = target.saveToString();
        assertTrue(saved.contains("  #   owner: '&c[Owner] <prefix><display_name>&f: <message>'"), saved);
        assertTrue(saved.contains("  #   admin: '&4[Admin] <prefix><display_name>&f: <message>'"), saved);
        assertTrue(saved.contains("  #   default: '<prefix><display_name>&7: <message>'"), saved);
        assertTrue(saved.contains("  groups: {}"), saved);
        assertFalse(saved.contains("\n#   owner:"), "group example comments must stay indented under chat-format, not detach onto chat-hover");
    }

    private static YamlConfiguration bundled() throws Exception {
        var config = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                LegacyConfigMigrationTest.class.getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        return config;
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }
}
