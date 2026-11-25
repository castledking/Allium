package codes.castled.allium.importer.essentials;

import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.importer.essentials.EssentialsConfigReport.Outcome;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EssentialsConfigReportTest {

    @Test
    void classifiesMappedManualConflictAndUnsupportedKeysAndPermissions(@TempDir Path dir) throws Exception {
        Path essDir = dir.resolve("Essentials");
        Files.createDirectories(essDir.resolve("warps"));
        UUID worldId = UUID.randomUUID();

        Files.writeString(essDir.resolve("warps/vip_lounge.yml"), """
                world: %s
                world-name: world
                x: 10.0
                y: 64.0
                z: 10.0
                """.formatted(worldId));
        Files.writeString(essDir.resolve("kits.yml"), """
                kits:
                  starter:
                    delay: -1
                    items:
                      - stone_pickaxe 1
                """);
        Files.writeString(essDir.resolve("config.yml"), """
                teleport-delay: 5
                teleport-cooldown: 30
                teleport-safety: true
                teleport-invulnerability: 4
                teleport-to-center: true
                spawn-if-no-home: true
                sethome-multiple:
                  default: 3
                  vip: 5
                  staff: 12
                per-warp-permission: true
                starting-balance: 100
                min-money: -500
                currency-symbol: '$'
                command-costs:
                  heal: 50
                  nick: 100
                command-cooldowns:
                  heal: 60
                  nick: 120
                newbies:
                  kit: starter
                  spawnpoint: newbies
                  announce-format: '&dWelcome {DISPLAYNAME}!'
                enabledSigns:
                  - buy
                  - sell
                  - warp
                  - trade
                sign-use-per-second: 4
                permission-based-item-spawn: true
                item-spawn-blacklist: 'bedrock,tnt'
                chat:
                  radius: 100
                  format: '{TEAMNAME} {PREFIX}{DISPLAYNAME}: {MESSAGE}'
                custom-join-message: '&e{PLAYER} joined'
                """);

        YamlConfiguration allium = new YamlConfiguration();
        allium.set("teleport.warmup-seconds", 3); // differs from Essentials 5 -> CONFLICT
        allium.set("teleport.cooldowns.home", 0);
        allium.set("teleport.cooldowns.warp", 0);
        allium.set("teleport.cooldowns.tpa", 0);
        allium.set("teleport.safety.enabled", true); // matches -> MAPPED
        allium.set("teleport.arrival-pvp-grace-seconds", 0); // differs from 4 -> CONFLICT
        allium.set("homes.spawn-if-no-home", true); // matches -> MAPPED
        allium.set("homes.default-limit", 1); // differs from 3 -> CONFLICT
        allium.set("economy.starting-balance", "100.00"); // numeric equal to 100 -> MAPPED
        allium.set("economy.min-money", "0.00"); // differs from -500 -> CONFLICT
        allium.set("economy.currency-symbol", "$"); // matches -> MAPPED
        allium.set("kits.first-join-kit", "");
        allium.set("sign-use-per-second", 4); // matches -> MAPPED
        allium.set("chat.channels.local-channel.radius", 100.0); // matches -> MAPPED

        EssentialsSnapshot snapshot = EssentialsSnapshot.read(essDir);
        EssentialsImportPlan plan = EssentialsImportPlan.build(snapshot,
                (id, name) -> Optional.of(new ResolvedWorld(worldId, "world")),
                EssentialsImportPlan.Destination.empty(), 1_800_000_000_000L, EconomySettings.defaults());

        EssentialsConfigReport report = EssentialsConfigReport.analyze(essDir, allium, plan);

        assertEquals(Outcome.CONFLICT, findConfig(report, "teleport-delay").outcome());
        assertEquals(Outcome.CONFLICT, findConfig(report, "teleport-cooldown").outcome());
        assertEquals(Outcome.MAPPED, findConfig(report, "teleport-safety").outcome());
        assertEquals(Outcome.CONFLICT, findConfig(report, "teleport-invulnerability").outcome());
        assertEquals("teleport.arrival-pvp-grace-seconds", findConfig(report, "teleport-invulnerability").targetKey());
        assertEquals(Outcome.MANUAL, findConfig(report, "teleport-to-center").outcome());
        assertEquals(Outcome.MAPPED, findConfig(report, "spawn-if-no-home").outcome());
        assertEquals("homes.spawn-if-no-home", findConfig(report, "spawn-if-no-home").targetKey());
        assertEquals(Outcome.CONFLICT, findConfig(report, "permission-based-item-spawn").outcome());
        assertEquals("items.give.policy.permission-based-spawn", findConfig(report, "permission-based-item-spawn").targetKey());
        assertEquals(Outcome.CONFLICT, findConfig(report, "item-spawn-blacklist").outcome());
        assertEquals("items.give.policy.deny", findConfig(report, "item-spawn-blacklist").targetKey());
        assertEquals("allium.give.item.minecraft.<id>", findPerm(report, "essentials.give.item-*").alliumNode());
        assertEquals("allium.give.bypass.policy", findPerm(report, "essentials.itemspawn.exempt").alliumNode());
        assertEquals(Outcome.CONFLICT, findConfig(report, "sethome-multiple.default").outcome());
        assertEquals(Outcome.MANUAL, findConfig(report, "sethome-multiple.vip").outcome());
        assertEquals(Outcome.MAPPED, findConfig(report, "per-warp-permission").outcome());
        assertEquals(Outcome.MAPPED, findConfig(report, "starting-balance").outcome());
        assertEquals(Outcome.CONFLICT, findConfig(report, "min-money").outcome());
        assertEquals(Outcome.MAPPED, findConfig(report, "currency-symbol").outcome());
        assertEquals(Outcome.CONFLICT, findConfig(report, "command-costs.heal").outcome());
        assertEquals("command-policy.actions.heal.cost", findConfig(report, "command-costs.heal").targetKey());
        assertEquals(Outcome.UNSUPPORTED, findConfig(report, "command-costs").outcome());
        assertEquals(Outcome.CONFLICT, findConfig(report, "command-cooldowns.heal").outcome());
        assertEquals("command-policy.actions.heal.cooldown-seconds", findConfig(report, "command-cooldowns.heal").targetKey());
        assertEquals(Outcome.UNSUPPORTED, findConfig(report, "command-cooldowns").outcome());
        assertEquals(Outcome.MAPPED, findConfig(report, "newbies.kit").outcome());
        assertEquals(Outcome.UNSUPPORTED, findConfig(report, "enabledSigns.trade").outcome());
        assertEquals(Outcome.UNSUPPORTED, findConfig(report, "chat.format").outcome());
        assertEquals(Outcome.MANUAL, findConfig(report, "custom-join-message").outcome());

        // Permission mappings
        assertEquals("allium.sethome.multiple.5", findPerm(report, "essentials.sethome.multiple.vip").alliumNode());
        assertEquals("allium.sethome.multiple.12", findPerm(report, "essentials.sethome.multiple.staff").alliumNode());
        assertEquals("allium.warps.vip_lounge", findPerm(report, "essentials.warps.vip_lounge").alliumNode());
        assertEquals("allium.kits.starter", findPerm(report, "essentials.kits.starter").alliumNode());
        assertEquals("allium.signs.use.buy", findPerm(report, "essentials.signs.use.buy").alliumNode());
        assertEquals(Outcome.UNSUPPORTED, findPerm(report, "essentials.signs.use.trade").outcome());

        String text = report.renderText("essentials-20261124-095000");
        assertTrue(text.contains("Existing Allium settings remain authoritative on CONFLICT"));
        assertTrue(text.contains("allium.sethome.multiple.5"));
        assertTrue(text.contains("allium.warps.vip_lounge"));
        assertTrue(text.contains("allium.kits.starter"));

        String jsonl = report.renderJsonl();
        assertTrue(jsonl.contains("\"kind\":\"config\""));
        assertTrue(jsonl.contains("\"kind\":\"permission\""));
        assertTrue(jsonl.contains("\"outcome\":\"CONFLICT\""));
        assertTrue(jsonl.contains("\"outcome\":\"UNSUPPORTED\""));
    }

    private static EssentialsConfigReport.ConfigEntry findConfig(EssentialsConfigReport report, String sourceKey) {
        return report.configEntries().stream()
                .filter(e -> e.sourceKey().equals(sourceKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing config entry: " + sourceKey));
    }

    private static EssentialsConfigReport.PermissionEntry findPerm(EssentialsConfigReport report, String essNode) {
        return report.permissionEntries().stream()
                .filter(p -> p.essentialsNode().equals(essNode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing permission entry: " + essNode));
    }
}
