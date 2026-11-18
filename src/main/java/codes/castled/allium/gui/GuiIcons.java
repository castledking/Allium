package codes.castled.allium.gui;

import codes.castled.allium.pack.AssetManifest;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu icons for {@code /invsee} and {@code /restore}: a light gray glass pane carrying the core
 * pack's {@code gui_slot} model (a textured pane tile, or the empty-slot hint of an equipment slot).
 * Without the pack, players see named light gray panes. Every icon is marked, so a menu can tell an
 * icon from a real item and never saves or hands one out.
 */
public final class GuiIcons {

    public enum Icon {
        FILLER, HELMET, CHESTPLATE, LEGGINGS, BOOTS, SHIELD, SWORD;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<Icon, Integer> FALLBACK = new EnumMap<>(Map.of(Icon.FILLER, 2577100, Icon.HELMET, 2577101,
            Icon.CHESTPLATE, 2577102, Icon.LEGGINGS, 2577103, Icon.BOOTS, 2577104, Icon.SHIELD, 2577105, Icon.SWORD, 2577106));
    private static volatile Map<Icon, Integer> modelData = FALLBACK;
    private static volatile NamespacedKey marker;

    private GuiIcons() {
    }

    /** Binds the marker key and reads each icon's model data from the bundled manifest. */
    public static void init(org.bukkit.plugin.Plugin plugin, @Nullable AssetManifest manifest) {
        marker = new NamespacedKey(plugin, "gui_icon");
        if (manifest == null) {
            return;
        }
        manifest.tool("gui_slot").ifPresent(tool -> {
            Map<Icon, Integer> read = new EnumMap<>(Icon.class);
            for (Icon icon : Icon.values()) {
                tool.state(icon.key()).ifPresent(state -> read.put(icon, state.customModelData()));
            }
            if (read.size() == Icon.values().length) {
                modelData = read;
            }
        });
    }

    /** A marked icon with {@code name} and {@code lore} (legacy color codes). */
    public static ItemStack icon(Icon icon, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(name);
                meta.setLore(lore.isEmpty() ? null : lore);
                meta.setCustomModelData(modelData.get(icon));
                if (marker != null) {
                    meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, icon.key());
                }
                stack.setItemMeta(meta);
            }
        } catch (RuntimeException ignored) {
            // No item factory in plain unit tests: a bare pane still marks the slot as locked.
        }
        return stack;
    }

    /** True for an icon made by {@link #icon}: never a real item. */
    public static boolean isIcon(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
            return false;
        }
        try {
            ItemMeta meta = stack.getItemMeta();
            return marker != null && meta != null && meta.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
        } catch (RuntimeException noItemFactory) {
            // Plain unit tests have no item factory, so icons there are bare panes.
            return true;
        }
    }

    /** The empty-slot icon for player slot {@code slot} (36-39 boots..helmet, 40 offhand), or null for other slots. */
    @Nullable
    public static Icon equipmentIcon(int slot) {
        return switch (slot) {
            case 36 -> Icon.BOOTS;
            case 37 -> Icon.LEGGINGS;
            case 38 -> Icon.CHESTPLATE;
            case 39 -> Icon.HELMET;
            case 40 -> Icon.SHIELD;
            default -> null;
        };
    }
}
