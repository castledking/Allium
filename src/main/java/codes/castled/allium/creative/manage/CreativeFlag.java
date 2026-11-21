package codes.castled.allium.creative.manage;

import codes.castled.allium.creative.CreativeManager;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The creative-manager permission flags that {@code /creativemanager} edits, each the node
 * {@code allium.gamemode.creative.<key>}. The first nine are the per-action restrictions, the next
 * nine the extras, and {@link #BYPASS} lifts every restriction at once.
 */
public enum CreativeFlag {
    PLACE("place", Material.GRASS_BLOCK, "Place blocks"),
    BREAK("break", Material.IRON_PICKAXE, "Break blocks"),
    INTERACT("interact", Material.LEVER, "Doors, beds, item frames, mobs, food and projectiles"),
    USE("use", Material.COMPASS, "Take items from the creative menu"),
    DROP("drop", Material.DROPPER, "Drop items"),
    PICKUP("pickup", Material.HOPPER, "Pick up items"),
    CONTAINER("container", Material.CHEST, "Open chests, furnaces, crafting tables and villagers"),
    SPAWN("spawn", Material.ZOMBIE_SPAWN_EGG, "Use spawn eggs"),
    PICKBLOCK("pickblock", Material.SPYGLASS, "Pick block (Paper 1.21.4 and later)"),
    BLACKLIST("blacklist", Material.BARRIER, "Ignore creative-manager.blacklist and protect-tnt"),
    FILLED_CONTAINERS("filled-containers", Material.SHULKER_BOX, "Place containers that already hold items"),
    LOOT("loot", Material.GOLD_INGOT, "Normal drops from blocks placed in creative"),
    UNTAGGED("untagged", Material.NAME_TAG, "Creative-menu items without the creative lore"),
    ANY_ITEM("any-item", Material.ENCHANTED_BOOK, "Take enchanted or unsanitized items from the menu"),
    KEEP_EFFECTS("keep-effects", Material.BREWING_STAND, "Keep potion effects when switching gamemode"),
    OWN_ARMOR("own-armor", Material.IRON_CHESTPLATE, "Keep their own armour in creative"),
    OWN_GLOW("own-glow", Material.GLOWSTONE_DUST, "Keep their own glow in restricted creative"),
    INVENTORY("inventory", Material.ENDER_CHEST, "One shared inventory across gamemodes"),
    BYPASS("bypass", Material.NETHER_STAR, "Unrestricted creative: every flag above except inventory");

    private final String key;
    private final Material icon;
    private final String description;

    CreativeFlag(String key, Material icon, String description) {
        this.key = key;
        this.icon = icon;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String node() {
        return CreativeManager.PERMISSION_PREFIX + key;
    }

    public Material icon() {
        return icon;
    }

    public String description() {
        return description;
    }

    /** Whether {@link #BYPASS} grants this flag as a child node. */
    public boolean coveredByBypass() {
        return this != BYPASS && this != INVENTORY;
    }

    /** The flag named {@code key} ({@code place}, {@code filled-containers}), ignoring case; null if none. */
    @Nullable
    public static CreativeFlag byKey(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (CreativeFlag flag : values()) {
            if (flag.key.equals(wanted)) {
                return flag;
            }
        }
        return null;
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(CreativeFlag::key).toList();
    }
}
