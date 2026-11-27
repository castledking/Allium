package codes.castled.allium.legacy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /editnick [player]} on legacy Bukkit: the modern nickname styling GUI. Players pick a
 * colour or a gradient and toggle styles, see a preview, and save; saving goes through /nick's
 * rules. Gradients need hex colours, so they are shown on 1.16+ only; on older versions the
 * gradient row is left out. Icons use each version's own item names.
 */
final class LegacyNicknameEditor implements Listener {

    enum Style {
        BOLD('l', "Bold", "ANVIL", null, (short) 0),
        ITALIC('o', "Italic", "FEATHER", null, (short) 0),
        UNDERLINE('n', "Underline", "STICK", null, (short) 0),
        STRIKETHROUGH('m', "Strikethrough", "IRON_BARS", "IRON_FENCE", (short) 0),
        MAGIC('k', "Magic", "AMETHYST_SHARD", "ENDER_PEARL", (short) 0);

        final char code;
        final String label;
        final String modern;
        final String legacy;
        final short data;

        Style(char code, String label, String modern, String legacy, short data) {
            this.code = code;
            this.label = label;
            this.modern = modern;
            this.legacy = legacy;
            this.data = data;
        }
    }

    static final class Colour {
        final char code;
        final String name;
        final String modern;
        final String legacy;
        final short data;

        Colour(char code, String name, String modern, String legacy, int data) {
            this.code = code;
            this.name = name;
            this.modern = modern;
            this.legacy = legacy;
            this.data = (short) data;
        }
    }

    static final class Gradient {
        final String name;
        final String modern;
        final String legacy;
        final short data;
        final List<String> stops;

        Gradient(String name, String modern, String legacy, int data, String... stops) {
            this.name = name;
            this.modern = modern;
            this.legacy = legacy;
            this.data = (short) data;
            this.stops = Arrays.asList(stops);
        }
    }

    static final List<Colour> COLOURS = Collections.unmodifiableList(Arrays.asList(
            new Colour('a', "Green", "LIME_WOOL", "WOOL", 5),
            new Colour('b', "Aqua", "LIGHT_BLUE_WOOL", "WOOL", 3),
            new Colour('c', "Red", "RED_WOOL", "WOOL", 14),
            new Colour('d', "Light Purple", "MAGENTA_WOOL", "WOOL", 2),
            new Colour('e', "Yellow", "YELLOW_WOOL", "WOOL", 4),
            new Colour('f', "White", "WHITE_WOOL", "WOOL", 0),
            new Colour('1', "Dark Blue", "BLUE_WOOL", "WOOL", 11),
            new Colour('2', "Dark Green", "GREEN_WOOL", "WOOL", 13),
            new Colour('3', "Dark Aqua", "CYAN_WOOL", "WOOL", 9),
            new Colour('4', "Dark Red", "RED_TERRACOTTA", "STAINED_CLAY", 14),
            new Colour('5', "Dark Purple", "PURPLE_WOOL", "WOOL", 10),
            new Colour('6', "Gold", "ORANGE_WOOL", "WOOL", 1),
            new Colour('7', "Gray", "LIGHT_GRAY_WOOL", "WOOL", 8),
            new Colour('8', "Dark Gray", "GRAY_WOOL", "WOOL", 7),
            new Colour('9', "Blue", "CORNFLOWER", "INK_SACK", 4),
            new Colour('0', "Black", "BLACK_WOOL", "WOOL", 15)));

    static final List<Gradient> GRADIENTS = Collections.unmodifiableList(Arrays.asList(
            new Gradient("Rainbow", "PRISMARINE_CRYSTALS", null, 0, "#FF5555", "#FFAA00", "#FFFF55", "#55FF55", "#5555FF", "#AA00AA"),
            new Gradient("Fire", "BLAZE_POWDER", null, 0, "#FF3300", "#FF8800", "#FFEE33"),
            new Gradient("Ocean", "HEART_OF_THE_SEA", "PRISMARINE_SHARD", 0, "#1E40AF", "#06B6D4", "#E0F2FE"),
            new Gradient("Forest", "OAK_SAPLING", "SAPLING", 0, "#15803D", "#4ADE80", "#FDE047"),
            new Gradient("Sunset", "SUNFLOWER", "DOUBLE_PLANT", 0, "#F43F5E", "#FB923C", "#FACC15"),
            new Gradient("Amethyst", "AMETHYST_CLUSTER", "PURPUR_BLOCK", 0, "#7E22CE", "#C084FC", "#F472B6")));

    private static final int[] COLOUR_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29};
    private static final int[] STYLE_SLOTS = {30, 31, 32, 33, 34};
    private static final int[] GRADIENT_SLOTS = {37, 38, 39, 40, 41, 42};
    private static final int PREVIEW = 4, RESET = 45, SAVE = 49, CLOSE = 53;

    private final LegacyPlayerInfoModule nicknames;
    private final LegacyMessageManager messages;

    LegacyNicknameEditor(LegacyPlayerInfoModule nicknames, LegacyMessageManager messages) {
        this.nicknames = nicknames;
        this.messages = messages;
    }

    /** {@code /editnick [player]}. */
    boolean command(org.bukkit.command.CommandSender sender, String[] args, java.util.function.Function<String, Player> findOnline) {
        if (!sender.hasPermission("allium.editnick")) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only", "{prefix} &cThis command can only be used by players.");
            return true;
        }
        if (args.length > 1) return false;
        Player viewer = (Player) sender;
        Player target = viewer;
        if (args.length == 1) {
            if (!viewer.hasPermission("allium.nick.others") && !viewer.hasPermission("allium.nick.other")) {
                messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
                return true;
            }
            target = findOnline.apply(args[0]);
            if (target == null || !viewer.canSee(target)) {
                messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", args[0]);
                return true;
            }
        }
        open(viewer, target);
        return true;
    }

    void open(Player viewer, Player target) {
        String current = nicknames.nicknameOf(target.getUniqueId());
        String plain = current == null ? "" : ChatColor.stripColor(LegacyCompat.color(current)).trim();
        Holder holder = new Holder(viewer.getUniqueId(), target.getUniqueId(), target.getName(), plain.isEmpty() ? target.getName() : plain,
                LegacyCompat.supportsHexColor());
        holder.inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GRAY + "Edit Nickname: " + target.getName());
        render(viewer, holder);
        viewer.openInventory(holder.inventory);
    }

    /** A nickname from a plain name, a colour or a gradient, and styles; colour codes are {@code &}-codes. */
    static String compose(String plain, Character colour, Gradient gradient, Set<Style> styles) {
        String base = plain == null ? "" : plain.trim();
        if (base.isEmpty()) return "";
        StringBuilder styleCodes = new StringBuilder();
        for (Style style : Style.values()) if (styles.contains(style)) styleCodes.append('&').append(style.code);
        if (gradient != null) {
            // Each letter gets its own hex colour; a colour code clears styles, so they follow it.
            StringBuilder out = new StringBuilder();
            int[] points = base.codePoints().toArray();
            for (int i = 0; i < points.length; i++) {
                out.append("&#").append(colourAt(gradient.stops, points.length == 1 ? 0 : (double) i / (points.length - 1)))
                        .append(styleCodes).appendCodePoint(points[i]);
            }
            return out.toString();
        }
        if (colour != null) return "&" + colour + styleCodes + base;
        return styleCodes + base;
    }

    static String colourAt(List<String> stops, double position) {
        double scaled = position * (stops.size() - 1);
        int index = Math.min(stops.size() - 2, (int) Math.floor(scaled));
        double t = scaled - index;
        int from = Integer.parseInt(stops.get(index).substring(1), 16);
        int to = Integer.parseInt(stops.get(index + 1).substring(1), 16);
        int r = mix(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = mix(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = mix(from & 0xFF, to & 0xFF, t);
        return String.format("%02X%02X%02X", r, g, b);
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
        event.setCancelled(true);
        Holder holder = (Holder) event.getView().getTopInventory().getHolder();
        if (!(event.getWhoClicked() instanceof Player) || !event.getWhoClicked().getUniqueId().equals(holder.viewer)
                || event.getRawSlot() >= event.getView().getTopInventory().getSize() || event.getRawSlot() < 0) return;
        Player viewer = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == CLOSE) {
            viewer.closeInventory();
        } else if (slot == RESET) {
            viewer.closeInventory();
            nicknames.setNicknameFromEditor(viewer, holder.target, holder.targetName, null);
        } else if (slot == SAVE) {
            viewer.closeInventory();
            nicknames.setNicknameFromEditor(viewer, holder.target, holder.targetName, holder.composed());
        } else if (index(COLOUR_SLOTS, slot) >= 0 && index(COLOUR_SLOTS, slot) < COLOURS.size()) {
            if (!allowed(viewer, "allium.nick.color", "nickname colors")) return;
            Colour chosen = COLOURS.get(index(COLOUR_SLOTS, slot));
            holder.gradient = null;
            holder.colour = holder.colour != null && holder.colour == chosen.code ? null : chosen.code;
            render(viewer, holder);
        } else if (index(STYLE_SLOTS, slot) >= 0) {
            if (!allowed(viewer, "allium.nick.color", "nickname styles")) return;
            Style style = Style.values()[index(STYLE_SLOTS, slot)];
            if (!holder.styles.remove(style)) holder.styles.add(style);
            render(viewer, holder);
        } else if (holder.hex && index(GRADIENT_SLOTS, slot) >= 0) {
            if (!allowed(viewer, "allium.nick.gradient", "nickname gradients")) return;
            Gradient chosen = GRADIENTS.get(index(GRADIENT_SLOTS, slot));
            holder.colour = null;
            holder.gradient = holder.gradient == chosen ? null : chosen;
            render(viewer, holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private boolean allowed(Player viewer, String permission, String what) {
        if (viewer.hasPermission(permission)) return true;
        messages.send(viewer, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", what);
        return false;
    }

    private static int index(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }

    private void render(Player viewer, Holder holder) {
        Inventory inv = holder.inventory;
        ItemStack filler = icon("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE", (short) 7, " ", Collections.<String>emptyList());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        String composed = holder.composed();
        String rendered = LegacyCompat.color(composed);
        inv.setItem(PREVIEW, icon("NAME_TAG", null, (short) 0, ChatColor.YELLOW + "Preview: " + ChatColor.RESET + rendered, Arrays.asList(
                ChatColor.GRAY + "Target: " + ChatColor.WHITE + holder.targetName,
                ChatColor.GRAY + "Click colors, styles" + (holder.hex ? ", or gradients" : "") + " below.")));
        boolean colours = viewer.hasPermission("allium.nick.color");
        for (int i = 0; i < COLOURS.size(); i++) {
            Colour option = COLOURS.get(i);
            boolean selected = holder.colour != null && holder.colour == option.code;
            String label = ChatColor.translateAlternateColorCodes('&', "&" + option.code + option.name) + (selected ? ChatColor.GREEN + " (Selected)" : "");
            List<String> lore = colours
                    ? Arrays.asList(ChatColor.GRAY + "Code: &" + option.code, ChatColor.YELLOW + "Click to " + (selected ? "clear" : "select"))
                    : Collections.singletonList(ChatColor.RED + "Requires allium.nick.color");
            inv.setItem(COLOUR_SLOTS[i], icon(option.modern, option.legacy, option.data, label, lore));
        }
        Style[] styles = Style.values();
        for (int i = 0; i < styles.length; i++) {
            boolean on = holder.styles.contains(styles[i]);
            String label = (on ? ChatColor.GREEN : ChatColor.WHITE) + styles[i].label + (on ? ChatColor.GRAY + " [ON]" : ChatColor.DARK_GRAY + " [OFF]");
            List<String> lore = colours
                    ? Arrays.asList(ChatColor.GRAY + "Code: &" + styles[i].code, ChatColor.YELLOW + "Click to toggle")
                    : Collections.singletonList(ChatColor.RED + "Requires allium.nick.color");
            inv.setItem(STYLE_SLOTS[i], icon(styles[i].modern, styles[i].legacy, styles[i].data, label, lore));
        }
        if (holder.hex) {
            boolean gradients = viewer.hasPermission("allium.nick.gradient");
            for (int i = 0; i < GRADIENTS.size(); i++) {
                Gradient preset = GRADIENTS.get(i);
                boolean selected = holder.gradient == preset;
                String label = LegacyCompat.color(compose(preset.name, null, preset, Collections.<Style>emptySet()))
                        + (selected ? ChatColor.GREEN + " (Selected)" : "");
                List<String> lore = gradients
                        ? Arrays.asList(ChatColor.GRAY + "Preset gradient", ChatColor.YELLOW + "Click to " + (selected ? "clear" : "select"))
                        : Collections.singletonList(ChatColor.RED + "Requires allium.nick.gradient");
                inv.setItem(GRADIENT_SLOTS[i], icon(preset.modern, preset.legacy, preset.data, label, lore));
            }
        }
        inv.setItem(RESET, icon("BARRIER", null, (short) 0, ChatColor.RED + "Reset Nickname",
                Collections.singletonList(ChatColor.GRAY + "Clears the custom nickname.")));
        inv.setItem(SAVE, icon("EMERALD_BLOCK", null, (short) 0, ChatColor.GREEN + "Save Nickname",
                Collections.singletonList(ChatColor.GRAY + "Applies: " + ChatColor.RESET + rendered)));
        inv.setItem(CLOSE, icon("DARK_OAK_DOOR", "DARK_OAK_DOOR_ITEM", (short) 0, ChatColor.GRAY + "Close",
                Collections.singletonList(ChatColor.DARK_GRAY + "Exit without saving")));
    }

    /** The item named {@code modern} on 1.13+, else {@code legacy} (or the same name) with its data value. */
    @SuppressWarnings("deprecation")
    static ItemStack icon(String modern, String legacy, short data, String name, List<String> lore) {
        Material material = Material.matchMaterial(modern);
        ItemStack stack;
        if (material != null) {
            stack = new ItemStack(material);
        } else {
            Material old = Material.matchMaterial(legacy == null ? modern : legacy);
            stack = old == null ? new ItemStack(Material.PAPER) : new ItemStack(old, 1, data);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(new ArrayList<>(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static final class Holder implements InventoryHolder {
        final UUID viewer;
        final UUID target;
        final String targetName;
        final String plain;
        final boolean hex;
        final Set<Style> styles = EnumSet.noneOf(Style.class);
        Character colour;
        Gradient gradient;
        Inventory inventory;

        Holder(UUID viewer, UUID target, String targetName, String plain, boolean hex) {
            this.viewer = viewer;
            this.target = target;
            this.targetName = targetName;
            this.plain = plain;
            this.hex = hex;
        }

        String composed() {
            return compose(plain, colour, gradient, styles);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
