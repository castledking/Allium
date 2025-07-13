
package net.survivalfun.core.managers.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class Placeholder extends PlaceholderExpansion {
    @Override
    public String getIdentifier() {
        return "core";
    }

    @Override
    public String getAuthor() {
        return "Towkio";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "";
        }

        // Map underscore-separated identifiers to display names
        switch (identifier.toLowerCase()) {
            case "controversial_topics":
                return "Controversial Topics";
            case "adult_content":
                return "Adult Content";
            case "asked_for_it":
                return "Asked For It";
            case "offensive_remark":
                return "Offensive Remark";
            case "staff_disrespect":
                return "Staff Disrespect";
            case "death_wishing":
                return "Death Wishing";
            case "other_languages":
                return "Other Languages";
            case "inappropriate_username":
                return "Inappropriate Username";
            case "movement_cheats":
                return "Movement Cheats";
            case "unfair_advantage":
                return "Unfair Advantage";
            case "disobeying_staff":
                return "Disobeying Staff";
            case "creating_lag_machines":
                return "Creating Lag Machines";
            case "item_duping":
                return "Item Duping";
            case "inappropriate_builds":
                return "Inappropriate Builds";
            case "killing_players_pets":
                return "Killing Players/Pets";
            case "inappropriate_skins":
                return "Inappropriate Skins";
            case "ban_evasion":
                return "Ban Evasion";
            default:
                return null;
        }
    }
}