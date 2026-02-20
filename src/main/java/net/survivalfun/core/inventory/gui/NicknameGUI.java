package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NicknameGUI extends BaseGUI {
    private final NicknameManager nicknameManager;
    private String currentNickname = "";
    private String currentStyle = "";
    private String currentAnimation = "none";
    private final List<String> colorCodes = Arrays.asList(
        "a", "b", "c", "d", "e", "f",  // Standard colors
        "1", "2", "3", "4", "5", "6",  // Dark colors
        "7", "8", "9", "0"             // Gray, dark gray, blue, black
    );
    private final List<Color> colors = Arrays.asList(
        Color.fromRGB(85, 255, 85),   // &a - Green
        Color.fromRGB(85, 255, 255),  // &b - Aqua
        Color.fromRGB(255, 85, 85),   // &c - Red
        Color.fromRGB(255, 85, 255),  // &d - Pink
        Color.fromRGB(255, 255, 85),  // &e - Yellow
        Color.fromRGB(255, 255, 255), // &f - White
        Color.fromRGB(85, 85, 255),   // &1 - Dark Blue
        Color.fromRGB(85, 170, 0),    // &2 - Dark Green
        Color.fromRGB(85, 170, 170),  // &3 - Dark Aqua
        Color.fromRGB(170, 0, 0),     // &4 - Dark Red
        Color.fromRGB(170, 0, 170),   // &5 - Purple
        Color.fromRGB(255, 170, 0),   // &6 - Gold
        Color.fromRGB(170, 170, 170), // &7 - Gray
        Color.fromRGB(85, 85, 85),    // &8 - Dark Gray
        Color.fromRGB(85, 85, 255),   // &9 - Blue
        Color.fromRGB(0, 0, 0)        // &0 - Black
    );
    
    private final Map<String, String> formatCodes = Map.of(
        "&l", "<b>",  // Bold
        "&n", "<u>",  // Underline
        "&o", "<i>",  // Italic
        "&k", "<obf>", // Obfuscated
        "&m", "<st>"   // Strikethrough
    );
    
    private final Map<String, Color[]> gradients = Map.of(
        "Rainbow", new Color[]{
            Color.RED, Color.ORANGE, Color.YELLOW, 
            Color.GREEN, Color.BLUE, Color.FUCHSIA
        },
        "Fire", new Color[]{
            Color.RED, Color.ORANGE, Color.YELLOW
        },
        "Ocean", new Color[]{
            Color.BLUE, Color.AQUA, Color.WHITE
        },
        "Forest", new Color[]{
            Color.GREEN, Color.LIME, Color.YELLOW
        }
    );

    public NicknameGUI(Player player, PluginStart plugin) {
        super(player, "Nickname Editor", 6, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.currentNickname = player.getName();
        initialize();
    }

    public NicknameGUI(Player player, PluginStart plugin, String initialNickname) {
        super(player, "Nickname Editor", 6, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        this.currentNickname = initialNickname;
        initialize();
    }

    public void setCurrentNickname(String nickname) {
        this.currentNickname = nickname;
        updatePreview();
    }

    public void setCurrentStyle(String style) {
        this.currentStyle = style;
        updatePreview();
    }

    @Override
    public void initialize() {
        // Clear the inventory first
        getInventory().clear();
        
        // Main action buttons row (slots 0-8)
        // Slot 0-7: Color selection
        for (int i = 0; i < Math.min(colorCodes.size(), 8); i++) {
            String color = colorCodes.get(i);
            String colorName = getColorName(color);
            ItemStack colorItem = new ItemBuilder(Material.WHITE_WOOL)
                .name("&" + color + colorName)
                .lore(
                    "",
                    "&7Click to apply this color",
                    "&7to your nickname"
                )
                .build();
            getInventory().setItem(i, colorItem);
        }

        // Slot 8: More colors (opens full color picker)
        getInventory().setItem(8, new ItemBuilder(Material.REDSTONE)
            .name("&c&lMore Colors")
            .lore(
                "",
                "&7Click for more color options"
            )
            .build());

        // Action buttons row (slots 9-17)
        // Slot 9: Gradient button
        getInventory().setItem(9, new ItemBuilder(Material.PAINTING)
            .name("&b&lGradients")
            .lore(
                "",
                "&7Click to browse gradient",
                "&7effects for your nickname"
            )
            .build());

        // Slot 10: Letter Colors button
        getInventory().setItem(10, new ItemBuilder(Material.WRITABLE_BOOK)
            .name("&d&lLetter Colors")
            .lore(
                "",
                "&7Click to color each letter",
                "&7of your nickname individually"
            )
            .build());

        // Slot 11: Templates button
        getInventory().setItem(11, new ItemBuilder(Material.MAP)
            .name("&6&lTemplates")
            .lore(
                "",
                "&7Click to browse pre-built",
                "&7nickname templates"
            )
            .build());

        // Slot 12: Animations button
        getInventory().setItem(12, new ItemBuilder(Material.CLOCK)
            .name("&e&lAnimations")
            .lore(
                "",
                "&7Click to browse animated",
                "&7nickname styles"
            )
            .build());

        // Slot 13: Glow toggle button
        boolean hasGlow = nicknameManager.hasGlowEffect(player);
        getInventory().setItem(13, new ItemBuilder(hasGlow ? Material.BEACON : Material.GLASS)
            .name(hasGlow ? "&e&lGlow: ON" : "&7&lGlow: OFF")
            .lore(
                "",
                "&7Click to toggle glow effect",
                "&7on your nickname"
            )
            .build());

        // Slot 14: Format options
        getInventory().setItem(14, new ItemBuilder(Material.OAK_SIGN)
            .name("&f&lFormatting")
            .lore(
                "",
                "&7Click for bold, italic,",
                "&7underline, strikethrough, etc"
            )
            .build());

        // Preview slot (slot 22)
        updatePreview();

        // Bottom row
        // Slot 45: Reset button
        getInventory().setItem(45, new ItemBuilder(Material.BARRIER)
            .name("&c&lReset Nickname")
            .lore(
                "",
                "&7Click to reset your nickname",
                "&7to your original username"
            )
            .build());

        // Slot 49: Confirm button
        getInventory().setItem(49, new ItemBuilder(Material.LIME_DYE)
            .name("&a&lApply Nickname")
            .lore(
                "",
                "&7Click to apply your new",
                "&7nickname"
            )
            .build());
    }

    private void updatePreview() {
        String displayName = currentNickname;
        
        // Apply gradient if selected
        if (currentStyle.startsWith("gradient:")) {
            String[] parts = currentStyle.substring(9).split(",");
            if (parts.length >= 2) {
                displayName = nicknameManager.applyGradient(displayName, parts[0], parts[1]);
            }
        } 
        // Apply color if selected
        else if (!currentStyle.isEmpty()) {
            displayName = "&" + currentStyle + displayName;
        }
        
        // Apply formatting using PrismaticAPI
        displayName = nicknameManager.formatNickname(player, displayName);
        
        // Create preview item
        ItemStack preview = new ItemBuilder(Material.NAME_TAG)
            .name(displayName)
            .lore(
                "",
                "§7Current Nickname: " + displayName,
                "",
                "§eClick to edit nickname"
            )
            .build();
            
        getInventory().setItem(22, preview);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        if (event.getCurrentItem() == null) return;
        
        int slot = event.getSlot();
        Player player = (Player) event.getWhoClicked();

        // Handle gradient button (slot 9)
        if (slot == 9) {
            player.openInventory(new GradientGUI(player, plugin, currentNickname, currentStyle).getInventory());
            return;
        }

        // Handle letter color button (slot 10)
        if (slot == 10) {
            player.openInventory(new LetterColorGUI(player, plugin, currentNickname, new java.util.HashMap<>()).getInventory());
            return;
        }

        // Handle template button (slot 11)
        if (slot == 11) {
            player.openInventory(new TemplateGUI(player, plugin, currentNickname).getInventory());
            return;
        }

        // Handle animation button (slot 12)
        if (slot == 12) {
            player.openInventory(new AnimationGUI(player, plugin, currentNickname, currentStyle).getInventory());
            return;
        }

        // Handle glow toggle (slot 13)
        if (slot == 13) {
            boolean currentGlow = nicknameManager.hasGlowEffect(player);
            nicknameManager.setGlowEffect(player, !currentGlow);
            updatePreview();
            player.sendMessage(PrismaticAPI.colorize(player, 
                !currentGlow ? "&aGlow effect enabled!" : "&7Glow effect disabled."));
            return;
        }

        // Handle format button (slot 14)
        if (slot == 14) {
            player.openInventory(new FormatGUI(player, plugin, currentNickname, currentStyle).getInventory());
            return;
        }

        // Handle color selection (slots 0-7)
        if (slot < 8) {
            currentStyle = String.format("%02x%02x%02x", 
                colors.get(slot).getRed(),
                colors.get(slot).getGreen(),
                colors.get(slot).getBlue()
            );
            currentAnimation = "none";
            updatePreview();
            return;
        }

        // Handle more colors (slot 8)
        if (slot == 8) {
            player.openInventory(new FullColorGUI(player, plugin, currentNickname, currentStyle).getInventory());
            return;
        }

        // Handle reset button (slot 45)
        if (slot == 45) {
            currentNickname = player.getName();
            currentStyle = "";
            currentAnimation = "none";
            updatePreview();
            return;
        }

        // Handle confirm button (slot 49)
        if (slot == 49) {
            if (nicknameManager.isOnCooldown(player)) {
                long remaining = nicknameManager.getRemainingCooldown(player);
                player.sendMessage(ChatColor.RED + "You must wait " + remaining + " seconds before changing your nickname again!");
                return;
            }

            if (!nicknameManager.isValidNickname(currentNickname)) {
                player.sendMessage(ChatColor.RED + "Invalid nickname! Must be between " + 
                    nicknameManager.getMinNickLength() + " and " + 
                    nicknameManager.getMaxNickLength() + " characters and only contain letters, numbers, and underscores.");
                return;
            }

            // Build the final nickname with style
            String displayName = currentStyle + currentNickname;
            player.setDisplayName(displayName);
            player.setPlayerListName(displayName);
            
            // Set cooldown
            nicknameManager.setCooldown(player);
            
            player.sendMessage(ChatColor.GREEN + "Nickname updated to: " + displayName);
            player.closeInventory();
        }
    }

    private String getColorName(String colorCode) {
        switch (colorCode) {
            case "a": return "Green";
            case "b": return "Aqua";
            case "c": return "Red";
            case "d": return "Pink";
            case "e": return "Yellow";
            case "f": return "White";
            case "1": return "Dark Blue";
            case "2": return "Dark Green";
            case "3": return "Dark Aqua";
            case "4": return "Dark Red";
            case "5": return "Purple";
            case "6": return "Gold";
            case "7": return "Gray";
            case "8": return "Dark Gray";
            case "9": return "Blue";
            case "0": return "Black";
            default: return "Unknown";
        }
    }
}
