package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Map;

public class GradientGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private final String currentNickname;
    private String currentStyle;
    
    private final Map<String, Color[]> gradients = Map.of(
        "Rainbow", new Color[]{Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.BLUE, Color.PURPLE},
        "Fire", new Color[]{Color.RED, Color.ORANGE, Color.YELLOW},
        "Ocean", new Color[]{Color.BLUE, Color.AQUA, Color.WHITE},
        "Forest", new Color[]{Color.GREEN, Color.LIME, Color.YELLOW},
        "Sunset", new Color[]{Color.PURPLE, Color.FUCHSIA, Color.ORANGE},
        "Galaxy", new Color[]{Color.PURPLE, Color.BLUE, Color.FUCHSIA, Color.RED},
        "Gold", new Color[]{Color.YELLOW, Color.ORANGE, Color.RED},
        "Pink", new Color[]{Color.fromRGB(255, 105, 180), Color.fromRGB(255, 20, 147), Color.fromRGB(219, 112, 147)}
    );
    
    private final Map<String, String[]> customGradients = Map.of(
        "&c&lRed&f&lGradient", new String[]{"#ff0000", "#ff4444"},
        "&6&lOrange&f&lGradient", new String[]{"#ff8800", "#ffaa00"},
        "&e&lYellow&f&lGradient", new String[]{"#ffff00", "#aaff00"},
        "&a&lGreen&f&lGradient", new String[]{"#00ff00", "#00ff88"},
        "&b&lAqua&f&lGradient", new String[]{"#00ffff", "#00aaff"},
        "&9&lBlue&f&lGradient", new String[]{"#0000ff", "#4400ff"},
        "&d&lPink&f&lGradient", new String[]{"#ff00ff", "#ff0088"},
        "&5&lPurple&f&lGradient", new String[]{"#8800ff", "#ff00ff"}
    );

    public GradientGUI(Player player, PluginStart plugin, String currentNickname, String currentStyle) {
        super(player, "Select Gradient", 4, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.currentNickname = currentNickname;
        this.currentStyle = currentStyle;
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();
        
        // Preset gradients
        int slot = 0;
        for (Map.Entry<String, Color[]> entry : gradients.entrySet()) {
            String name = entry.getKey();
            Color[] colors = entry.getValue();
            
            String startHex = String.format("#%02x%02x%02x", colors[0].getRed(), colors[0].getGreen(), colors[0].getBlue());
            String endHex = String.format("#%02x%02x%02x", colors[colors.length - 1].getRed(), colors[colors.length - 1].getGreen(), colors[colors.length - 1].getBlue());
            
            getInventory().setItem(slot++, new ItemBuilder(Material.PAINTING)
                .name("&b" + name)
                .lore(
                    "",
                    "&7Click to apply this gradient",
                    "&7Start: " + startHex + " End: " + endHex
                )
                .build());
        }
        
        // Custom gradients
        for (Map.Entry<String, String[]> entry : customGradients.entrySet()) {
            String name = entry.getKey();
            
            getInventory().setItem(slot++, new ItemBuilder(Material.PAINTING)
                .name(name)
                .lore(
                    "",
                    "&7Click to apply this gradient"
                )
                .build());
        }
        
        // Back button
        getInventory().setItem(35, new ItemBuilder(Material.ARROW)
            .name("&f&lBack")
            .lore("", "&7Go back to nickname editor")
            .build());
        
        // Clear gradient button
        getInventory().setItem(36, new ItemBuilder(Material.BARRIER)
            .name("&c&lClear Gradient")
            .lore("", "&7Remove gradient effect")
            .build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        int slot = event.getSlot();
        
        // Back button
        if (slot == 35) {
            player.openInventory(new NicknameGUI(player, plugin).getInventory());
            return;
        }
        
        // Clear gradient
        if (slot == 36) {
            currentStyle = "";
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&7Gradient cleared!"));
            return;
        }
        
        // Preset gradients (slots 0-7)
        if (slot < gradients.size()) {
            String[] gradientNames = gradients.keySet().toArray(new String[0]);
            String gradientName = gradientNames[slot];
            Color[] colors = gradients.get(gradientName);
            
            String startHex = String.format("#%02x%02x%02x", colors[0].getRed(), colors[0].getGreen(), colors[0].getBlue());
            String endHex = String.format("#%02x%02x%02x", colors[colors.length - 1].getRed(), colors[colors.length - 1].getGreen(), colors[colors.length - 1].getBlue());
            
            currentStyle = "<gradient:" + startHex + ":" + endHex + ">";
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&aApplied " + gradientName + " gradient!"));
            return;
        }
        
        // Custom gradients (slots 8+)
        int customIndex = slot - gradients.size();
        String[] customNames = customGradients.keySet().toArray(new String[0]);
        if (customIndex >= 0 && customIndex < customNames.length) {
            String[] colors = customGradients.get(customNames[customIndex]);
            
            currentStyle = "<gradient:" + colors[0] + ":" + colors[1] + ">";
            NicknameGUI gui = new NicknameGUI(player, plugin);
            gui.setCurrentNickname(currentNickname);
            gui.setCurrentStyle(currentStyle);
            player.openInventory(gui.getInventory());
            player.sendMessage(PrismaticAPI.colorize(player, "&aApplied gradient!"));
        }
    }
}
