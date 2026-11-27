package codes.castled.allium;

import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupHeaderTest {

    @Test
    void bundledStartupsTxtParsesAllThreeEntries() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("startups.txt")) {
            assertNotNull(in, "startups.txt must be bundled in resources");
            Map<String, List<String>> parsed = StartupHeader.parseStartups(in);
            assertEquals(1, parsed.size());
            List<String> entries = parsed.get("p");
            assertNotNull(entries);
            assertEquals(3, entries.size(), "All three GP3D ASCII headers (p1, p2, p3) should be preserved");
            for (String entry : entries) {
                assertTrue(entry.contains("${version}"), "Header should include ${version}");
                assertTrue(entry.contains("${startup}"), "Header should include ${startup}");
            }
        }
    }

    @Test
    void rendersVersionStartupBlockAndAlignedDetailColumns() throws Exception {
        StartupHeader.StartupInfo info = StartupHeader.StartupInfo.builder()
                .locale("en_US")
                .version("1.0.2")
                .authorTag("&7by castledking")
                .pluginTag("&dThe modern essentials suite for Minecraft servers")
                .startupDetailsHeader("&fStartup details: ")
                .bootFinished("&7Boot finished in: &a42ms")
                .hexColorSupported(true)
                .addRow("Platform:", "&aPaper 1.20.6", "Runtime:", "&aModern (Java 21)")
                .addRow("Modules:", "&a22/22", "Commands:", "&a180")
                .addRow("Storage:", "&aSQLITE", "Addons:", "&a0")
                .build();

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("startups.txt")) {
            String rendered = StartupHeader.renderRandom(in, info, new Random(0));
            assertNotNull(rendered);
            assertFalse(rendered.contains("${version}"));
            assertFalse(rendered.contains("${startup}"));
            assertTrue(rendered.contains("v1.0.2"));
            assertTrue(rendered.contains("\u00A77by castledking"));
            assertTrue(rendered.contains("\u00A7dThe modern essentials suite for Minecraft servers"));
            assertTrue(rendered.contains("\u00A7fStartup details: "));
            assertTrue(rendered.contains("Platform: \u00A7aPaper 1.20.6"));
            assertTrue(rendered.contains("Runtime: \u00A7aModern (Java 21)"));
            assertTrue(rendered.contains("Modules: \u00A7a22/22"));
            assertTrue(rendered.contains("Boot finished in: \u00A7a42ms"));
        }
    }

    @Test
    void selectsLocaleSpecificEntryBeforeBaseLanguageOrFallback() {
        String custom = "es1[\n&aHola v${version}\n${startup}\n]\np1[\n&bFallback v${version}\n${startup}\n]\n";
        StartupHeader.StartupInfo esInfo = StartupHeader.StartupInfo.builder()
                .locale("es_ES")
                .version("1.0.2")
                .bootFinished("&7Listo")
                .build();
        String renderedEs = StartupHeader.renderRandom(
                new ByteArrayInputStream(custom.getBytes(StandardCharsets.UTF_8)), esInfo, new Random(0));
        assertNotNull(renderedEs);
        assertTrue(renderedEs.contains("\u00A7aHola v1.0.2"));

        StartupHeader.StartupInfo frInfo = StartupHeader.StartupInfo.builder()
                .locale("fr_FR")
                .version("1.0.2")
                .bootFinished("&7Prêt")
                .build();
        String renderedFr = StartupHeader.renderRandom(
                new ByteArrayInputStream(custom.getBytes(StandardCharsets.UTF_8)), frInfo, new Random(0));
        assertNotNull(renderedFr);
        assertTrue(renderedFr.contains("v1.0.2"));
    }

    @Test
    void translatesHexColorsOnModernAndStripsOnLegacy() {
        String raw = "&#AA55FFHello #00FF88World &aOK $bFine";
        String modern = StartupHeader.translateColors(raw, true);
        assertTrue(modern.contains("\u00A7x\u00A7a\u00A7a\u00A75\u00A75\u00A7f\u00A7fHello"));
        assertTrue(modern.contains("\u00A7x\u00A70\u00A70\u00A7f\u00A7f\u00A78\u00A78World"));
        assertTrue(modern.contains("\u00A7aOK \u00A7bFine"));

        String legacy = StartupHeader.translateColors(raw, false);
        assertEquals("Hello World \u00A7aOK \u00A7bFine", legacy);
    }

    @Test
    void displaySendsRenderedHeaderToConsoleSender() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getConsoleSender()).thenReturn(console);
        when(plugin.getResource("startups.txt")).thenAnswer(
                inv -> getClass().getClassLoader().getResourceAsStream("startups.txt"));

        StartupHeader.display(plugin, StartupHeader.StartupInfo.builder()
                .version("1.0.2")
                .bootFinished("&7Boot finished in: &a12ms")
                .addRow("Platform:", "&aPaper 1.20.6")
                .build());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(console, atLeastOnce()).sendMessage(sent.capture());
        List<String> lines = sent.getAllValues();
        assertTrue(lines.size() > 1, "Startup header should be sent line-by-line so console serializers preserve per-line colors");
        for (String line : lines) {
            assertFalse(line.contains("\n"), "Each console.sendMessage call should be a single line");
        }
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("v1.0.2"));
        assertTrue(joined.contains("12ms"));
    }

    @Test
    void firstHeaderPreservesColorCodeOnEveryBannerLine() throws Exception {
        StartupHeader.StartupInfo info = StartupHeader.StartupInfo.builder()
                .locale("en_US")
                .version("1.0.2")
                .bootFinished("&7Boot finished in: &a12ms")
                .build();

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("startups.txt")) {
            assertNotNull(in);
            // Fixed Random whose first nextInt(3) returns 0 selects p1
            String rendered = StartupHeader.renderRandom(in, info, new Random() {
                @Override
                public int nextInt(int bound) {
                    return 0;
                }
            });
            assertNotNull(rendered);
            String[] lines = StartupHeader.splitLines(rendered);
            assertTrue(lines[1].startsWith("\u00A7d▄████▄"), "Line 1 of p1 should start with §d: " + lines[1]);
            assertTrue(lines[2].startsWith("\u00A7d██▄▄██"), "Line 2 of p1 should start with §d: " + lines[2]);
            assertTrue(lines[3].startsWith("\u00A7d██  ██"), "Line 3 of p1 should start with §d: " + lines[3]);
        }
    }

    @Test
    void startupHeaderClassesCompileToJava8Bytecode() throws Exception {
        for (Class<?> clazz : new Class<?>[]{
                StartupHeader.class,
                StartupHeader.DetailRow.class,
                StartupHeader.StartupInfo.class,
                StartupHeader.StartupInfo.Builder.class
        }) {
            String path = clazz.getName().replace('.', '/') + ".class";
            try (InputStream in = clazz.getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "Missing classfile for " + clazz.getName());
                DataInputStream dis = new DataInputStream(in);
                assertEquals(0xCAFEBABE, dis.readInt());
                dis.readUnsignedShort(); // minor
                int major = dis.readUnsignedShort();
                assertTrue(major <= 52, clazz.getName() + " must be Java 8 bytecode (<= 52), got " + major);
            }
        }
    }
}
