package net.survivalfun.core.inventory.gui;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Prismatic-style home menu for nickname editing. One clean menu with buttons
 * that open separate sub-menus (Edit, Colors, Gradients, Letter Colors, Templates,
 * Animations, Format, Glow, Reset). Replaces the previous single crowded editor.
 */
public class NicknameHomeGUI extends BaseGUI {

    private final NicknameManager nicknameManager;

    public NicknameHomeGUI(Player player, PluginStart plugin) {
        super(player, "Nickname", 3, plugin);
        this.nicknameManager = plugin.getNicknameManager();
        initialize();
    }

    @Override
    public void initialize() {
        getInventory().clear();

        String currentNick = nicknameManager.getStoredNickname(player);
        if (currentNick == null || currentNick.isEmpty()) {
            currentNick = player.getName();
        }
        final String nick = currentNick;

        // Row 2 (slots 9-17): main category buttons
        getInventory().setItem(10, new ItemBuilder(Material.NAME_TAG)
            .name("&a&lEdit Nickname")
            .lore(
                "",
                "&7Open the full nickname editor",
                "&7with preview and apply"
            )
            .build());

        getInventory().setItem(11, new ItemBuilder(Material.WHITE_WOOL)
            .name("&f&lSolid Colors")
            .lore(
                "",
                "&7Pick a single color",
                "&7for your nickname"
            )
            .build());

        getInventory().setItem(12, new ItemBuilder(Material.PAINTING)
            .name("&b&lGradients")
            .lore(
                "",
                "&7Apply a gradient effect",
                "&7to your nickname"
            )
            .build());

        getInventory().setItem(13, new ItemBuilder(Material.WRITABLE_BOOK)
            .name("&d&lLetter Colors")
            .lore(
                "",
                "&7Color each letter",
                "&7of your nickname"
            )
            .build());

        getInventory().setItem(14, new ItemBuilder(Material.MAP)
            .name("&6&lTemplates")
            .lore(
                "",
                "&7Pre-built nickname",
                "&7styles"
            )
            .build());

        getInventory().setItem(15, new ItemBuilder(Material.CLOCK)
            .name("&e&lAnimations")
            .lore(
                "",
                "&7Animated nickname",
                "&7effects"
            )
            .build());

        getInventory().setItem(16, new ItemBuilder(Material.OAK_SIGN)
            .name("&f&lFormat & Effects")
            .lore(
                "",
                "&7Bold, italic, underline,",
                "&7strikethrough, obfuscated"
            )
            .build());

        boolean hasGlow = nicknameManager.hasGlowEffect(player);
        getInventory().setItem(17, new ItemBuilder(hasGlow ? Material.BEACON : Material.GLASS)
            .name(hasGlow ? "&e&lGlow: &aON" : "&7&lGlow: &cOFF")
            .lore(
                "",
                "&7Click to toggle glow",
                "&7on your nickname"
            )
            .build());

        // Bottom center: Reset
        getInventory().setItem(22, new ItemBuilder(Material.BARRIER)
            .name("&c&lReset Nickname")
            .lore(
                "",
                "&7Reset to your",
                "&7original username"
            )
            .build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;

        int slot = event.getSlot();
        String currentNick = nicknameManager.getStoredNickname(player);
        if (currentNick == null || currentNick.isEmpty()) {
            currentNick = player.getName();
        }

        switch (slot) {
            case 10:
                player.openInventory(new NicknameGUI(player, plugin).getInventory());
                return;
            case 11:
                player.openInventory(new FullColorGUI(player, plugin, currentNick, "").getInventory());
                return;
            case 12:
                player.openInventory(new GradientGUI(player, plugin, currentNick, "").getInventory());
                return;
            case 13:
                player.openInventory(new LetterColorGUI(player, plugin, currentNick, new java.util.HashMap<>()).getInventory());
                return;
            case 14:
                player.openInventory(new TemplateGUI(player, plugin, currentNick).getInventory());
                return;
            case 15:
                player.openInventory(new AnimationGUI(player, plugin, currentNick, "").getInventory());
                return;
            case 16:
                player.openInventory(new FormatGUI(player, plugin, currentNick, "").getInventory());
                return;
            case 17:
                boolean currentGlow = nicknameManager.hasGlowEffect(player);
                nicknameManager.setGlowEffect(player, !currentGlow);
                player.sendMessage(PrismaticAPI.colorize(player,
                    !currentGlow ? "&aGlow effect enabled!" : "&7Glow effect disabled."));
                initialize();
                return;
            case 22:
                nicknameManager.resetNickname(player);
                player.sendMessage(PrismaticAPI.colorize(player, "&aNickname reset to " + player.getName()));
                initialize();
                return;
            default:
                break;
        }
    }
}
