package codes.castled.allium.tool.powertool;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Global powertool safety settings read from {@code config.yml} under {@code powertools:}.
 */
public record PowerToolSettings(
        boolean enabled,
        List<String> consoleAllowlist,
        boolean blockSelectors
) {
    public static final List<String> DEFAULT_CONSOLE_ALLOWLIST = List.of();

    public static final PowerToolSettings DEFAULT = new PowerToolSettings(true, DEFAULT_CONSOLE_ALLOWLIST, true);

    public PowerToolSettings {
        consoleAllowlist = consoleAllowlist == null ? List.of() : List.copyOf(consoleAllowlist);
    }

    public static PowerToolSettings fromConfig(@Nullable ConfigurationSection section) {
        if (section == null) {
            return DEFAULT;
        }
        boolean enabled = section.getBoolean("enabled", true);
        List<String> allowlist = section.isList("console-allowlist")
                ? section.getStringList("console-allowlist")
                : DEFAULT_CONSOLE_ALLOWLIST;
        boolean blockSelectors = section.getBoolean("block-selectors", true);
        return new PowerToolSettings(enabled, allowlist, blockSelectors);
    }
}
