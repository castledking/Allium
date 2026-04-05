package net.survivalfun.core.items.impl;

import net.survivalfun.core.items.CustomItem;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

public class TreeAxeItem extends CustomItem {

    public TreeAxeItem(final Plugin plugin) {
        super(plugin, "tree_axe");
    }

    @Override
    public Material getMaterial() {
        return Material.NETHERITE_AXE;
    }

    @Override
    public String getDisplayName() {
        return "§dLazy Axe";
    }

    @Override
    public List<String> getLore() {
        return Arrays.asList(
            "§7A magical netherite axe that",
            "§7chops down entire trees with one hit!"
        );
    }

    @Override
    public String getTextureType() {
        return "item_model";
    }

    @Override
    public Object getItemModel() {
        return "oraxen:tree_axe";
    }

    @Override
    public int getCustomModelData() {
        return 1001;
    }
}
