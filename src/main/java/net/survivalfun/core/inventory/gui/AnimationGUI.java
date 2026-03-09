package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Map;

public class AnimationGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private final String currentNickname;
    private String currentStyle;
    private String currentAnimation;

    public AnimationGUI(Player player, PluginStart plugin, String currentNickname, String currentStyle) {
        super(player, "Select Animation", 4, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.currentNickname = currentNickname;
        this.currentStyle = currentStyle;
        this.currentAnimation = "none";
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();
        
        Map<String, NicknameManager.NicknameAnimation> animations = nicknameManager.getAnimations();
        int slot = 0;
        
        for (Map.Entry<String, NicknameManager.NicknameAnimation> entry : animations.entrySet()) {
            String name = entry.getKey();
            NicknameManager.NicknameAnimation anim = entry.getValue();
            
            getInventory().setItem(slot++, new ItemBuilder(Material.CLOCK)
                .name("&e&l" + name.substring(0, 1).toUpperCase() + name.substring(1))
                .lore(
                    "",
                    "&7Click to apply this animation",
                    "&7to your nickname"
                )
                .build());
        }
        
        // Back button
        getInventory().setItem(35, new ItemBuilder(Material.ARROW)
            .name("&f&lBack")
            .lore("", "&7Go back to nickname editor")
            .build());
        
        // Clear animation button
        getInventory().setItem(36, new ItemBuilder(Material.BARRIER)
            .name("&c&lClear Animation")
            .lore("", "&7Remove animation effect")
            .build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        int slot = event.getSlot();
        
        // Back button
        if (slot == 35) {
            player.openInventory(new NicknameHomeGUI(player, plugin).getInventory());
            return;
        }
        
        // Clear animation
        if (slot == 36) {
            currentAnimation = "none";
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&7Animation cleared!"));
            return;
        }
        
        // Animation selection
        Map<String, NicknameManager.NicknameAnimation> animations = nicknameManager.getAnimations();
        int index = 0;
        
        for (Map.Entry<String, NicknameManager.NicknameAnimation> entry : animations.entrySet()) {
            if (slot == index) {
                String animName = entry.getKey();
                currentAnimation = animName;
                
                NicknameGUI gui = new NicknameGUI(player, plugin);
                gui.setCurrentNickname(currentNickname);
                gui.setCurrentStyle(currentStyle);
                player.openInventory(gui.getInventory());
                player.sendMessage(PrismaticAPI.colorize(player, "&aApplied " + animName + " animation!"));
                return;
            }
            index++;
        }
    }
}
