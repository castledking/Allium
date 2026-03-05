package net.survivalfun.core.managers.core;

import org.bukkit.inventory.meta.ItemMeta;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * Manager for applying item flags (e.g. HIDE_ENCHANTS, UNBREAKABLE) to items.
 * Used by Give command for flag: modifier (e.g. dhoe;flag:unbreakable).
 */
public class Flag {

    /**
     * Applies item flags from a comma-separated string.
     * Supports: UNBREAKABLE, HIDE_ENCHANTS, HIDE_ATTRIBUTES, HIDE_UNBREAKABLE,
     * HIDE_DESTROYS, HIDE_PLACED_ON.
     *
     * @param meta  The item meta to modify
     * @param flags Comma-separated flag names (e.g. "unbreakable" or "hide_enchants,hide_attributes")
     */
    public static void applyItemFlags(ItemMeta meta, String flags) {
        if (meta == null || flags == null || flags.isEmpty()) return;

        String[] flagList = flags.split(",");

        for (String flag : flagList) {
            flag = flag.trim().toUpperCase();

            switch (flag) {
                case "UNBREAKABLE":
                    meta.setUnbreakable(true);
                    break;
                case "HIDE_ENCHANTS":
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                    break;
                case "HIDE_ATTRIBUTES":
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                    break;
                case "HIDE_UNBREAKABLE":
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
                    break;
                case "HIDE_DESTROYS":
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_DESTROYS);
                    break;
                case "HIDE_PLACED_ON":
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_PLACED_ON);
                    break;
                default:
                    Text.sendDebugLog(INFO, "Unknown item flag: " + flag);
                    break;
            }
        }
    }
}
