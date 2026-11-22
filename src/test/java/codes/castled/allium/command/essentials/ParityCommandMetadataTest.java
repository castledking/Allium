package codes.castled.allium.command.essentials;

import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParityCommandMetadataTest {

    @Test
    void bukkitCanRegisterTheNewSpecsAndAliases() throws Exception {
        List<CommandSpec> specs = new ArrayList<>(List.of(
                CoreCommandSpecs.BROADCASTWORLD, CoreCommandSpecs.REALNAME, CoreCommandSpecs.REPLY_TOGGLE,
                BlockToolCommand.BREAK, BlockToolCommand.SPAWNER, TreeCommand.TREE, TreeCommand.BIG_TREE,
                RemoveCommand.SPEC, BookCommand.SPEC, EditSignCommand.SPEC, CondenseCommand.SPEC,
                UnlimitedCommand.SPEC, ThunderCommand.SPEC, CoreCommandSpecs.POWERTOOL,
                CoreCommandSpecs.CLEAR_CONFIRM_TOGGLE, CoreCommandSpecs.SPEED, CoreCommandSpecs.WEATHER));
        specs.addAll(CoreCommandSpecs.UTILITY_SPECS);
        for (var action : PlayerUtilityCommand.Action.values()) {
            specs.add(action.spec());
        }
        try (var resource = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(resource);
            var metadata = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
            for (CommandSpec spec : specs) {
                String path = "commands." + spec.canonicalName();
                assertTrue(metadata.isConfigurationSection(path), path);
                assertEquals(spec.basePermission(), metadata.getString(path + ".permission"), path);
                assertTrue(metadata.getStringList(path + ".aliases").containsAll(spec.aliases()), path + ".aliases");
            }
        }
    }
}
