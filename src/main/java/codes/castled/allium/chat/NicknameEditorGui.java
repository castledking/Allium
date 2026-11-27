package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.text.TextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Interactive {@code /editnick [player]} nickname styling GUI: lets players preview and apply
 * classic color codes, formatting styles, and preset gradients, saving through {@link ChatService}'s
 * existing validation, impersonation check, and Folia-safe persistence pipeline.
 */
public final class NicknameEditorGui implements Listener, AutoCloseable {

    public enum StyleFlag {
        BOLD('l', "Bold", Material.ANVIL),
        ITALIC('o', "Italic", Material.FEATHER),
        UNDERLINE('n', "Underline", Material.STICK),
        STRIKETHROUGH('m', "Strikethrough", Material.IRON_BARS),
        MAGIC('k', "Magic", Material.AMETHYST_SHARD);

        private final char code;
        private final String displayName;
        private final Material icon;

        StyleFlag(char code, String displayName, Material icon) {
            this.code = code;
            this.displayName = displayName;
            this.icon = icon;
        }

        public char code() {
            return code;
        }

        public String displayName() {
            return displayName;
        }

        public Material icon() {
            return icon;
        }
    }

    public record ColorOption(char code, String name, Material icon) {}

    public record GradientPreset(String id, String name, Material icon, List<String> hexStops) {
        public String openingTag() {
            return "<gradient:" + String.join(":", hexStops) + ">";
        }
    }

    public static final List<ColorOption> COLORS = List.of(
            new ColorOption('a', "Green", Material.LIME_WOOL),
            new ColorOption('b', "Aqua", Material.LIGHT_BLUE_WOOL),
            new ColorOption('c', "Red", Material.RED_WOOL),
            new ColorOption('d', "Light Purple", Material.MAGENTA_WOOL),
            new ColorOption('e', "Yellow", Material.YELLOW_WOOL),
            new ColorOption('f', "White", Material.WHITE_WOOL),
            new ColorOption('1', "Dark Blue", Material.BLUE_WOOL),
            new ColorOption('2', "Dark Green", Material.GREEN_WOOL),
            new ColorOption('3', "Dark Aqua", Material.CYAN_WOOL),
            new ColorOption('4', "Dark Red", Material.RED_TERRACOTTA),
            new ColorOption('5', "Dark Purple", Material.PURPLE_WOOL),
            new ColorOption('6', "Gold", Material.ORANGE_WOOL),
            new ColorOption('7', "Gray", Material.LIGHT_GRAY_WOOL),
            new ColorOption('8', "Dark Gray", Material.GRAY_WOOL),
            new ColorOption('9', "Blue", Material.CORNFLOWER),
            new ColorOption('0', "Black", Material.BLACK_WOOL)
    );

    public static final List<GradientPreset> GRADIENTS = List.of(
            new GradientPreset("rainbow", "Rainbow", Material.PRISMARINE_CRYSTALS, List.of("#FF5555", "#FFAA00", "#FFFF55", "#55FF55", "#5555FF", "#AA00AA")),
            new GradientPreset("fire", "Fire", Material.BLAZE_POWDER, List.of("#FF3300", "#FF8800", "#FFEE33")),
            new GradientPreset("ocean", "Ocean", Material.HEART_OF_THE_SEA, List.of("#1E40AF", "#06B6D4", "#E0F2FE")),
            new GradientPreset("forest", "Forest", Material.OAK_SAPLING, List.of("#15803D", "#4ADE80", "#FDE047")),
            new GradientPreset("sunset", "Sunset", Material.SUNFLOWER, List.of("#F43F5E", "#FB923C", "#FACC15")),
            new GradientPreset("amethyst", "Amethyst", Material.AMETHYST_CLUSTER, List.of("#7E22CE", "#C084FC", "#F472B6"))
    );

    private static final int[] COLOR_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29
    };
    private static final int[] STYLE_SLOTS = {30, 31, 32, 33, 34};
    private static final int[] GRADIENT_SLOTS = {37, 38, 39, 40, 41, 42};
    private static final int PREVIEW_SLOT = 4;
    private static final int RESET_SLOT = 45;
    private static final int SAVE_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final PluginStart plugin;
    private final ChatService chat;

    public NicknameEditorGui(PluginStart plugin, ChatService chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    public void enable() {
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, codes.castled.allium.PluginStart.bukkitPlugin(plugin));
        }
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        if (plugin.getServer() != null) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                UtilitySupport.onOwner(plugin, online, () -> {
                    if (online.isOnline() && online.getOpenInventory().getTopInventory().getHolder() instanceof EditorHolder) {
                        online.closeInventory();
                    }
                });
            }
        }
    }

    /** Opens the editor for {@code viewer} targeting {@code targetId} / {@code targetName}. */
    public void open(Player viewer, UUID targetId, String targetName, @Nullable String currentRawNickname) {
        String plainBase = stripPlainBase(currentRawNickname, targetName);
        EditorHolder holder = new EditorHolder(viewer.getUniqueId(), targetId, targetName, plainBase);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GRAY + "Edit Nickname: " + targetName);
        holder.inventory = inventory;
        render(viewer, holder);
        UtilitySupport.onOwner(plugin, viewer, () -> {
            if (viewer.isOnline()) {
                viewer.openInventory(inventory);
            }
        });
    }

    /**
     * Composes a nickname string from a plain base name, optional color code OR gradient preset,
     * and active formatting styles.
     */
    public static String compose(String plainBase, @Nullable Character colorCode,
                                 @Nullable GradientPreset gradient, Set<StyleFlag> styles) {
        String base = plainBase == null ? "" : plainBase.trim();
        if (base.isEmpty()) {
            return "";
        }
        StringBuilder stylePrefix = new StringBuilder();
        for (StyleFlag flag : StyleFlag.values()) {
            if (styles != null && styles.contains(flag)) {
                stylePrefix.append('&').append(flag.code());
            }
        }
        if (gradient != null) {
            return stylePrefix + gradient.openingTag() + base + "</gradient>";
        }
        if (colorCode != null) {
            return "&" + colorCode + stylePrefix + base;
        }
        return stylePrefix + base;
    }

    static String stripPlainBase(@Nullable String rawNickname, String fallbackName) {
        if (rawNickname == null || rawNickname.isBlank()) {
            return fallbackName;
        }
        String plain = TextFormatter.parse(rawNickname).toPlainText().trim();
        return plain.isEmpty() ? fallbackName : plain;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)
                || !viewer.getUniqueId().equals(holder.viewerId)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            viewer.closeInventory();
            return;
        }
        if (slot == RESET_SLOT) {
            viewer.closeInventory();
            chat.applyNicknameFromGui(viewer, holder.targetId, null);
            return;
        }
        if (slot == SAVE_SLOT) {
            String composed = holder.composed();
            viewer.closeInventory();
            chat.applyNicknameFromGui(viewer, holder.targetId, composed);
            return;
        }
        Integer colorIndex = holder.colorSlots.get(slot);
        if (colorIndex != null) {
            if (!viewer.hasPermission("allium.nick.color")) {
                plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION, "{cmd}", "nickname colors");
                return;
            }
            ColorOption chosen = COLORS.get(colorIndex);
            holder.gradient = null;
            holder.colorCode = (holder.colorCode != null && holder.colorCode == chosen.code()) ? null : chosen.code();
            render(viewer, holder);
            return;
        }
        Integer styleIndex = holder.styleSlots.get(slot);
        if (styleIndex != null) {
            if (!viewer.hasPermission("allium.nick.color")) {
                plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION, "{cmd}", "nickname styles");
                return;
            }
            StyleFlag flag = StyleFlag.values()[styleIndex];
            if (!holder.styles.remove(flag)) {
                holder.styles.add(flag);
            }
            render(viewer, holder);
            return;
        }
        Integer gradientIndex = holder.gradientSlots.get(slot);
        if (gradientIndex != null) {
            if (!viewer.hasPermission("allium.nick.gradient")) {
                plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION, "{cmd}", "nickname gradients");
                return;
            }
            GradientPreset preset = GRADIENTS.get(gradientIndex);
            holder.colorCode = null;
            holder.gradient = (holder.gradient == preset) ? null : preset;
            render(viewer, holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    private void render(Player viewer, EditorHolder holder) {
        Inventory inv = holder.inventory;
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        String composed = holder.composed();
        String rendered = TextFormatter.parse(composed).toLegacy();
        inv.setItem(PREVIEW_SLOT, item(Material.NAME_TAG,
                ChatColor.YELLOW + "Preview: " + ChatColor.RESET + rendered,
                List.of(
                        ChatColor.GRAY + "Target: " + ChatColor.WHITE + holder.targetName,
                        ChatColor.GRAY + "Format: " + ChatColor.DARK_GRAY + composed,
                        ChatColor.GRAY + "Click colors, styles, or gradients below."
                )));

        boolean allowColor = viewer.hasPermission("allium.nick.color");
        for (int i = 0; i < COLORS.size(); i++) {
            ColorOption option = COLORS.get(i);
            boolean selected = holder.colorCode != null && holder.colorCode == option.code();
            String label = ChatColor.translateAlternateColorCodes('&', "&" + option.code() + option.name())
                    + (selected ? ChatColor.GREEN + " (Selected)" : "");
            List<String> lore = allowColor
                    ? List.of(ChatColor.GRAY + "Code: &" + option.code(), ChatColor.YELLOW + "Click to " + (selected ? "clear" : "select"))
                    : List.of(ChatColor.RED + "Requires allium.nick.color");
            inv.setItem(COLOR_SLOTS[i], item(option.icon(), label, lore));
        }

        StyleFlag[] allStyles = StyleFlag.values();
        for (int i = 0; i < allStyles.length; i++) {
            StyleFlag flag = allStyles[i];
            boolean active = holder.styles.contains(flag);
            String label = (active ? ChatColor.GREEN : ChatColor.WHITE) + flag.displayName()
                    + (active ? ChatColor.GRAY + " [ON]" : ChatColor.DARK_GRAY + " [OFF]");
            List<String> lore = allowColor
                    ? List.of(ChatColor.GRAY + "Code: &" + flag.code(), ChatColor.YELLOW + "Click to toggle")
                    : List.of(ChatColor.RED + "Requires allium.nick.color");
            inv.setItem(STYLE_SLOTS[i], item(flag.icon(), label, lore));
        }

        boolean allowGradient = viewer.hasPermission("allium.nick.gradient");
        for (int i = 0; i < GRADIENTS.size(); i++) {
            GradientPreset preset = GRADIENTS.get(i);
            boolean selected = holder.gradient == preset;
            String label = TextFormatter.parse(preset.openingTag() + preset.name() + "</gradient>").toLegacy()
                    + (selected ? ChatColor.GREEN + " (Selected)" : "");
            List<String> lore = allowGradient
                    ? List.of(ChatColor.GRAY + "Preset gradient", ChatColor.YELLOW + "Click to " + (selected ? "clear" : "select"))
                    : List.of(ChatColor.RED + "Requires allium.nick.gradient");
            inv.setItem(GRADIENT_SLOTS[i], item(preset.icon(), label, lore));
        }

        inv.setItem(RESET_SLOT, item(Material.BARRIER, ChatColor.RED + "Reset Nickname",
                List.of(ChatColor.GRAY + "Clears custom nickname formatting.")));
        inv.setItem(SAVE_SLOT, item(Material.EMERALD_BLOCK, ChatColor.GREEN + "Save Nickname",
                List.of(ChatColor.GRAY + "Applies: " + ChatColor.RESET + rendered)));
        inv.setItem(CLOSE_SLOT, item(Material.DARK_OAK_DOOR, ChatColor.GRAY + "Close",
                List.of(ChatColor.DARK_GRAY + "Exit without saving")));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) {
                meta.setLore(new ArrayList<>(lore));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static final class EditorHolder implements InventoryHolder {
        final UUID viewerId;
        final UUID targetId;
        final String targetName;
        final String plainBase;
        final Map<Integer, Integer> colorSlots = new LinkedHashMap<>();
        final Map<Integer, Integer> styleSlots = new LinkedHashMap<>();
        final Map<Integer, Integer> gradientSlots = new LinkedHashMap<>();
        final EnumSet<StyleFlag> styles = EnumSet.noneOf(StyleFlag.class);
        @Nullable Character colorCode;
        @Nullable GradientPreset gradient;
        Inventory inventory;

        EditorHolder(UUID viewerId, UUID targetId, String targetName, String plainBase) {
            this.viewerId = viewerId;
            this.targetId = targetId;
            this.targetName = targetName;
            this.plainBase = plainBase;
            for (int i = 0; i < COLORS.size(); i++) colorSlots.put(COLOR_SLOTS[i], i);
            for (int i = 0; i < StyleFlag.values().length; i++) styleSlots.put(STYLE_SLOTS[i], i);
            for (int i = 0; i < GRADIENTS.size(); i++) gradientSlots.put(GRADIENT_SLOTS[i], i);
        }

        String composed() {
            return compose(plainBase, colorCode, gradient, styles);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
