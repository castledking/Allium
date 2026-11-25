package codes.castled.curios;

import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrameAuthoringAndChoiceTest {

    @TempDir
    Path tempDir;

    private static YamlConfiguration bundledConfig() throws IOException {
        try (var in = FrameAuthoringAndChoiceTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "bundled config.yml must exist");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Player mockPlayer(UUID uuid, String... permissions) {
        Player player = mock(Player.class);
        Set<String> allowed = Set.of(permissions);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.hasPermission(anyString())).thenAnswer(inv -> allowed.contains(inv.getArgument(0)));
        return player;
    }

    private static void writeSyntheticSprite(Path packRoot, String namespace, String relativePath) throws IOException {
        Path png = packRoot.resolve("assets").resolve(namespace).resolve("textures").resolve(relativePath + ".png");
        Files.createDirectories(png.getParent());
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean border = x < 3 || x >= 13 || y < 3 || y >= 13;
                img.setRGB(x, y, border ? 0xFFD4A04A : 0xCC1E1428);
            }
        }
        ImageIO.write(img, "png", png.toFile());
        String mcmeta = """
                {
                  "gui": {
                    "scaling": {
                      "type": "nine_slice",
                      "width": 16,
                      "height": 16,
                      "border": 3
                    }
                  }
                }
                """;
        Files.writeString(Path.of(png + ".mcmeta"), mcmeta, StandardCharsets.UTF_8);
    }

    @Test
    void customFramesConfigParsesAliasesSpritesAndRejectsInvalidEntries() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pack", "packs/custom");
        yaml.set("fallback-frame", "elite");
        yaml.set("frames.vip_gold.card", "legendary");
        yaml.set("frames.vip_gold.permission", "allium.frame.vip_gold");
        yaml.set("frames.staff_badge.parent", "diamond");
        yaml.set("frames.royal_banner.background", "custom:gui/royal_bg");
        yaml.set("frames.royal_banner.frame", "custom:gui/royal_frame");
        yaml.set("frames.royal_banner.width", 20); // clamped to MIN_WIDTH
        yaml.set("frames.auto.card", "simple"); // reserved
        yaml.set("frames.off.card", "simple"); // reserved
        yaml.set("frames.empty_entry.width", 180); // missing card/parent/sprites

        List<String> problems = new ArrayList<>();
        CustomFramesConfig config = CustomFramesConfig.load(yaml, problems);

        assertEquals("packs/custom", config.pack());
        assertEquals("elite", config.fallback());
        assertEquals(3, config.frames().size());

        var vip = config.frames().get("vip_gold");
        assertTrue(vip.isAlias());
        assertEquals("legendary", vip.aliasTarget());
        assertEquals("allium.frame.vip_gold", vip.permission());

        var staff = config.frames().get("staff_badge");
        assertTrue(staff.isAlias());
        assertEquals("diamond", staff.aliasTarget());
        assertEquals("allium.frame.staff_badge", staff.permission());

        var royal = config.frames().get("royal_banner");
        assertTrue(royal.isSprite());
        assertEquals(CustomFramesConfig.MIN_WIDTH, royal.width());
        assertEquals(1, config.spriteFrames().size());

        assertEquals(3, problems.size(), "reserved and empty entries should produce problems: " + problems);
    }

    @Test
    void customFrameBuilderCutsSpritesIntoFontAndPanelsDrawThem() throws Exception {
        Path packRoot = tempDir.resolve("pack");
        writeSyntheticSprite(packRoot, "custom", "gui/royal_bg");
        writeSyntheticSprite(packRoot, "custom", "gui/royal_frame");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pack", packRoot.toString());
        yaml.set("frames.royal_banner.background", "custom:gui/royal_bg");
        yaml.set("frames.royal_banner.frame", "custom:gui/royal_frame");
        yaml.set("frames.royal_banner.width", 160);
        yaml.set("frames.vip_gold.card", "legendary");
        yaml.set("frames.staff_diamond.parent", "diamond");

        List<String> problems = new ArrayList<>();
        CustomFramesConfig config = CustomFramesConfig.load(yaml, problems);
        assertTrue(problems.isEmpty(), () -> "Unexpected problems: " + problems);

        File manifestFile = tempDir.resolve(FrameRegistry.MANIFEST_FILE).toFile();
        CustomFrameBuilder.Result built = CustomFrameBuilder.build(config, packRoot.toFile(), manifestFile);
        assertEquals(1, built.frames());
        assertTrue(built.glyphs() > 0);
        assertTrue(built.problems().isEmpty(), () -> "Unexpected build problems: " + built.problems());
        assertTrue(manifestFile.isFile());
        assertTrue(Files.isRegularFile(packRoot.resolve("assets/curios/font/custom_frames.json")));

        var manifest = JsonParser.parseString(Files.readString(manifestFile.toPath())).getAsJsonObject();
        var panels = FramePanels.bundled().withCustom(manifest, java.util.Map.of(
                "vip_gold", "legendary",
                "staff_diamond", "diamond",
                "loop_a", "loop_b",
                "loop_b", "loop_a"
        ));

        assertTrue(panels.draws("royal_banner"));
        var royalPanel = panels.panel("royal_banner", null, 4, 100, 0);
        assertNotNull(royalPanel);
        assertEquals(CustomFrameBuilder.CUSTOM_FONT, royalPanel.font());
        assertEquals(4, royalPanel.prefixes().size());

        assertTrue(panels.draws("vip_gold"));
        var vipPanel = panels.panel("vip_gold", null, 3, 120, 0);
        assertNotNull(vipPanel);
        assertEquals("curios:card_panel", vipPanel.font());

        assertTrue(panels.draws("staff_diamond"));
        var staffPanel = panels.panel("staff_diamond", null, 3, 100, 0);
        assertNotNull(staffPanel);
        assertEquals("curios:frames", staffPanel.font());

        assertFalse(panels.draws("loop_a"), "Cyclic aliases must not loop or claim to be drawable");
        assertTrue(panels.draws("diamond"), "Bundled frames stay intact");
    }

    @Test
    void customFrameSpriteRejectsPathTraversal() {
        assertThrows(IOException.class,
                () -> CustomFrameSprite.resolveTextureFile(tempDir.toFile(), "custom:../../etc/passwd"));
        assertThrows(IOException.class,
                () -> CustomFrameSprite.resolveTextureFile(tempDir.toFile(), "bad/ns:gui/test"));
    }

    @Test
    void frameRegistryEnforcesPrecedencePersistenceAndPermissionRevocation() throws Exception {
        Path dataDir = tempDir.resolve("curios-data");
        Files.createDirectories(dataDir);
        Path packRoot = tempDir.resolve("custom-pack");
        writeSyntheticSprite(packRoot, "custom", "gui/crest");

        String framesYml = """
                pack: %s
                fallback-frame: elite
                frames:
                  crest:
                    frame: custom:gui/crest
                    width: 140
                    permission: allium.frame.crest
                  vip_alias:
                    parent: diamond
                    permission: allium.frame.vip_alias
                  unbuilt_only:
                    frame: custom:gui/missing
                    permission: allium.frame.unbuilt_only
                """.formatted(packRoot.toString().replace("\\", "/"));
        Files.writeString(dataDir.resolve(CustomFramesConfig.FILE), framesYml, StandardCharsets.UTF_8);

        // Build only the valid custom sprite (unbuilt_only will fail to build and stay reported).
        List<String> loadProblems = new ArrayList<>();
        CustomFramesConfig cfg = CustomFramesConfig.load(
                YamlConfiguration.loadConfiguration(dataDir.resolve(CustomFramesConfig.FILE).toFile()), loadProblems);
        CustomFrameBuilder.Result built = CustomFrameBuilder.build(
                cfg, packRoot.toFile(), dataDir.resolve(FrameRegistry.MANIFEST_FILE).toFile());
        assertEquals(1, built.frames());
        assertEquals(1, built.problems().size());

        YamlConfiguration config = bundledConfig();
        FrameRegistry registry = new FrameRegistry(dataDir.toFile());
        List<String> problems = registry.reload(config.getConfigurationSection("frames"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unbuilt_only")), "Should report unbuilt custom frame");
        assertTrue(registry.isDrawable("crest"));
        assertTrue(registry.isDrawable("vip_alias"));
        assertFalse(registry.isDrawable("unbuilt_only"));

        UUID aliceId = UUID.randomUUID();

        // 1. No permissions -> configured fallback ('elite')
        Player noPerms = mockPlayer(aliceId);
        var fallbackRes = registry.resolve(noPerms);
        assertEquals("elite", fallbackRes.frame());
        assertEquals(FrameRegistry.Source.FALLBACK, fallbackRes.source());

        // 2. Custom frame permission takes priority ahead of bundled permissions
        Player withPerms = mockPlayer(aliceId, "allium.frame.aqua", "allium.frame.vip_alias", "allium.frame.diamond");
        var permRes = registry.resolve(withPerms);
        assertEquals("vip_alias", permRes.frame());
        assertEquals(FrameRegistry.Source.PERMISSION_DEFAULT, permRes.source());

        // 3. Player choice overrides permission default when player has permission
        registry.setChoice(aliceId, "aqua");
        var choiceRes = registry.resolve(withPerms);
        assertEquals("aqua", choiceRes.frame());
        assertEquals(FrameRegistry.Source.PLAYER_CHOICE, choiceRes.source());

        // 4. Revoking permission for the chosen frame gracefully falls back to permission default
        Player revokedAqua = mockPlayer(aliceId, "allium.frame.diamond");
        var revokedRes = registry.resolve(revokedAqua);
        assertEquals("diamond", revokedRes.frame());
        assertEquals(FrameRegistry.Source.PERMISSION_DEFAULT, revokedRes.source());

        // 5. Admin override beats both player choice and permissions (even without frame permission)
        registry.setOverride(aliceId, "crest");
        var overrideRes = registry.resolve(noPerms);
        assertEquals("crest", overrideRes.frame());
        assertEquals(FrameRegistry.Source.ADMIN_OVERRIDE, overrideRes.source());

        // 6. Reload into a brand-new FrameRegistry preserves overrides and choices from disk
        FrameRegistry reopened = new FrameRegistry(dataDir.toFile());
        reopened.reload(config.getConfigurationSection("frames"));
        assertEquals("crest", reopened.overrideFor(aliceId));
        assertEquals("aqua", reopened.choiceFor(aliceId));
        assertEquals("crest", reopened.resolve(withPerms).frame());

        // 7. Clearing admin override restores the player's saved choice
        reopened.setOverride(aliceId, null);
        var afterOverrideClear = reopened.resolve(withPerms);
        assertEquals("aqua", afterOverrideClear.frame());
        assertEquals(FrameRegistry.Source.PLAYER_CHOICE, afterOverrideClear.source());

        // 8. Clearing player choice restores permission default
        reopened.setChoice(aliceId, null);
        var afterChoiceClear = reopened.resolve(withPerms);
        assertEquals("vip_alias", afterChoiceClear.frame());
        assertEquals(FrameRegistry.Source.PERMISSION_DEFAULT, afterChoiceClear.source());
    }
}
