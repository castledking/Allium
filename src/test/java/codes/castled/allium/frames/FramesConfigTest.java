package codes.castled.allium.frames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** frames.yml, and the shipped one. */
class FramesConfigTest {

    @Test
    void theShippedFileListsTheCardFramesBestFirstAndFallsBackToSimple() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(new File("src/main/resources/frames.yml"));
        List<String> problems = new ArrayList<>();
        FramesConfig config = FramesConfig.load(yaml, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("simple", config.fallback());
        assertEquals(List.of("fabled", "legendary", "ultimate", "elite", "simple"),
            List.copyOf(config.frames().keySet()));
        assertEquals("allium.frame.fabled", config.frames().get("fabled").permission());
        assertTrue(config.spriteFrames().isEmpty());
    }

    @Test
    void aSpriteFrameDefaultsItsWidthAndPermission() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            frames:
              Epic:
                background: minecraft:gui/sprites/tooltip/epic_background
              auto:
                card: fabled
              nothing: {}
            """);
        List<String> problems = new ArrayList<>();
        FramesConfig config = FramesConfig.load(yaml, problems);
        var epic = config.frames().get("epic");
        assertTrue(epic.sprite());
        assertEquals(200, epic.width());
        assertEquals("allium.frame.epic", epic.permission());
        // 'auto' is the marker's own word, and an empty entry draws nothing.
        assertFalse(config.frames().containsKey("auto"));
        assertFalse(config.frames().containsKey("nothing"));
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void autoInTextIsSwappedForAFrameName() {
        FrameService frames = FrameService.get();
        String hover = "&8[frame:auto]\n&7Rank";
        // No player and no frames.yml loaded: the fallback.
        assertEquals("&8[frame:simple]\n&7Rank", frames.substituteAuto(hover, null));
        assertEquals("[frame:simple]", frames.substituteAuto("<frame:auto>", null));
        assertEquals("[frame:simple]", frames.substituteAuto("[frame]", null));
        assertEquals("[frame:fabled]", frames.substituteAuto("[frame:fabled]", null));
    }
}
