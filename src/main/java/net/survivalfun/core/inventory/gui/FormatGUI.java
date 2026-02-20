package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Map;

public class FormatGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private final String currentNickname;
    private String currentStyle;
    
    private final Map<String, String> formatCodes = Map.of(
        "Bold", "<b>",
        "Italic", "<i>",
        "Underline", "<u>",
        "Strikethrough", "<st>",
        "Obfuscated", "<obf>"
    );

    public FormatGUI(Player player, PluginStart plugin, String currentNickname, String currentStyle) {
        super(player, "Text Formatting", 3, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.currentNickname = currentNickname;
        this.currentStyle = currentStyle;
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();
        
        // Format buttons
        int slot = 0;
        for (Map.Entry<String, String> entry : formatCodes.entrySet()) {
            String name = entry.getKey();
            String code = entry.getValue();
            boolean active = currentStyle.contains(code);
            
            getInventory().setItem(slot++, new ItemBuilder(active ? Material.LIME_WOOL : Material.GRAY_WOOL)
                .name((active ? "&a" : "&7") + name)
                .lore(
                    "",
                    "&7Click to " + (active ? "remove" : "add") + " " + name.toLowerCase() + " formatting"
                )
                .build());
        }
        
        // Back button
        getInventory().setItem(18, new ItemBuilder(Material.ARROW)
            .name("&f&lBack")
            .lore("", "&7Go back to nickname editor")
            .build());
        
        // Clear all button
        getInventory().setItem(26, new ItemBuilder(Material.BARRIER)
            .name("&c&lClear All Formatting")
            .lore("", "&7Remove all text formatting")
            .build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        int slot = event.getSlot();
        
        // Back button
        if (slot == 18) {
            player.openInventory(new NicknameGUI(player, plugin).getInventory());
            return;
        }
        
        // Clear all formatting
        if (slot == 26) {
            currentStyle = "";
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&7All formatting cleared!"));
            return;
        }
        
        // Format toggle
        String[] formatNames = formatCodes.keySet().toArray(new String[0]);
        if (slot >= 0 && slot < formatNames.length) {
            String formatName = formatNames[slot];
            String code = formatCodes.get(formatName);
            
            if (currentStyle.contains(code)) {
                currentStyle = currentStyle.replace(code, "");
            } else {
                currentStyle = code + currentStyle;
            }
            
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&aApplied " + formatName + " formatting!"));
        }
    }
}
