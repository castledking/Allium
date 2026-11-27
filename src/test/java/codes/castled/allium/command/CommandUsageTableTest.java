package codes.castled.allium.command;

import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CommandUsage;
import codes.castled.allium.command.spec.CommandUsages;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * command-usages.tsv carries every command's usage card lines to the legacy Bukkit platform, which
 * cannot load the command specs themselves. This test keeps the file equal to the specs; run it
 * with {@code ALLIUM_WRITE_USAGE_TABLE=true} in the environment to rewrite the file after changing a command.
 */
class CommandUsageTableTest {
    private static final Path FILE = Path.of("src/main/resources/command-usages.tsv");

    @Test
    void theLegacyUsageTableMatchesTheCommandSpecs() throws Exception {
        String expected = render();
        if ("true".equals(System.getenv("ALLIUM_WRITE_USAGE_TABLE"))) Files.writeString(FILE, expected, StandardCharsets.UTF_8);
        assertEquals(expected, Files.readString(FILE, StandardCharsets.UTF_8),
                "command-usages.tsv is out of date; rerun this test with ALLIUM_WRITE_USAGE_TABLE=true");
    }

    static String render() throws Exception {
        Map<String, CommandSpec> specs = new TreeMap<>();
        collect(CoreCommandSpecs.class, specs);
        Path classes = Path.of("build/classes/java/main");
        try (Stream<Path> files = Files.walk(classes.resolve("codes/castled/allium/command"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class") && !f.toString().contains("$")).toList()) {
                String name = classes.relativize(file).toString().replace('/', '.').replace(".class", "");
                collect(Class.forName(name, false, CommandUsageTableTest.class.getClassLoader()), specs);
            }
        }
        StringBuilder out = new StringBuilder("# Usage card lines for the legacy Bukkit platform, written from the command specs\n")
                .append("# by CommandUsageTableTest. C: command, aliases, description. U: syntax, description, permission.\n");
        for (CommandSpec spec : specs.values()) {
            List<String> aliases = new ArrayList<>(spec.bareLabels());
            aliases.remove(spec.canonicalName());
            out.append("C\t").append(clean(spec.canonicalName())).append('\t').append(clean(String.join(",", aliases)))
                    .append('\t').append(clean(spec.description())).append('\n');
            for (CommandUsage usage : CommandUsages.select(spec, spec.selfUsage())) {
                out.append("U\t").append(clean(usage.syntax())).append('\t').append(clean(usage.description()))
                        .append('\t').append(clean(usage.permission() == null ? "" : usage.permission())).append('\n');
            }
        }
        return out.toString();
    }

    private static void collect(Class<?> type, Map<String, CommandSpec> specs) throws IllegalAccessException {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers) || !Modifier.isFinal(modifiers)) continue;
            if (CommandSpec.class.isAssignableFrom(field.getType())) {
                CommandSpec spec = (CommandSpec) field.get(null);
                specs.putIfAbsent(spec.canonicalName(), spec);
            } else if (List.class.isAssignableFrom(field.getType())) {
                for (Object item : (List<?>) field.get(null)) {
                    if (item instanceof CommandSpec spec) specs.putIfAbsent(spec.canonicalName(), spec);
                }
            }
        }
    }

    private static String clean(String value) {
        assertFalse(value.contains("\t") || value.contains("\n"), "tab or newline in usage text: " + value);
        return value;
    }
}
