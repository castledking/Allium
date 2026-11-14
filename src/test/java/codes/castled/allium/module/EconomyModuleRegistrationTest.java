package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.database.DatabaseManager;
import codes.castled.allium.database.EconomyTable;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import codes.castled.allium.util.AlliumLogger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EconomyModuleRegistrationTest {
    @TempDir Path directory;

    @Test
    void missingAndFalseFlagNeverTouchesVaultEvenWhenVaultPluginIsPresent() throws Exception {
        for (Boolean flag : new Boolean[]{null, false}) {
            try (var fixture = new Fixture(flag, true, false)) {
                fixture.module.onEnable();
                fixture.module.onDisable();
                verifyNoInteractions(fixture.plugins, fixture.services);
                assertEquals(0, fixture.loader.vaultLoads, "default startup must not link optional Vault classes");
            }
        }
    }

    @Test
    void explicitFlagWithoutVaultNeverLoadsApiOrRegisters() throws Exception {
        try (var fixture = new Fixture(true, false, false)) {
            fixture.module.onEnable();
            fixture.module.onDisable();
            verify(fixture.plugins).getPlugin("Vault");
            verifyNoInteractions(fixture.services);
            assertEquals(0, fixture.loader.vaultLoads);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void explicitFlagAndVaultRegistersWarnsAndUnregistersOnlyItsOwnProvider() throws Exception {
        try (var fixture = new Fixture(true, true, true)) {
            when(fixture.services.getRegistration(any(Class.class))).thenReturn(mock(RegisteredServiceProvider.class));
            fixture.module.onEnable();
            var provider = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(fixture.services).register(any(Class.class), provider.capture(), eq(fixture.plugin), eq(ServicePriority.Highest));
            assertTrue(fixture.loader.loadClass("net.milkbowl.vault.economy.Economy").isInstance(provider.getValue()));
            verify(fixture.logger).warning(contains("UNSAFE economy scaffold"));
            verify(fixture.logger).warning(contains("existing Vault economy provider"));
            fixture.module.onDisable();
            fixture.module.onDisable();
            verify(fixture.services, times(1)).unregister(any(Class.class), same(provider.getValue()));
        }
    }

    /** Registration-only API fixture: no Vault money calls or transaction correctness is claimed. */
    private final class Fixture implements AutoCloseable {
        final PluginStart plugin = mock(PluginStart.class);
        final PluginManager plugins = mock(PluginManager.class);
        final ServicesManager services = mock(ServicesManager.class);
        final AlliumLogger logger = mock(AlliumLogger.class);
        final org.mockito.MockedConstruction<EconomyTable> tables;
        final org.mockito.MockedStatic<Bukkit> bukkit;
        final IsolatedLoader loader;
        final AbstractModule module;

        Fixture(Boolean flag, boolean vaultPlugin, boolean apiPresent) throws Exception {
            var config = new YamlConfiguration();
            if (flag != null) config.set("economy.scaffold-vault-provider-enabled", flag);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getDatabaseManager()).thenReturn(mock(DatabaseManager.class));
            when(plugin.logger()).thenReturn(logger);
            if (vaultPlugin) when(plugins.getPlugin("Vault")).thenReturn(mock(Plugin.class));
            if (apiPresent) {
                Path source = directory.resolve("net/milkbowl/vault/economy/Economy.java");
                Files.createDirectories(source.getParent());
                Files.writeString(source, "package net.milkbowl.vault.economy; public interface Economy {}\n");
                assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "-d", directory.toString(), source.toString()));
            }
            loader = new IsolatedLoader(apiPresent);
            tables = mockConstruction(EconomyTable.class);
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            module = (AbstractModule) loader.loadClass(EconomyModule.class.getName())
                    .getConstructor(PluginStart.class).newInstance(plugin);
        }

        @Override public void close() { bukkit.close(); tables.close(); }
    }

    private final class IsolatedLoader extends ClassLoader {
        private final boolean apiPresent;
        int vaultLoads;
        IsolatedLoader(boolean apiPresent) { super(EconomyModule.class.getClassLoader()); this.apiPresent = apiPresent; }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(EconomyModule.class.getName()) && !name.startsWith("net.milkbowl.vault.")) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    byte[] bytes;
                    if (name.startsWith("net.milkbowl.vault.")) {
                        vaultLoads++;
                        if (!apiPresent) throw new ClassNotFoundException("Vault deliberately absent: " + name);
                        bytes = Files.readAllBytes(directory.resolve(name.replace('.', '/') + ".class"));
                    } else {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            bytes = input.readAllBytes();
                        }
                    }
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException exception) { throw new ClassNotFoundException(name, exception); }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
