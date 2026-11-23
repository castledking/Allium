package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.AddonRefusedException;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlliumAddonDirectoryTest {

    @TempDir
    Path tempDir;

    private static Path writePluginJar(Path jarPath, String pluginYml) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jarPath;
    }

    private static PluginStart mockAllium(Path dataFolder, PluginManager pm) {
        PluginStart allium = mock(PluginStart.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        when(allium.getDataFolder()).thenReturn(dataFolder.toFile());
        when(allium.getServer()).thenReturn(server);
        when(allium.logger()).thenReturn(logger);
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getServicesManager()).thenReturn(services);
        when(pm.getPlugins()).thenReturn(new Plugin[]{allium});
        return allium;
    }

    @Test
    void loadsValidAddonJarFromAddonsDirectoryAndDisablesOnShutdown() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        Path addonsDir = dataFolder.resolve("addons");
        String curiosYml = """
                name: Curios
                main: codes.castled.curios.CuriosPlugin
                version: '1.0.0'
                depend: [Allium]
                allium-api: '^1.1'
                allium-addon-dir: true
                """;
        Path jar = writePluginJar(addonsDir.resolve("Addon-Curios.jar"), curiosYml);

        PluginManager pm = mock(PluginManager.class);
        PluginStart allium = mockAllium(dataFolder, pm);
        Plugin curios = mock(Plugin.class);
        when(curios.getName()).thenReturn("Curios");
        when(curios.getResource("plugin.yml")).thenAnswer(inv -> new ByteArrayInputStream(curiosYml.getBytes(StandardCharsets.UTF_8)));
        when(curios.isEnabled()).thenReturn(true);
        when(pm.loadPlugin(jar.toFile())).thenReturn(curios);

        AlliumApiService service = new AlliumApiService(allium);
        service.install();

        assertTrue(Files.isDirectory(addonsDir));
        verify(pm).loadPlugin(jar.toFile());
        verify(curios).onLoad();
        verify(pm).enablePlugin(curios);
        assertEquals(AddonVerdict.Status.ACCEPTED, service.verdicts().get("Curios").status());

        service.closeAll();
        verify(pm).disablePlugin(curios);
    }

    @Test
    void ignoresNonAddonAndRefusesMismatchedJarsWithoutCallingLoadPlugin() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        Path addonsDir = dataFolder.resolve("addons");
        writePluginJar(addonsDir.resolve("RandomPlugin.jar"), """
                name: RandomPlugin
                main: example.RandomPlugin
                version: '1.0.0'
                """);
        writePluginJar(addonsDir.resolve("FutureAddon.jar"), """
                name: FutureAddon
                main: example.FutureAddon
                version: '1.0.0'
                allium-api: '^2.0'
                """);

        PluginManager pm = mock(PluginManager.class);
        PluginStart allium = mockAllium(dataFolder, pm);

        AlliumApiService service = new AlliumApiService(allium);
        service.install();

        verify(pm, never()).loadPlugin(any(File.class));
        assertFalse(service.verdicts().containsKey("RandomPlugin"));
        assertEquals(AddonVerdict.Status.VERSION_MISMATCH, service.verdicts().get("FutureAddon").status());
    }

    @Test
    void refusesAddonRequiringAddonsDirectoryWhenLoadedFromPluginsFolder() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        String curiosYml = """
                name: Curios
                main: codes.castled.curios.CuriosPlugin
                version: '1.0.0'
                depend: [Allium]
                allium-api: '^1.1'
                allium-addon-dir: true
                """;
        Plugin curios = mock(Plugin.class);
        when(curios.getName()).thenReturn("Curios");
        when(curios.getResource("plugin.yml")).thenAnswer(inv -> new ByteArrayInputStream(curiosYml.getBytes(StandardCharsets.UTF_8)));
        when(curios.isEnabled()).thenReturn(true);

        PluginManager pm = mock(PluginManager.class);
        PluginStart allium = mockAllium(dataFolder, pm);
        when(pm.getPlugins()).thenReturn(new Plugin[]{allium, curios});
        when(pm.getPlugin("Curios")).thenReturn(curios);

        // Also place a duplicate jar in addons/ to confirm it is skipped while plugins/ copy is loaded.
        writePluginJar(dataFolder.resolve("addons").resolve("Addon-Curios.jar"), curiosYml);

        AlliumApiService service = new AlliumApiService(allium);
        service.install();

        AddonVerdict verdict = service.verdicts().get("Curios");
        assertNotNull(verdict);
        assertEquals(AddonVerdict.Status.WRONG_DIRECTORY, verdict.status());
        assertTrue(verdict.refused());
        verify(pm, never()).loadPlugin(any(File.class));
        AddonRefusedException refused = assertThrows(AddonRefusedException.class, () -> service.connect(curios));
        assertTrue(refused.getMessage().contains("plugins/Allium/addons/"), refused.getMessage());
    }

    @Test
    void refusesShadedApiJarInAddonsDirectoryDuringPreflightWithoutCallingLoadPlugin() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        Path addonsDir = dataFolder.resolve("addons");
        Path shadedJar = addonsDir.resolve("Addon-Shaded.jar");
        Files.createDirectories(addonsDir);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(shadedJar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write("""
                    name: ShadedAddon
                    main: example.ShadedAddon
                    version: '1.0.0'
                    allium-api: '^1.1'
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("codes/castled/allium/api/AlliumApi.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }

        PluginManager pm = mock(PluginManager.class);
        PluginStart allium = mockAllium(dataFolder, pm);

        AlliumApiService service = new AlliumApiService(allium);
        service.install();

        verify(pm, never()).loadPlugin(any(File.class));
        assertNotNull(service.verdicts().get("ShadedAddon"));
        assertEquals(AddonVerdict.Status.SHADED_API, service.verdicts().get("ShadedAddon").status());
    }

    @Test
    void curiosPluginYmlRequiresAlliumAndAddonsDirectory() throws Exception {
        Path pluginYml = Path.of("addons/curios/src/main/resources/plugin.yml");
        String content = Files.readString(pluginYml);
        assertTrue(content.contains("depend: [Allium]"), "Curios must require Allium");
        assertTrue(content.contains("allium-api:"), "Curios must declare allium-api");
        assertTrue(content.contains("allium-addon-dir: true"), "Curios must require plugins/Allium/addons/");
    }
}
