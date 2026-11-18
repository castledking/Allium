package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomySettingsInputTest {
    @Test void quotedConfigDecimalsAreExactAndPlainNumbersAreReadByTheirDigits(){
        var plugin=mock(PluginStart.class);var config=new YamlConfiguration();when(plugin.getConfig()).thenReturn(config);
        var logger=mock(codes.castled.allium.util.AlliumLogger.class);when(plugin.logger()).thenReturn(logger);
        assertEquals(10000,EconomyRuntime.readSettings(plugin).starting().minor());
        // Legacy configs wrote "starting-balance: 100.0"; refusing it disabled the whole economy.
        config.set("economy.starting-balance",1.25);assertEquals(125,EconomyRuntime.readSettings(plugin).starting().minor());
        config.set("economy.starting-balance",100.0);assertEquals(10000,EconomyRuntime.readSettings(plugin).starting().minor());
        config.set("economy.starting-balance",500);assertEquals(50000,EconomyRuntime.readSettings(plugin).starting().minor());
        verify(logger,atLeastOnce()).warning(contains("Quote it"));
        config.set("economy.starting-balance",0.001);assertThrows(ArithmeticException.class,()->EconomyRuntime.readSettings(plugin),"more than two decimals stays refused");
        config.set("economy.starting-balance",java.util.List.of(1));assertThrows(IllegalArgumentException.class,()->EconomyRuntime.readSettings(plugin));
        config.set("economy.starting-balance","1.2300");assertEquals(123,EconomyRuntime.readSettings(plugin).starting().minor());
        config.set("economy.starting-balance","1.001");assertThrows(ArithmeticException.class,()->EconomyRuntime.readSettings(plugin));
    }
    @Test void allCommandFamiliesBelongToEconomyAndHaveExplicitPermissions(){
        assertEquals(5,CoreCommandSpecs.ECONOMY_SPECS.size());
        for(var spec:CoreCommandSpecs.ECONOMY_SPECS){assertEquals("economy",spec.moduleName());assertEquals("allium."+spec.canonicalName(),spec.basePermission());}
    }
}
