package net.survivalfun.core.items;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.survivalfun.core.managers.config.CustomItemsConfig;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

import io.papermc.paper.datacomponent.DataComponentTypes;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.INFO;

public abstract class CustomItem {

    protected final Plugin plugin;
    protected final String id;
    protected final NamespacedKey itemKey;

    protected CustomItem(final Plugin plugin, final String id) {
        this.plugin = plugin;
        this.id = id;
        this.itemKey = new NamespacedKey(plugin, "custom_item_id");
    }

    public String getId() {
        return id;
    }

    public abstract Material getMaterial();

    public abstract String getDisplayName();

    public abstract List<String> getLore();

    public abstract String getTextureType();

    public Object getItemModel() {
        return null;
    }

    public int getCustomModelData() {
        return 0;
    }

    public boolean isSingleUse() {
        return false;
    }

    public boolean onUse(final org.bukkit.entity.Player player, final ItemStack item) {
        return false;
    }

    public boolean onUseOnBlock(final org.bukkit.entity.Player player, final ItemStack item,
                                 final org.bukkit.block.Block block, final org.bukkit.block.BlockFace face) {
        return false;
    }

    public ItemStack createItemStack(final int amount) {
        ItemStack item = new ItemStack(getMaterial(), amount);
        final ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(getDisplayName());

        final List<String> lore = getLore();
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore);
        }

        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);

        String textureType = getTextureType();
        Text.sendDebugLog(INFO, "Creating item: " + id + ", textureType: " + textureType);
        if (textureType != null && textureType.equalsIgnoreCase("custommodeldata")) {
            int cmd = getCustomModelData();
            Text.sendDebugLog(INFO, "Setting custommodeldata: " + cmd);
            if (cmd > 0) {
                meta.setCustomModelData(cmd);
            }
        }

        item.setItemMeta(meta);

        if (textureType != null && textureType.equalsIgnoreCase("item_model")) {
            Object model = getItemModel();
            if (model != null) {
                item = applyItemModel(item, model);
            }
        }

        return item;
    }

    private ItemStack applyItemModel(ItemStack stack, Object modelValue) {
        if (stack == null || stack.getType() == Material.AIR) {
            return stack;
        }

        try {
            Key modelKey;
            
            if (modelValue instanceof String) {
                String modelStr = (String) modelValue;
                if (modelStr.contains(":")) {
                    String[] parts = modelStr.split(":");
                    if (parts.length == 2) {
                        modelKey = Key.key(parts[0], parts[1]);
                    } else {
                        return stack;
                    }
                } else {
                    modelKey = Key.key("allium", modelStr);
                }
            } else if (modelValue instanceof Integer) {
                modelKey = Key.key("allium", "cmd_" + modelValue);
            } else if (modelValue instanceof Float) {
                modelKey = Key.key("allium", "cmd_" + ((Float) modelValue).intValue());
            } else {
                Text.sendDebugLog(INFO, "Unknown item_model type: " + modelValue.getClass().getName());
                return stack;
            }

            stack.setData(DataComponentTypes.ITEM_MODEL, modelKey);
            return stack;
        } catch (Exception e) {
            Text.sendDebugLog(INFO, "Failed to set item model: " + e.getMessage());
            e.printStackTrace();
            return stack;
        }
    }

    public ItemStack createItemStack() {
        return createItemStack(1);
    }

    public boolean isThisItem(final ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        final String storedId = item.getItemMeta().getPersistentDataContainer()
                .get(itemKey, PersistentDataType.STRING);

        return id.equals(storedId);
    }

    public static CustomItem fromConfigDefinition(Plugin plugin, CustomItemsConfig.MaterialDefinition def) {
        return new ConfigBasedCustomItem(plugin, def);
    }

    private static class ConfigBasedCustomItem extends CustomItem {
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final String textureType;
        private final Object itemModel;
        private final int customModelData;
        private final boolean singleUse;

        public ConfigBasedCustomItem(Plugin plugin, CustomItemsConfig.MaterialDefinition def) {
            super(plugin, def.getId());
            this.material = Material.valueOf(def.getMaterial().toUpperCase());
            this.name = def.getName();
            this.lore = def.getLore();
            this.textureType = def.getTextureType();
            this.itemModel = def.getItemModel();
            this.customModelData = def.getCustomModelData();
            this.singleUse = def.isSingleUse();
        }

        @Override
        public Material getMaterial() {
            return material;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public List<String> getLore() {
            return lore;
        }

        @Override
        public String getTextureType() {
            return textureType;
        }

        @Override
        public Object getItemModel() {
            return itemModel;
        }

        @Override
        public int getCustomModelData() {
            return customModelData;
        }

        @Override
        public boolean isSingleUse() {
            return singleUse;
        }
    }
}
