package codes.castled.allium.placeholder;

import codes.castled.allium.PluginStart;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The {@code allium} PlaceholderAPI expansion; loaded only when PlaceholderAPI is present. */
final class AlliumExpansion extends PlaceholderExpansion {

    private final PluginStart plugin;
    private final AlliumPlaceholders placeholders;

    private AlliumExpansion(PluginStart plugin, AlliumPlaceholders placeholders) {
        this.plugin = plugin;
        this.placeholders = placeholders;
    }

    static AutoCloseable register(PluginStart plugin, AlliumPlaceholders placeholders) {
        AlliumExpansion expansion = new AlliumExpansion(plugin, placeholders);
        if (!expansion.register()) {
            throw new IllegalStateException("the allium placeholder identifier is already registered");
        }
        return expansion::unregister;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium";
    }

    @Override
    public @NotNull String getAuthor() {
        return "castledking";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return AlliumPlaceholders.PLAYER_KEYS.stream().map(key -> "%allium_" + key + "%").toList();
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        return placeholders.request(player, params);
    }
}
