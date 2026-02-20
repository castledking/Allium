package net.survivalfun.core.items.impl;

import net.survivalfun.core.items.CustomItem;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

public class SpawnerChangerItem extends CustomItem {

    public SpawnerChangerItem(final Plugin plugin) {
        super(plugin, "spawner_changer");
    }

    @Override
    public Material getMaterial() {
        return Material.BLAZE_ROD;
    }

    @Override
    public String getDisplayName() {
        return "§6§lSpawner Type Changer";
    }

    @Override
    public List<String> getLore() {
        return Arrays.asList(
            "§7Right-Click on any spawner",
            "§7with this tool to open",
            "§7the changer menu.",
            "",
            "§cWarning: This item only has one use!"
        );
    }

    @Override
    public String getTextureType() {
        return "custommodeldata";
    }

    @Override
    public Object getItemModel() {
        return "allium:spawner_changer";
    }

    @Override
    public int getCustomModelData() {
        return 1002;
    }

    @Override
    public boolean isSingleUse() {
        return true;
    }
}
