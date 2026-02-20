package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class TeamPlaceholder extends PlaceholderExpansion {

    private final PluginStart plugin;

    public TeamPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium_team";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
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
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        String lowerParams = params.toLowerCase();
        if (!lowerParams.startsWith("team_")) {
            return null;
        }

        Team team = getPlayerTeam(player);

        return switch (lowerParams) {
            case "team_color" -> getTeamColor(team);
            case "team_prefix" -> componentToLegacyString(team != null ? team.prefix() : null);
            case "team_suffix" -> componentToLegacyString(team != null ? team.suffix() : null);
            case "team_name" -> team != null ? team.getName() : "";
            case "team_displayname" -> componentToLegacyString(team != null ? team.displayName() : null);
            default -> null;
        };
    }

    private Team getPlayerTeam(Player player) {
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team team = scoreboard != null ? scoreboard.getEntryTeam(player.getName()) : null;
            if (team != null) {
                return team;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return null;
            }

            Scoreboard mainScoreboard = manager.getMainScoreboard();
            if (mainScoreboard == null || mainScoreboard == scoreboard) {
                return null;
            }

            return mainScoreboard.getEntryTeam(player.getName());
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "Failed to fetch team for player " + player.getName(), ex);
            return null;
        }
    }

    private String getTeamColor(Team team) {
        if (team == null) {
            return "&f";
        }

        try {
            Object color = team.color();
            if (color instanceof org.bukkit.ChatColor legacyColor) {
                return legacyColor.toString().replace('§', '&');
            }
            if (color instanceof NamedTextColor namedColor) {
                String name = NamedTextColor.NAMES.key(namedColor);
                if (name != null) {
                    org.bukkit.ChatColor legacy = org.bukkit.ChatColor.valueOf(name.toUpperCase());
                    if (legacy != null) {
                        return legacy.toString().replace('§', '&');
                    }
                }
                return "&f";
            }
        } catch (NoSuchMethodError ignored) {
            // Fallback for older Bukkit versions using getColor()
            try {
                org.bukkit.ChatColor legacyColor = team.getColor();
                if (legacyColor != null) {
                    return legacyColor.toString().replace('§', '&');
                }
            } catch (Exception inner) {
                Text.sendDebugLog(WARN, "Failed to access team color " + team.getName(), inner);
            }
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "Error reading team color for " + team.getName(), ex);
        }

        return "&f";
    }

    private String componentToLegacyString(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return LegacyComponentSerializer.legacySection().serialize(component);
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "Failed to serialize component", ex);
            return "";
        }
    }
}
