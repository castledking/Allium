package codes.castled.allium.managers.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;

import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.INFO;
import static codes.castled.allium.managers.core.Text.DebugSeverity.WARN;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swaps Nexo glyph placeholders (":tada:", "🎉", ...) for the glyph characters inside
 * components Allium assembles itself - currently the Discord to in-game relay.
 *
 * <p>Nexo itself only touches glyphs in two places: it escapes the ones a player lacks permission
 * for on {@code AsyncChatDecorateEvent}, and it rewrites placeholders/tags into glyph characters
 * while chat packets are on the wire. Messages we build and send ourselves do not reliably survive
 * that packet pass (other chat packet consumers on the pipeline can re-send the untransformed
 * component), so the placeholder reaches the client as literal text. Resolving it up front makes
 * the glyph stick regardless of what happens further down.
 *
 * <p>Only placeholders are resolved, never {@code <glyph:id>} tags, so remote chat cannot pull in
 * arbitrary glyphs. Everything is reflective because Nexo is an optional dependency whose internals
 * move between releases; anything unexpected leaves the component untouched and says so in the log,
 * since a silent no-op here is indistinguishable from Nexo simply having nothing to replace.
 */
public final class NexoGlyphSupport {

    private static final long CACHE_TTL_MS = 60_000L;

    private static volatile List<GlyphPlaceholder> cachedGlyphs = List.of();
    private static volatile long cachedAt;
    private static volatile boolean unsupported;
    private static volatile String lastStatus = "";

    private NexoGlyphSupport() {
    }

    /**
     * Returns the component with every Nexo glyph placeholder replaced by its glyph, or the
     * component unchanged when Nexo is absent or has nothing to contribute.
     */
    public static Component resolvePlaceholders(Component component) {
        if (component == null || unsupported) {
            return component;
        }

        List<GlyphPlaceholder> glyphs = glyphs();
        if (glyphs.isEmpty()) {
            return component;
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        if (plain.isEmpty()) {
            return component;
        }

        Component result = component;
        for (GlyphPlaceholder glyph : glyphs) {
            for (TextReplacementConfig replacement : glyph.replacementsFor(plain)) {
                result = result.replaceText(replacement);
            }
        }
        return result;
    }

    /** Drops the cached glyph snapshot so the next lookup re-reads Nexo (used after /nexo reload). */
    public static void invalidate() {
        cachedAt = 0L;
    }

    private static List<GlyphPlaceholder> glyphs() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_TTL_MS) {
            return cachedGlyphs;
        }

        List<GlyphPlaceholder> loaded = load();
        cachedGlyphs = loaded;
        cachedAt = now;
        return loaded;
    }

    private static List<GlyphPlaceholder> load() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            status("Nexo is not enabled; glyph placeholders will be left as typed");
            return List.of();
        }

        try {
            Class<?> pluginClass = Class.forName("com.nexomc.nexo.NexoPlugin");
            Class<?> fontManagerClass = Class.forName("com.nexomc.nexo.fonts.FontManager");
            Class<?> glyphClass = Class.forName("com.nexomc.nexo.glyphs.Glyph");
            Method placeholdersMethod = glyphClass.getMethod("getPlaceholders");
            Method componentMethod = glyphClass.getMethod("getComponent");
            Method configMethod = findMethod(glyphClass, "getPlaceholderConfig");

            Object nexo = pluginClass.getMethod("instance").invoke(null);
            Object fontManager = pluginClass.getMethod("fontManager").invoke(nexo);

            // Nexo indexes every placeholder against its glyph, which is exactly the lookup we
            // want; glyphs() is the fallback for versions without that map.
            Collection<?> registered = asCollection(
                    invokeIfPresent(fontManagerClass, "getPlaceholderGlyphMap", fontManager));
            String source = "placeholder map";
            if (registered.isEmpty()) {
                registered = asCollection(fontManagerClass.getMethod("glyphs").invoke(fontManager));
                source = "glyph list";
            }

            List<GlyphPlaceholder> loaded = new ArrayList<>();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int skipped = 0;
            for (Object glyph : registered) {
                // A glyph appears once per placeholder in the map, and one entry already covers
                // all of them.
                if (glyph == null || !seen.add(glyph)) {
                    continue;
                }

                // Per-glyph, because these accessors are Kotlin lazies that build components on
                // first touch - one glyph blowing up must not cost us all the others. A null result
                // just means the glyph has no placeholders, which is the common case.
                try {
                    GlyphPlaceholder entry = read(glyph, placeholdersMethod, configMethod, componentMethod);
                    if (entry != null) {
                        loaded.add(entry);
                    }
                } catch (Throwable t) {
                    skipped++;
                    Text.sendDebugLog(WARN, "[NexoGlyphs] Skipping a glyph Nexo could not describe: " + t, true);
                }
            }

            status("Loaded " + loaded.size() + " glyph(s) with placeholders from Nexo's " + source
                    + " of " + registered.size() + " entr(ies)"
                    + (skipped > 0 ? ", " + skipped + " unreadable" : ""));
            return List.copyOf(loaded);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            unsupported = true;
            Text.sendDebugLog(WARN, "[NexoGlyphs] Nexo is installed but its glyph API is not what we expect ("
                    + e + "); placeholders will be left as typed", true);
            return List.of();
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "[NexoGlyphs] Failed to read Nexo glyphs: " + t, true);
            return List.of();
        }
    }

    /**
     * Reads one glyph into a placeholder entry, or {@code null} when it declares no placeholders.
     * Nexo's own replacement config carries its exact styling, so it is preferred; a literal match
     * on the glyph component is the fallback if that accessor ever moves.
     */
    private static GlyphPlaceholder read(Object glyph, Method placeholdersMethod, Method configMethod,
                                         Method componentMethod) throws Exception {
        List<String> literals = placeholdersOf(placeholdersMethod, glyph);
        if (literals.isEmpty()) {
            return null;
        }

        if (configMethod != null && configMethod.invoke(glyph) instanceof TextReplacementConfig config) {
            return new GlyphPlaceholder(literals, List.of(config));
        }

        if (!(componentMethod.invoke(glyph) instanceof Component glyphComponent)) {
            throw new IllegalStateException("glyph for " + literals.get(0) + " has no component");
        }

        List<TextReplacementConfig> replacements = new ArrayList<>(literals.size());
        for (String literal : literals) {
            replacements.add(TextReplacementConfig.builder()
                    .matchLiteral(literal)
                    .replacement(glyphComponent)
                    .build());
        }
        return new GlyphPlaceholder(literals, List.copyOf(replacements));
    }

    private static List<String> placeholdersOf(Method placeholdersMethod, Object glyph) throws Exception {
        if (!(placeholdersMethod.invoke(glyph) instanceof Collection<?> placeholders) || placeholders.isEmpty()) {
            return List.of();
        }
        List<String> literals = new ArrayList<>(placeholders.size());
        for (Object placeholder : placeholders) {
            if (placeholder != null && !placeholder.toString().isEmpty()) {
                literals.add(placeholder.toString());
            }
        }
        return List.copyOf(literals);
    }

    /** Normalises whatever Nexo hands back - a map of placeholder to glyph, or a plain collection. */
    private static Collection<?> asCollection(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values();
        }
        return value instanceof Collection<?> collection ? collection : List.of();
    }

    private static Object invokeIfPresent(Class<?> owner, String name, Object target) throws Exception {
        Method method = findMethod(owner, name);
        return method == null ? null : method.invoke(target);
    }

    private static Method findMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Logs load outcomes once per distinct message so a broken hook is visible without debug-mode. */
    private static void status(String message) {
        if (message.equals(lastStatus)) {
            return;
        }
        lastStatus = message;
        Text.sendDebugLog(INFO, "[NexoGlyphs] " + message, true);
    }

    private record GlyphPlaceholder(List<String> placeholders, List<TextReplacementConfig> replacements) {

        private List<TextReplacementConfig> replacementsFor(String plain) {
            for (String placeholder : placeholders) {
                if (plain.contains(placeholder)) {
                    return replacements;
                }
            }
            return List.of();
        }
    }
}
