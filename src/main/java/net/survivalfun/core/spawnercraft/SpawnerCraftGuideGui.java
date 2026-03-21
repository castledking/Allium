package net.survivalfun.core.spawnercraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates a fake crafting guide GUI showing the spawner recipe.
 */
public final class SpawnerCraftGuideGui {

    private static final String GUI_TITLE = "§8Spawner Crafting Guide";

    private SpawnerCraftGuideGui() {}

    public static void open(Player player, EntityType entityType) {
        Inventory gui = Bukkit.createInventory(null, InventoryType.WORKBENCH, GUI_TITLE);
        ItemStack mobHead = createDisplayHead(entityType);
        gui.setItem(1, mobHead.clone());
        gui.setItem(2, mobHead.clone());
        gui.setItem(3, mobHead.clone());
        gui.setItem(4, mobHead.clone());
        gui.setItem(5, createDisplayCore(entityType));
        gui.setItem(6, mobHead.clone());
        gui.setItem(7, mobHead.clone());
        gui.setItem(8, mobHead.clone());
        gui.setItem(9, mobHead.clone());
        gui.setItem(0, createResultSpawner(entityType));
        player.openInventory(gui);
    }

    public static String getGuiTitle() {
        return GUI_TITLE;
    }

    private static ItemStack createDisplayHead(EntityType entityType) {
        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        String hexColor = SpawnerCoreManager.getMobColor(entityType);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Head");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7Obtain from killing " + entityName + "s");
            lore.add("§7with the More Mob Heads datapack.");
            meta.setLore(lore);
            String texture = MobHeadTextureRegistry.getTexture(entityType);
            if (texture != null) {
                try {
                    String url = decodeTextureUrl(texture);
                    if (url != null) {
                        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                        PlayerTextures textures = profile.getTextures();
                        textures.setSkin(new URL(url));
                        profile.setTextures(textures);
                        meta.setOwnerProfile(profile);
                    }
                } catch (MalformedURLException ignored) {}
            }
            head.setItemMeta(meta);
        }
        return head;
    }

    private static String decodeTextureUrl(String base64) {
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(base64));
            int start = decoded.indexOf("\"url\":\"") + 7;
            int end = decoded.indexOf("\"", start);
            if (start > 6 && end > start) return decoded.substring(start, end);
        } catch (Exception ignored) {}
        return null;
    }

    private static ItemStack createDisplayCore(EntityType entityType) {
        ItemStack core = new ItemStack(Material.TRIAL_SPAWNER);
        ItemMeta meta = core.getItemMeta();
        if (meta != null) {
            String entityName = SpawnerCoreManager.formatEntityName(entityType);
            String hexColor = SpawnerCoreManager.getMobColor(entityType);
            meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Spawner Core");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7The center piece of the recipe.");
            lore.add("§7Obtained from §e/shop");
            meta.setLore(lore);
            core.setItemMeta(meta);
        }
        return core;
    }

    private static ItemStack createResultSpawner(EntityType entityType) {
        ItemStack spawner = new ItemStack(Material.SPAWNER);
        ItemMeta meta = spawner.getItemMeta();
        if (meta != null) {
            String entityName = SpawnerCoreManager.formatEntityName(entityType);
            String hexColor = SpawnerCoreManager.getMobColor(entityType);
            meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + entityName + " Spawner");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7Use this recipe to get spawners!");
            meta.setLore(lore);
            spawner.setItemMeta(meta);
        }
        return spawner;
    }
}
