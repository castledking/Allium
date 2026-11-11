package codes.castled.allium.tool.definition;

import codes.castled.allium.item.ProviderKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates one tool definition document. A definition with any error is rejected whole: it is
 * never half-loaded with defaults standing in for bad values. Every error names the file and the
 * setting's path.
 *
 * <p>Top-level settings every definition shares: {@code schema}, {@code id}, {@code name},
 * {@code behavior}, {@code enabled}, {@code item}, {@code appearance.default},
 * {@code appearance.aliases}, {@code permissions}, {@code messages} and {@code sounds}. The bound
 * behavior reads the rest (for {@code allium:disarm}: {@code charges}, {@code targets},
 * {@code disarm}, {@code timing} and {@code appearance.charge-variants}).
 */
public final class ToolDefinitionParser {

    public static final int SCHEMA = 1;

    private static final Pattern ID = Pattern.compile("allium:[a-z0-9_]{1,64}");
    private static final Pattern NODE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");
    private static final Pattern LANG_KEY = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");
    private static final Pattern SOUND = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private ToolDefinitionParser() {
    }

    /** The outcome for one file: a definition, or the reasons it was rejected. */
    public record Result(String file, @Nullable ToolDefinition definition, List<String> errors) {
        public Result {
            Objects.requireNonNull(file, "file");
            errors = List.copyOf(errors);
        }

        public boolean valid() {
            return definition != null;
        }

        /** Each error as {@code <file>: <path>: <message>}. */
        public List<String> diagnostics() {
            return errors.stream().map(e -> file + ": " + e).toList();
        }

        public static Result rejected(String file, List<String> errors) {
            return new Result(file, null, errors);
        }
    }

    public static Result parse(String file, Map<?, ?> document, Map<String, ToolBehaviorType<?>> behaviors, ToolEnvironment environment) {
        ConfigReader root = ConfigReader.of(document);
        long schema = root.whole("schema", SCHEMA, Long.MIN_VALUE, Long.MAX_VALUE);
        if (schema != SCHEMA) {
            root.error("schema", "must be " + SCHEMA + "; this Allium reads tool definition schema " + SCHEMA + " (got " + schema + ")");
        }

        String id = root.requiredString("id");
        String localId = null;
        if (id != null) {
            id = id.trim();
            if (ID.matcher(id).matches()) {
                localId = id.substring("allium:".length());
            } else {
                root.error("id", "must be allium:<name> using a-z, 0-9 and _ (got '" + id + "')");
            }
        }
        String name = root.string("name", id == null ? "tool" : id);

        String behaviorId = root.requiredString("behavior");
        ToolBehaviorType<?> type = null;
        if (behaviorId != null) {
            type = behaviors.get(behaviorId.trim());
            if (type == null) {
                root.error("behavior", "unknown behavior '" + behaviorId + "'; available: " + String.join(", ", behaviors.keySet()));
            }
        }
        boolean enabled = root.bool("enabled", true);

        if (localId != null && environment.isCoreItem(localId) && type != null) {
            Optional<String> reserved = environment.coreItemBehavior(localId);
            if (reserved.isEmpty() || !reserved.get().equals(type.id())) {
                root.error("id", id + " is reserved for a core tool"
                        + reserved.map(r -> " with behavior " + r).orElse(" that is not available yet"));
            }
        }

        BuiltInItemSpec item = parseItem(root, localId, environment);

        ConfigReader appearanceSection = root.section("appearance");
        ProviderKey ownBuiltIn = localId == null ? null : ProviderKey.allium(localId);
        ProviderKey appearance = appearanceKey(appearanceSection, "default", appearanceSection.string("default", null), ownBuiltIn);
        if (appearance == null) {
            appearance = ownBuiltIn;
        }
        if (localId != null && ownBuiltIn.equals(appearance) && item == null && !environment.isCoreItem(localId)) {
            root.error("item", "is required: allium:" + localId + " is not a core pack item, so this definition must"
                    + " declare its built-in item (or set appearance.default to a provider item)");
        }
        List<ProviderKey> aliases = new ArrayList<>();
        List<String> rawAliases = appearanceSection.stringList("aliases", List.of());
        for (int i = 0; i < rawAliases.size(); i++) {
            ProviderKey alias = appearanceKey(appearanceSection, "aliases[" + i + "]", rawAliases.get(i), ownBuiltIn);
            if (alias == null) {
                continue;
            }
            if (alias.equals(appearance) || aliases.contains(alias)) {
                appearanceSection.error("aliases[" + i + "]", alias + " is listed twice");
            } else {
                aliases.add(alias);
            }
        }

        Map<String, String> permissions = new LinkedHashMap<>();
        Map<String, String> messages = new LinkedHashMap<>();
        Map<String, ToolSound> sounds = new LinkedHashMap<>();
        Map<String, List<String>> particles = new LinkedHashMap<>();
        ToolParameters parameters = null;
        if (type != null) {
            parseSlots(root, type, localId, environment, permissions, messages, sounds);
            parseParticles(root, type, environment, particles);
            parameters = type.parse(root, environment);
            // Unknown-key checks need the behavior to have declared its settings.
            root.rejectUnknown();
        }

        if (root.hasErrors() || type == null || id == null || localId == null || appearance == null) {
            return Result.rejected(file, root.errors().isEmpty() ? List.of("(file): could not be read") : root.errors());
        }
        return new Result(file, new ToolDefinition(file, id, name, type.id(), enabled, item, appearance, aliases,
                permissions, messages, sounds, particles, parameters), List.of());
    }

    @Nullable
    private static BuiltInItemSpec parseItem(ConfigReader root, @Nullable String localId, ToolEnvironment environment) {
        if (!root.has("item")) {
            return null;
        }
        ConfigReader item = root.section("item");
        if (localId != null && environment.isCoreItem(localId)) {
            root.error("item", "allium:" + localId + " is a core pack item, which brings its own item; remove this section");
        }
        String material = item.requiredString("material");
        if (material != null && !environment.isItemMaterial(material.trim())) {
            item.error("material", "'" + material + "' is not a vanilla item material");
        }
        String displayName = item.string("name", null);
        Integer model = item.has("custom-model-data") ? item.integer("custom-model-data", 0, 0, 16_777_215) : null;
        return material == null ? null : new BuiltInItemSpec(material.trim(), displayName, model);
    }

    /**
     * Parses an appearance reference. Vanilla items are refused (every such item would become the
     * tool), and so is another tool's built-in item.
     */
    @Nullable
    private static ProviderKey appearanceKey(ConfigReader section, String key, @Nullable String raw, @Nullable ProviderKey ownBuiltIn) {
        if (raw == null) {
            return null;
        }
        ProviderKey parsed;
        try {
            parsed = ProviderKey.parse(raw.trim());
        } catch (IllegalArgumentException e) {
            section.error(key, "invalid item reference '" + raw + "' (" + e.getMessage() + ")");
            return null;
        }
        if (parsed.isVanilla()) {
            section.error(key, "'" + raw + "' is a vanilla item; every such item would become this tool."
                    + " Use allium:, nexo:, oraxen: or craftengine:");
            return null;
        }
        if (parsed.isAllium() && !parsed.equals(ownBuiltIn)) {
            section.error(key, parsed + " is another tool's built-in item; a definition can only use its own ("
                    + (ownBuiltIn == null ? "allium:<id>" : ownBuiltIn) + ") or a provider item");
            return null;
        }
        return parsed;
    }

    private static void parseSlots(ConfigReader root, ToolBehaviorType<?> type, @Nullable String localId, ToolEnvironment environment,
                                   Map<String, String> permissions, Map<String, String> messages, Map<String, ToolSound> sounds) {
        ConfigReader permissionSection = root.section("permissions");
        for (String slot : type.permissionSlots().keySet()) {
            String node = permissionSection.string(slot, "allium.tool." + (localId == null ? "unknown" : localId) + "." + slot);
            if (node != null && NODE.matcher(node.trim()).matches()) {
                permissions.put(slot, node.trim());
            } else if (node != null) {
                permissionSection.error(slot, "'" + node + "' is not a valid permission node (lowercase a-z, 0-9, _, - and .)");
            }
        }

        ConfigReader messageSection = root.section("messages");
        for (Map.Entry<String, String> slot : type.messageSlots().entrySet()) {
            String key = messageSection.string(slot.getKey(), slot.getValue());
            if (key == null) {
                continue;
            }
            key = key.trim();
            if (!LANG_KEY.matcher(key).matches()) {
                messageSection.error(slot.getKey(), "'" + key + "' is not a valid lang key");
            } else if (!environment.hasLangKey(key)) {
                messageSection.error(slot.getKey(), "lang key '" + key + "' is not in the lang files");
            } else {
                messages.put(slot.getKey(), key);
            }
        }

        ConfigReader soundSection = root.section("sounds");
        for (Map.Entry<String, ToolSound> slot : type.soundSlots().entrySet()) {
            ToolSound sound = parseSound(soundSection, slot.getKey(), slot.getValue());
            if (sound != null) {
                sounds.put(slot.getKey(), sound);
            }
        }
    }

    /** {@code particles.<slot>}: one particle name, a list of them, or {@code []} for none. */
    private static void parseParticles(ConfigReader root, ToolBehaviorType<?> type, ToolEnvironment environment,
                                       Map<String, List<String>> particles) {
        if (type.particleSlots().isEmpty()) {
            return;
        }
        ConfigReader section = root.section("particles");
        for (Map.Entry<String, List<String>> slot : type.particleSlots().entrySet()) {
            List<String> names;
            Object raw = section.raw(slot.getKey()).orElse(null);
            if (raw == null) {
                names = slot.getValue();
            } else if (raw instanceof String single) {
                names = single.isBlank() ? List.of() : List.of(single.trim());
            } else {
                names = section.stringList(slot.getKey(), slot.getValue());
            }
            List<String> valid = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i).trim().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("minecraft:")) {
                    name = name.substring("minecraft:".length());
                }
                if (environment.isParticle(name)) {
                    valid.add(name);
                } else {
                    section.error(slot.getKey() + (raw instanceof List<?> ? "[" + i + "]" : ""), "'" + names.get(i) + "' is not a particle on this server");
                }
            }
            particles.put(slot.getKey(), valid);
        }
    }

    @Nullable
    private static ToolSound parseSound(ConfigReader sounds, String slot, ToolSound fallback) {
        if (!sounds.has(slot)) {
            return fallback;
        }
        Object raw = sounds.raw(slot).orElse(null);
        String id;
        float volume = fallback.volume();
        float pitch = fallback.pitch();
        ConfigReader spec = null;
        if (raw instanceof String text) {
            id = text;
        } else if (raw instanceof Map<?, ?>) {
            spec = sounds.section(slot);
            id = spec.requiredString("sound");
            volume = (float) spec.decimal("volume", fallback.volume(), 0.0D, 10.0D);
            pitch = (float) spec.decimal("pitch", fallback.pitch(), 0.5D, 2.0D);
        } else {
            if (raw != null) {
                sounds.error(slot, "must be a sound ID or a section with sound, volume and pitch");
            }
            return null;
        }
        if (id == null) {
            return null;
        }
        id = id.trim();
        if (!id.isEmpty() && !SOUND.matcher(id).matches()) {
            (spec == null ? sounds : spec).error(spec == null ? slot : "sound", "'" + id + "' is not a valid sound ID");
            return null;
        }
        return new ToolSound(id, volume, pitch);
    }
}
