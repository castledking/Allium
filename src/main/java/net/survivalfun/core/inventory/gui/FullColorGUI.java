package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Arrays;
import java.util.List;

public class FullColorGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private final String currentNickname;
    private String currentStyle;
    
    // All 16 Minecraft colors plus some extras
    private final List<Color> colors = Arrays.asList(
        Color.fromRGB(0, 0, 0),        // 0 - Black
        Color.fromRGB(0, 0, 170),       // 1 - Dark Blue
        Color.fromRGB(0, 170, 0),       // 2 - Dark Green
        Color.fromRGB(0, 170, 170),     // 3 - Dark Aqua
        Color.fromRGB(170, 0, 0),       // 4 - Dark Red
        Color.fromRGB(170, 0, 170),     // 5 - Purple
        Color.fromRGB(255, 170, 0),     // 6 - Gold
        Color.fromRGB(170, 170, 170),   // 7 - Gray
        Color.fromRGB(85, 85, 85),      // 8 - Dark Gray
        Color.fromRGB(85, 85, 255),     // 9 - Blue
        Color.fromRGB(85, 255, 85),     // a - Green
        Color.fromRGB(85, 255, 255),    // b - Aqua
        Color.fromRGB(255, 85, 85),     // c - Red
        Color.fromRGB(255, 85, 255),    // d - Pink
        Color.fromRGB(255, 255, 85),    // e - Yellow
        Color.fromRGB(255, 255, 255)    // f - White
    );
    
    private final List<String> colorNames = Arrays.asList(
        "Black", "Dark Blue", "Dark Green", "Dark Aqua",
        "Dark Red", "Purple", "Gold", "Gray",
        "Dark Gray", "Blue", "Green", "Aqua",
        "Red", "Pink", "Yellow", "White"
    );

    public FullColorGUI(Player player, PluginStart plugin, String currentNickname, String currentStyle) {
        super(player, "Select Color", 4, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.currentNickname = currentNickname;
        this.currentStyle = currentStyle;
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();
        
        // Color buttons (first 2 rows)
        for (int i = 0; i < colors.size(); i++) {
            Color color = colors.get(i);
            String name = colorNames.get(i);
            String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            
            getInventory().setItem(i, new ItemBuilder(Material.WHITE_WOOL)
                .name("&" + getColorCode(i) + name + " (#" + hex.substring(1) + ")")
                .lore(
                    "",
                    "&7Click to apply this color"
                )
                .build());
        }
        
        // Back button
        getInventory().setItem(35, new ItemBuilder(Material.ARROW)
            .name("&f&lBack")
            .lore("", "&7Go back to nickname editor")
            .build());
    }
    
    private String getColorCode(int index) {
        String[] codes = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
        return index < codes.length ? codes[index] : "f";
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
        
        // Color selection
        if (slot >= 0 && slot < colors.size()) {
            Color color = colors.get(slot);
            String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            
            currentStyle = hex;
            
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&aApplied " + colorNames.get(slot) + " color!"));
        }
    }
}
