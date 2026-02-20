package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Map;

public class TemplateGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private final String nickname;

    public TemplateGUI(Player player, PluginStart plugin, String nickname) {
        super(player, "Nickname Templates", 4, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.nickname = nickname;
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();

        Map<String, String[]> templates = nicknameManager.getTemplates();
        int slot = 0;
        
        for (Map.Entry<String, String[]> entry : templates.entrySet()) {
            String name = entry.getKey();
            String[] colors = entry.getValue();
            
            // Build preview by applying colors to template name
            StringBuilder preview = new StringBuilder();
            String[] letters = name.split("");
            for (int i = 0; i < letters.length; i++) {
                String color = colors[i % colors.length];
                preview.append(color).append(letters[i]);
            }
            
            getInventory().setItem(slot++, new ItemBuilder(Material.NAME_TAG)
                .name(PrismaticAPI.colorize(player, preview.toString()))
                .lore(
                    "",
                    "§7Click to apply this template",
                    "§7to your nickname"
                )
                .build());
        }

        getInventory().setItem(35, new ItemBuilder(Material.ARROW)
            .name("§f§lBack")
            .lore("", "§7Go back to nickname editor")
            .build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        int slot = event.getSlot();
        
        if (slot == 35) {
            player.openInventory(new NicknameGUI(player, plugin).getInventory());
            return;
        }
        
        Map<String, String[]> templates = nicknameManager.getTemplates();
        int index = 0;
        
        for (Map.Entry<String, String[]> entry : templates.entrySet()) {
            if (slot == index) {
                String templateName = entry.getKey();
                String styledNick = nicknameManager.applyTemplate(templateName, nickname);
                
                // Store the styled nickname for when user clicks apply
                player.sendMessage(PrismaticAPI.colorize(player, 
                    "&aApplied " + templateName + " template!"));
                
                NicknameGUI gui = new NicknameGUI(player, plugin);
                gui.setCurrentNickname(nickname);
                gui.setCurrentStyle(styledNick);
                player.openInventory(gui.getInventory());
                return;
            }
            index++;
        }
    }
}
