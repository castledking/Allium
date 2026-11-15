package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.database.*;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.command.CommandManager;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EconomyModuleRegistrationTest {
    PluginStart plugin(){
        var p=mock(PluginStart.class);var db=mock(DatabaseManager.class);when(p.getDatabaseManager()).thenReturn(db);when(db.getType()).thenReturn(DatabaseType.SQLITE);
        when(p.getConfig()).thenReturn(new YamlConfiguration());when(p.logger()).thenReturn(mock(AlliumLogger.class));
        var server=mock(Server.class);when(p.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(mock(PluginManager.class));when(server.getServicesManager()).thenReturn(mock(ServicesManager.class));
        when(p.getCommandManager()).thenReturn(mock(CommandManager.class));return p;
    }
    @Test void absentVaultLeavesReadyCommandsAndDoesNotRegisterService()throws Exception {
        var p=plugin();try(var mocked=mockConstruction(EconomyRuntime.class,(m,c)->when(m.ready()).thenReturn(true))){
            var module=new EconomyModule(p);module.onEnable();verifyNoInteractions(p.getServer().getServicesManager());
            module.onDisable();verify(mocked.constructed().get(0)).close();
        }
    }
    @Test void unreadyRecoveryNeverRegistersProviderAndClosesRuntime()throws Exception {
        var p=plugin();try(var mocked=mockConstruction(EconomyRuntime.class)){
            assertThrows(IllegalStateException.class,()->new EconomyModule(p).onEnable());
            verifyNoInteractions(p.getServer().getServicesManager());verify(mocked.constructed().get(0)).close();
        }
    }
    @Test void mysqlIsRefusedEvenWithRetiredScaffoldFlag() {
        var p=plugin();when(p.getDatabaseManager().getType()).thenReturn(DatabaseType.MYSQL);p.getConfig().set("economy.scaffold-vault-provider-enabled",true);
        assertThrows(IllegalStateException.class,()->new EconomyModule(p).onEnable());verifyNoInteractions(p.getServer().getServicesManager());
    }
}
