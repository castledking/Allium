package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class LetterColorGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private final String nickname;
    private final Map<Integer, String> letterColors = new HashMap<>();
    private int selectedLetterIndex = -1;

    private final List<String> colorCodes = Arrays.asList(
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "a", "b", "c", "d", "e", "f"
    );

    private final Map<String, Color> colorMap = new HashMap<>();
    private final List<Color> colors = Arrays.asList(
        Color.fromRGB(0, 0, 0),        // 0 - Black
        Color.fromRGB(170, 0, 0),      // 4 - Dark Red
        Color.fromRGB(0, 170, 0),      // 2 - Dark Green
        Color.fromRGB(170, 85, 0),     // 6 - Gold
        Color.fromRGB(0, 0, 170),      // 1 - Dark Blue
        Color.fromRGB(170, 0, 170),    // 5 - Purple
        Color.fromRGB(0, 170, 170),    // 3 - Dark Aqua
        Color.fromRGB(170, 170, 170),  // 7 - Gray
        Color.fromRGB(85, 85, 85),    // 8 - Dark Gray
        Color.fromRGB(255, 85, 85),    // c - Red
        Color.fromRGB(85, 255, 85),    // a - Green
        Color.fromRGB(255, 255, 85),   // e - Yellow
        Color.fromRGB(85, 85, 255),    // 9 - Blue
        Color.fromRGB(255, 85, 255),   // d - Pink
        Color.fromRGB(85, 255, 255),   // b - Aqua
        Color.fromRGB(255, 255, 255)   // f - White
    );

    public LetterColorGUI(Player player, PluginStart plugin, String nickname, Map<Integer, String> existingColors) {
        super(player, "Letter Colors - " + nickname, 6, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.nickname = nickname;
        if (existingColors != null) {
            this.letterColors.putAll(existingColors);
        }
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();

        for (int i = 0; i < Math.min(nickname.length(), 45); i++) {
            String letter = String.valueOf(nickname.charAt(i));
            String color = letterColors.get(i);
            
            ItemStack item;
            if (color != null) {
                item = new ItemBuilder(Material.PAPER)
                    .name("§" + color + letter)
                    .lore("§7Click to change color")
                    .build();
            } else {
                item = new ItemBuilder(Material.PAPER)
                    .name("§f" + letter)
                    .lore("§7Click to set color")
                    .build();
            }
            getInventory().setItem(i, item);
        }

        for (int i = 0; i < colorCodes.size(); i++) {
            String code = colorCodes.get(i);
            Color color = colors.get(i);
            
            ItemStack colorItem = new ItemBuilder(Material.WHITE_WOOL)
                .name("§" + code + getColorName(code))
                .lore("", "§7Click to apply this color")
                .build();
            
            getInventory().setItem(45 + i, colorItem);
        }

        getInventory().setItem(53, new ItemBuilder(Material.LIME_DYE)
            .name("§a§lApply")
            .lore("", "§7Click to apply letter colors")
            .build());
            
        getInventory().setItem(52, new ItemBuilder(Material.BARRIER)
            .name("§c§lClear All")
            .lore("", "§7Click to clear all letter colors")
            .build());

        getInventory().setItem(49, new ItemBuilder(Material.ARROW)
            .name("§f§lBack")
            .lore("", "§7Go back to nickname editor")
            .build());
    }

    private String getColorName(String code) {
        return switch (code) {
            case "0" -> "Black";
            case "1" -> "Dark Blue";
            case "2" -> "Dark Green";
            case "3" -> "Dark Aqua";
            case "4" -> "Dark Red";
            case "5" -> "Purple";
            case "6" -> "Gold";
            case "7" -> "Gray";
            case "8" -> "Dark Gray";
            case "9" -> "Blue";
            case "a" -> "Green";
            case "b" -> "Aqua";
            case "c" -> "Red";
            case "d" -> "Pink";
            case "e" -> "Yellow";
            case "f" -> "White";
            default -> "Unknown";
        };
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        int slot = event.getSlot();
        
        if (slot == 49) {
            player.openInventory(new NicknameGUI(player, plugin).getInventory());
            return;
        }
        
        if (slot == 52) {
            letterColors.clear();
            initialize();
            return;
        }
        
        if (slot == 53) {
            for (Map.Entry<Integer, String> entry : letterColors.entrySet()) {
                nicknameManager.setLetterColor(player, entry.getKey(), entry.getValue());
            }
            player.sendMessage(PrismaticAPI.colorize(player, "&aLetter colors applied!"));
            player.openInventory(new NicknameGUI(player, plugin).getInventory());
            return;
        }
        
        if (slot >= 45 && slot < 61) {
            int colorIndex = slot - 45;
            if (colorIndex < colorCodes.size() && selectedLetterIndex >= 0) {
                letterColors.put(selectedLetterIndex, colorCodes.get(colorIndex));
                initialize();
            }
            return;
        }
        
        if (slot < nickname.length()) {
            selectedLetterIndex = slot;
            player.sendMessage(PrismaticAPI.colorize(player, 
                "&aSelected letter: " + nickname.charAt(slot) + " &a- Now click a color below!"));
        }
    }
}
