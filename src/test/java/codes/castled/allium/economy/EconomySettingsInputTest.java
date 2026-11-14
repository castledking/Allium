package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomySettingsInputTest {
    @Test void quotedConfigDecimalsAreExactAndUnquotedDoublesAreRejected(){
        var plugin=mock(PluginStart.class);var config=new YamlConfiguration();when(plugin.getConfig()).thenReturn(config);
        assertEquals(10000,EconomyRuntime.readSettings(plugin).starting().minor());
        config.set("economy.starting-balance",1.25);assertThrows(IllegalArgumentException.class,()->EconomyRuntime.readSettings(plugin));
        config.set("economy.starting-balance","1.2300");assertEquals(123,EconomyRuntime.readSettings(plugin).starting().minor());
        config.set("economy.starting-balance","1.001");assertThrows(ArithmeticException.class,()->EconomyRuntime.readSettings(plugin));
    }
    @Test void allCommandFamiliesBelongToEconomyAndHaveExplicitPermissions(){
        assertEquals(5,CoreCommandSpecs.ECONOMY_SPECS.size());
        for(var spec:CoreCommandSpecs.ECONOMY_SPECS){assertEquals("economy",spec.moduleName());assertEquals("allium."+spec.canonicalName(),spec.basePermission());}
    }
}
