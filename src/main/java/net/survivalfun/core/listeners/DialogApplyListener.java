package net.survivalfun.core.listeners;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Dialog;
import net.survivalfun.core.managers.core.Lore;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles Apply button clicks from nickname, rename, and lore dialogs.
 * Dialogs use minecraft:dynamic/custom with id allium:nick_apply, allium:rename_apply, allium:lore_apply, allium:lore_add_line.
 */
public class DialogApplyListener implements Listener {

    private static final String NICK_APPLY = "allium:nick_apply";
    private static final String RENAME_APPLY = "allium:rename_apply";
    private static final String LORE_APPLY = "allium:lore_apply";
    private static final String LORE_ADD_LINE = "allium:lore_add_line";

    private final PluginStart plugin;

    public DialogApplyListener(PluginStart plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCustomClick(PlayerCustomClickEvent event) {
        String id = event.getIdentifier().asString();
        if (!id.equals(NICK_APPLY) && !id.equals(RENAME_APPLY) && !id.equals(LORE_APPLY) && !id.equals(LORE_ADD_LINE)) {
            return;
        }

        if (!(event.getCommonConnection() instanceof PlayerGameConnection gameConn)) {
            return;
        }
        Player player = gameConn.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        DialogResponseView view = event.getDialogResponseView();
        if (view == null) {
            return;
        }

        if (id.equals(NICK_APPLY)) {
            handleNickApply(player, view);
        } else if (id.equals(RENAME_APPLY)) {
            handleRenameApply(player, view);
        } else if (id.equals(LORE_APPLY)) {
            handleLoreApply(player, view);
        } else if (id.equals(LORE_ADD_LINE)) {
            handleLoreAddLine(player, view);
        }
    }

    private void handleNickApply(Player player, DialogResponseView view) {
        // Try keys that the client might use for the single text input (wiki says "key" field; some clients may use index)
        String nickname = view.getText("nickname");
        if (nickname == null) nickname = view.getText("0");
        if (nickname == null) nickname = view.getText("input");
        if (nickname == null) nickname = "";
        nickname = nickname.trim();
        if (nickname.isEmpty()) {
            player.sendMessage("§cEnter a nickname.");
            return;
        }
        if (!player.hasPermission("allium.nick.gui")) {
            net.survivalfun.core.managers.lang.Lang lang = plugin.getLangManager();
            if (lang != null) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "nick", true);
            } else {
                player.sendMessage("§cYou don't have permission to use /nick.");
            }
            return;
        }
        if (containsMiniMessageTags(nickname) && !player.hasPermission("allium.nick.minimessage")) {
            player.sendMessage("§cNo permission to use MiniMessage tags (<red>, <gradient>, <rainbow>, etc.) in nickname.");
            return;
        }
        if (!plugin.getNicknameManager().isValidNickname(nickname)) {
            player.sendMessage("§cInvalid nickname.");
            return;
        }
        if (plugin.getNicknameManager().isOnCooldown(player)) {
            player.sendMessage("§cCooldown. Wait " + plugin.getNicknameManager().getRemainingCooldown(player) + "s.");
            return;
        }
        if (plugin.getNicknameManager().setNickname(player, nickname)) {
            player.sendMessage("§aNickname updated.");
        } else {
            player.sendMessage("§cCould not set nickname.");
        }
    }

    private static boolean containsMiniMessageTags(String text) {
        if (text == null) return false;
        return text.contains("<gradient") || text.contains("</gradient>")
            || text.contains("<rainbow>") || text.contains("</rainbow>")
            || text.contains("<red>") || text.contains("<blue>") || text.contains("<green>")
            || text.contains("<yellow>") || text.contains("<white>") || text.contains("<gray>")
            || text.matches(".*<[a-zA-Z0-9_]+>.*");
    }

    private void handleRenameApply(Player player, DialogResponseView view) {
        String name = view.getText("name");
        if (name == null) name = "";
        if (!player.hasPermission("allium.rename.gui")) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            Lang lang = plugin.getLangManager();
            if (lang != null) {
                Text.sendErrorMessage(player, "hold-item", lang, "{modify}", "rename");
            } else {
                player.sendMessage("§cHold an item to rename.");
            }
            return;
        }
        if (player.hasPermission("allium.rename.color")) {
            name = Text.parseColors(name);
        } else {
            name = MiniMessage.miniMessage().stripTags(name);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
            player.updateInventory();
            Lang lang = plugin.getLangManager();
            if (lang != null) {
                player.sendMessage(lang.get("rename.success").replace("{name}", name));
            } else {
                player.sendMessage("§aItem renamed.");
            }
        }
    }

    private void handleLoreApply(Player player, DialogResponseView view) {
        if (!player.hasPermission("allium.lore.gui")) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !item.hasItemMeta()) {
            player.sendMessage("§cHold an item to set lore.");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; ; i++) {
            String line = view.getText("lore" + i);
            if (line == null) break;
            line = line.trim();
            if (!line.isEmpty()) {
                lines.add(Text.parseColors(line));
            }
        }
        Lore.setLore(item, lines);
        player.updateInventory();
        player.sendMessage("§aLore updated.");
    }

    private void handleLoreAddLine(Player player, DialogResponseView view) {
        if (!player.hasPermission("allium.lore.gui")) return;
        // Collect current lines in order (preserve empty lines so boxes match), then add one empty
        List<String> currentLines = new ArrayList<>();
        for (int i = 0; ; i++) {
            String line = view.getText("lore" + i);
            if (line == null) break;
            currentLines.add(line.trim());
        }
        currentLines.add("");
        Dialog.showLoreInput(plugin, player, currentLines);
    }
}
