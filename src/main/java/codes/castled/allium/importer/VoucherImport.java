package codes.castled.allium.importer;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.BuiltInItemSpec;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolBinding;
import codes.castled.allium.tool.powertool.PowerToolCodec;
import codes.castled.allium.tool.powertool.PowerToolCooldownScope;
import codes.castled.allium.tool.powertool.PowerToolGrants;
import codes.castled.allium.tool.powertool.PowerToolParameters;
import codes.castled.allium.tool.powertool.PowerToolSecurity;
import codes.castled.allium.tool.powertool.PowerToolTrigger;
import codes.castled.allium.tool.powertool.PowerToolUses;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans {@code /allium import vouchers}: each voucher in pre-recode Allium's {@code vouchers.yml}
 * becomes its own powertool, {@code tools/voucher_<id>.yml}. Reading and planning need no server.
 *
 * <p>A voucher's powertool looks the same (material, name, lore, the hidden-enchantment glint), is
 * redeemed by right-clicking it in either hand, and is used up by whoever redeems it (one of the
 * stack, operators included). In order, it:
 * <ol>
 *   <li>grants one of its {@code Grant} permissions, picked by weight among those the player lacks
 *       (a {@code required} {@code grant} action: with none left, nothing runs and the voucher is
 *       kept, where pre-recode Allium used it up anyway);</li>
 *   <li>runs its {@code Commands} as the console ({@code %player%} becomes {@code {player}}, and a
 *       command Allium owns gets its {@code allium:} label, which the console allowlist admits);</li>
 *   <li>says "Voucher redeemed: &lt;name&gt;", as before.</li>
 * </ol>
 */
public final class VoucherImport {

    public static final String SOURCE = "vouchers.yml";
    public static final String PREFIX = "voucher_";

    /** Glow colours: Essentials' {@code essentials.glow.color.<colour>} is Allium's {@code allium.glow.<colour>}. */
    static final Set<String> GLOW_COLORS = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final Pattern ESSENTIALS_GLOW = Pattern.compile("^essentials\\.glow\\.color\\.([a-z_]+)$");
    private static final Pattern LEGACY_PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_]+)%");
    private static final Pattern FORMATTING = Pattern.compile("(?i)[&§](#[0-9a-f]{6}|[0-9a-fk-orx])");

    public enum Status {
        /** A new tool file is written. */
        WRITE,
        /** The tool file already holds exactly this. */
        SAME,
        /** A different tool file has the name; it is kept and this voucher is not imported. */
        KEPT,
        /** The voucher cannot be imported (its id, or the tool it would make, is invalid). */
        BLOCKED
    }

    /** One voucher: its tool's name, what happens to it, the tool document and what to know. */
    public record Item(String legacyId, @Nullable String toolName, Status status, @Nullable Map<String, Object> document,
                       List<String> notes) {
        public Item {
            notes = List.copyOf(notes);
        }
    }

    public record Plan(List<Item> items) {
        public Plan {
            items = List.copyOf(items);
        }

        public long count(Status status) {
            return items.stream().filter(item -> item.status() == status).count();
        }
    }

    /**
     * What the server knows.
     *
     * @param alliumLabels the bare command labels Allium owns, lower-case
     * @param allowlist    {@code powertools.console-allowlist}
     * @param existing     a tool file's text after a YAML round trip, by tool name, or null when absent
     * @param validate     the tool definition parser's errors for a document; empty when valid
     */
    public record Context(Set<String> alliumLabels, List<String> allowlist, boolean blockSelectors,
                          Function<String, String> existing, Function<Map<String, Object>, List<String>> validate) {
    }

    private VoucherImport() {
    }

    /** {@code TFly10m} is {@code voucher_tfly10m}; null when the result is not a valid tool name. */
    @Nullable
    public static String toolName(String legacyId) {
        String cleaned = legacyId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        cleaned = cleaned.replaceAll("^_|_$", "");
        String name = PREFIX + cleaned;
        return cleaned.isEmpty() || !PowerToolSecurity.isValidToolName(name) ? null : name;
    }

    public static Plan plan(ConfigurationSection root, Context context) {
        List<Item> items = new ArrayList<>();
        ConfigurationSection vouchers = root.getConfigurationSection("vouchers");
        if (vouchers == null) return new Plan(items);
        Set<String> taken = new HashSet<>();
        for (String legacyId : vouchers.getKeys(false)) {
            ConfigurationSection voucher = vouchers.getConfigurationSection(legacyId);
            String name = toolName(legacyId);
            if (voucher == null) {
                items.add(new Item(legacyId, name, Status.BLOCKED, null, List.of("it is not a section of voucher settings")));
                continue;
            }
            if (name == null) {
                items.add(new Item(legacyId, null, Status.BLOCKED, null,
                        List.of("its id does not make a tool name (2 to 48 of a-z, 0-9 and _, after 'voucher_')")));
                continue;
            }
            if (!taken.add(name)) {
                items.add(new Item(legacyId, name, Status.BLOCKED, null,
                        List.of("another voucher already becomes " + name + " (ids differ only in case or punctuation)")));
                continue;
            }
            List<String> notes = new ArrayList<>();
            Map<String, Object> document = document(legacyId, name, voucher, context, notes);
            List<String> errors = context.validate().apply(document);
            if (!errors.isEmpty()) {
                notes.addAll(errors);
                items.add(new Item(legacyId, name, Status.BLOCKED, document, notes));
                continue;
            }
            String existing = context.existing().apply(name);
            Status status;
            if (existing == null) {
                status = Status.WRITE;
            } else if (existing.equals(yaml(document))) {
                status = Status.SAME;
            } else {
                status = Status.KEPT;
                notes.add("tools/" + name + ".yml already exists and differs; it is kept (delete it and run the import again to"
                        + " replace it)");
            }
            items.add(new Item(legacyId, name, status, document, notes));
        }
        return new Plan(items);
    }

    /** A document as YAML text, the form {@link Context#existing()} is compared against. */
    public static String yaml(Map<String, Object> document) {
        YamlConfiguration yaml = new YamlConfiguration();
        document.forEach(yaml::set);
        return yaml.saveToString();
    }

    private static Map<String, Object> document(String legacyId, String name, ConfigurationSection voucher, Context context,
                                                List<String> notes) {
        String materialName = voucher.getString("Material", "PAPER");
        Material material = materialName == null ? null : Material.matchMaterial(materialName.trim());
        if (material == null || material.isAir() || !material.isItem()) {
            notes.add("Material '" + materialName + "' is no item; it is paper, as pre-recode Allium made it");
            material = Material.PAPER;
        }
        String displayName = voucher.getString("DisplayName", "&fVoucher");
        List<String> lore = voucher.getStringList("Lore");
        boolean enchanted = voucher.getBoolean("Enchanted", false);
        BuiltInItemSpec item = new BuiltInItemSpec(material.name().toLowerCase(Locale.ROOT), displayName, null, lore,
                enchanted ? Map.of("unbreaking", 1) : Map.of(), false, enchanted ? List.of("HIDE_ENCHANTS") : List.of());

        List<PowerToolAction> actions = new ArrayList<>();
        String grants = grants(voucher.getStringList("Grant"), notes);
        if (grants != null) {
            actions.add(new PowerToolAction(PowerToolAction.TYPE_GRANT, grants, false, 0L, PowerToolCooldownScope.PLAYER, null, 0L,
                    null, Map.of(), true));
        }
        for (String raw : voucher.getStringList("Commands")) {
            String command = command(raw, context, notes);
            if (command != null) {
                actions.add(new PowerToolAction(PowerToolAction.TYPE_CONSOLE, command, true, 0L, PowerToolCooldownScope.PLAYER, null,
                        0L, null, Map.of()));
            }
        }
        if (actions.isEmpty()) {
            notes.add("it has no commands and no grants, so it only says it was redeemed");
        }
        actions.add(new PowerToolAction(PowerToolAction.TYPE_MESSAGE, "&aVoucher redeemed: " + displayName, false, 0L,
                PowerToolCooldownScope.PLAYER, null, 0L, null, Map.of()));

        PowerToolBinding redeem = new PowerToolBinding(PowerToolTrigger.RIGHT, null, true, PowerToolBinding.HAND_BOTH,
                PowerToolBinding.SNEAK_ANY, List.of(), 0L, PowerToolCooldownScope.PLAYER, null, null, false, true, actions);
        Map<PowerToolTrigger, PowerToolBinding> bindings = new LinkedHashMap<>();
        bindings.put(PowerToolTrigger.RIGHT, redeem);
        PowerToolParameters parameters = new PowerToolParameters(new PowerToolUses(1, true, 0, false, false),
                PowerToolParameters.DEFAULT_CLICK_GUARD_MILLIS, bindings);
        String plainName = FORMATTING.matcher(displayName).replaceAll("").trim();
        return PowerToolCodec.toDefinitionDocument(name, plainName.isEmpty() ? legacyId : plainName, item, ProviderKey.allium(name),
                parameters, null, null);
    }

    /** {@code chance:permission} lines as a grant value, or null when there are none. */
    @Nullable
    private static String grants(List<String> lines, List<String> notes) {
        List<String> entries = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line == null ? new String[0] : line.split(":", 2);
            int weight;
            try {
                weight = parts.length == 2 ? Integer.parseInt(parts[0].trim()) : -1;
            } catch (NumberFormatException invalid) {
                weight = -1;
            }
            if (weight < 0 || parts[1].trim().isEmpty()) {
                notes.add("grant '" + line + "' is not chance:permission; it is left out");
                continue;
            }
            if (weight == 0) {
                notes.add("grant '" + line + "' has chance 0, so it was never picked; it is left out");
                continue;
            }
            String node = parts[1].trim().toLowerCase(Locale.ROOT);
            Matcher glow = ESSENTIALS_GLOW.matcher(node);
            if (glow.matches() && GLOW_COLORS.contains(glow.group(1))) {
                String allium = "allium.glow." + glow.group(1);
                notes.add(node + " is " + allium + " in Allium (Essentials' node does nothing here)");
                node = allium;
            }
            String entry = Math.min(weight, PowerToolGrants.MAX_WEIGHT) + ":" + node;
            if (PowerToolGrants.firstInvalid(entry) != null) {
                notes.add("grant '" + line + "' does not name a valid permission node; it is left out");
                continue;
            }
            entries.add(entry);
        }
        return entries.isEmpty() ? null : String.join(" ", entries);
    }

    /** A voucher command as a powertool console command, or null when it is empty. */
    @Nullable
    private static String command(@Nullable String raw, Context context, List<String> notes) {
        String command = raw == null ? "" : PowerToolSecurity.cleanCommand(raw);
        if (command.isEmpty()) return null;
        command = command.replace("%player%", "{player}");
        Matcher other = LEGACY_PLACEHOLDER.matcher(command);
        while (other.find()) {
            notes.add("'" + raw + "' has " + other.group() + ", which pre-recode Allium never filled in either; it is passed as typed");
        }
        String unknown = PowerToolSecurity.firstUnknownPlaceholder(command);
        if (unknown != null) {
            notes.add("'" + raw + "' has {" + unknown + "}, which powertools refuse, so this command will not run");
        }
        int space = command.indexOf(' ');
        String head = (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
        if (!head.contains(":") && context.alliumLabels().contains(head)) {
            command = "allium:" + command;
            head = "allium:" + head;
        }
        if (!PowerToolSecurity.isConsoleCommandAllowed(command, context.allowlist())) {
            notes.add("'" + command + "' will not run until '" + head + "' is in powertools.console-allowlist (config.yml)");
        }
        if (context.blockSelectors() && PowerToolSecurity.containsSelectors(command)) {
            notes.add("'" + command + "' uses a target selector, which powertools.block-selectors refuses");
        }
        return command;
    }
}
