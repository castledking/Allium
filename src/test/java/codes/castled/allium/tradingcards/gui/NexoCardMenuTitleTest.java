package codes.castled.allium.tradingcards.gui;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the Nexo 1.29 glyph package move against the real compiled title helper. */
class NexoCardMenuTitleTest {
    @TempDir Path fixture;

    @Test
    void allFourTitlesParseTheirTagsWhenNexoMovesItsGlyphReturnType() throws Exception {
        var sources = new ArrayList<String>();
        write(sources, "com/nexomc/nexo/glyphs/Glyph.java", """
            package com.nexomc.nexo.glyphs;
            public final class Glyph {}
            """);
        write(sources, "com/nexomc/nexo/fonts/FontManager.java", """
            package com.nexomc.nexo.fonts;
            public final class FontManager {
                public com.nexomc.nexo.glyphs.Glyph glyphFromID(String id) {
                    return new com.nexomc.nexo.glyphs.Glyph();
                }
            }
            """);
        write(sources, "com/nexomc/nexo/NexoPlugin.java", """
            package com.nexomc.nexo;
            public final class NexoPlugin {
                public static NexoPlugin instance() { return new NexoPlugin(); }
                public com.nexomc.nexo.fonts.FontManager fontManager() {
                    return new com.nexomc.nexo.fonts.FontManager();
                }
            }
            """);
        write(sources, "com/nexomc/nexo/utils/AdventureUtils.java", """
            package com.nexomc.nexo.utils;
            import net.kyori.adventure.text.Component;
            import net.kyori.adventure.text.minimessage.MiniMessage;
            import net.kyori.adventure.text.minimessage.tag.Tag;
            import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
            public final class AdventureUtils {
                public static final AdventureUtils INSTANCE = new AdventureUtils();
                public MiniMessage getMINI_MESSAGE() {
                    return MiniMessage.builder().tags(TagResolver.resolver(
                        TagResolver.standard(),
                        TagResolver.resolver("shift", (args, ctx) ->
                            Tag.inserting(Component.text("SHIFT[" + args.pop().value() + "]"))),
                        TagResolver.resolver("glyph", (args, ctx) ->
                            Tag.inserting(Component.text("GLYPH[" + args.pop().value() + "]")))
                    )).build();
                }
            }
            """);
        var compilerArgs = new ArrayList<String>();
        compilerArgs.addAll(java.util.List.of("-classpath", System.getProperty("java.class.path"),
            "-d", fixture.toString()));
        compilerArgs.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            compilerArgs.toArray(String[]::new)));

        String helper = "codes.castled.allium.tradingcards.gui.NexoCardMenuTitle";
        URL compiledPlugin = NexoCardMenuTitle.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[]{fixture.toUri().toURL(), compiledPlugin},
                getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("com.nexomc.nexo.") || name.equals(helper)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            var title = loader.loadClass(helper).getDeclaredMethod("title", String.class);
            title.setAccessible(true);
            for (String glyph : java.util.List.of("gui_cards", "gui_card_boosts", "gui_merge", "gui_trade")) {
                Component rendered = (Component) title.invoke(null, glyph);
                assertEquals("SHIFT[-10]GLYPH[" + glyph + "]",
                    PlainTextComponentSerializer.plainText().serialize(rendered));
            }
        }
    }

    private void write(ArrayList<String> sources, String relative, String source) throws Exception {
        Path file = fixture.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        sources.add(file.toString());
    }
}
