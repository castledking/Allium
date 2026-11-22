package codes.castled.curios;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipDisplay;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipStyle;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Draws a frame on the outgoing copy. The real item keeps its name and original lore. */
final class FrameItemPackets extends PacketListenerAbstract implements AutoCloseable {
    private static final ResourceLocation TOOLTIP = new ResourceLocation("curios:card");
    private static final List<ComponentType<?>> ADDITIONAL_TOOLTIP_LINES = List.of(
            ComponentTypes.TROPICAL_FISH_PATTERN, ComponentTypes.INSTRUMENT, ComponentTypes.MAP_ID,
            ComponentTypes.BEES, ComponentTypes.CONTAINER_LOOT, ComponentTypes.CONTAINER,
            ComponentTypes.BUNDLE_CONTENTS, ComponentTypes.BANNER_PATTERNS, ComponentTypes.POT_DECORATIONS,
            ComponentTypes.WRITTEN_BOOK_CONTENT, ComponentTypes.CHARGED_PROJECTILES, ComponentTypes.FIREWORKS,
            ComponentTypes.FIREWORK_EXPLOSION, ComponentTypes.POTION_CONTENTS, ComponentTypes.JUKEBOX_PLAYABLE,
            ComponentTypes.TRIM, ComponentTypes.STORED_ENCHANTMENTS, ComponentTypes.ENCHANTMENTS,
            ComponentTypes.DYED_COLOR, ComponentTypes.PROFILE, ComponentTypes.ATTRIBUTE_MODIFIERS, ComponentTypes.UNBREAKABLE);
    private final CuriosPlugin plugin;
    private volatile boolean warned;

    FrameItemPackets(CuriosPlugin plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !(event.getPlayer() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) return;
        var type = event.getPacketType();
        try {
            if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                var packet = new WrapperPlayServerWindowItems(event);
                var items = packet.getItems();
                var copies = items.stream().map(item -> frame(item, player)).toList();
                var carried = packet.getCarriedItem().orElse(null);
                var newCarried = frame(carried, player);
                boolean changed = carried != newCarried;
                for (int i = 0; i < items.size() && !changed; i++) changed = items.get(i) != copies.get(i);
                if (!changed) return;
                packet.setItems(copies);
                if (newCarried != null) packet.setCarriedItem(newCarried);
            } else if (type == PacketType.Play.Server.SET_SLOT) {
                var packet = new WrapperPlayServerSetSlot(event);
                var before = packet.getItem();
                var after = frame(before, player);
                if (after == before) return;
                packet.setItem(after);
            } else if (type == PacketType.Play.Server.SET_CURSOR_ITEM) {
                var packet = new WrapperPlayServerSetCursorItem(event);
                var before = packet.getStack();
                var after = frame(before, player);
                if (after == before) return;
                packet.setStack(after);
            } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
                var packet = new WrapperPlayServerSetPlayerInventory(event);
                var before = packet.getStack();
                var after = frame(before, player);
                if (after == before) return;
                packet.setStack(after);
            } else return;
            event.markForReEncode(true);
        } catch (RuntimeException | LinkageError failure) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Could not render an item frame: " + failure);
            }
        }
    }

    private ItemStack frame(ItemStack item, Player viewer) {
        if (item == null || item.isEmpty()) return item;
        if (item.getVersion().isOlderThan(ClientVersion.V_1_20_5)) return legacy(item, viewer);
        var lore = item.getComponentOr(ComponentTypes.LORE, null);
        if (lore == null) return item;
        Component name = item.getComponentOr(ComponentTypes.CUSTOM_NAME, null);
        if (name == null) name = item.getComponentOr(ComponentTypes.ITEM_NAME, null);
        var framed = draw(item, viewer, name, lore.getLines());
        if (framed == null) return item;
        ItemStack copy = item.copy();
        copy.setComponent(ComponentTypes.LORE, new ItemLore(framed));
        copy.unsetComponent(ComponentTypes.CUSTOM_NAME);
        copy.setComponent(ComponentTypes.ITEM_NAME, Component.text(" "));
        if (item.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            copy.setComponent(ComponentTypes.TOOLTIP_STYLE, new ItemTooltipStyle(TOOLTIP));
        }
        if (item.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            var display = item.getComponentOr(ComponentTypes.TOOLTIP_DISPLAY, null);
            var hidden = new HashSet<>(ADDITIONAL_TOOLTIP_LINES);
            if (display != null) hidden.addAll(display.getHiddenComponents());
            copy.setComponent(ComponentTypes.TOOLTIP_DISPLAY, new ItemTooltipDisplay(display != null && display.isHideTooltip(), hidden));
        }
        return copy;
    }

    private ItemStack legacy(ItemStack item, Player viewer) {
        var tag = item.getNBT();
        var display = tag == null ? null : tag.getCompoundTagOrNull("display");
        var lore = display == null ? null : display.getStringListTagOrNull("Lore");
        if (lore == null || lore.getTags().stream().noneMatch(line -> line.getValue().toLowerCase(java.util.Locale.ROOT).contains("frame"))) return item;
        String name = display.getStringTagValueOrNull("Name");
        var lines = lore.getTags().stream().map(line -> AdventureSerializer.parseComponent(line.getValue())).toList();
        var framed = draw(item, viewer, name == null ? null : AdventureSerializer.parseComponent(name), lines);
        if (framed == null) return item;
        ItemStack copy = item.copy();
        var copiedDisplay = copy.getNBT().getCompoundTagOrThrow("display");
        copiedDisplay.setTag("Name", new NBTString(AdventureSerializer.toJson(Component.text(" "))));
        copiedDisplay.setTag("Lore", new NBTList<>(NBTType.STRING,
                framed.stream().map(line -> new NBTString(AdventureSerializer.toJson(line))).toList()));
        return copy;
    }

    private List<Component> draw(ItemStack item, Player viewer, Component name, List<Component> lore) {
        String frame = null;
        var content = new ArrayList<Component>();
        for (Component line : lore) {
            String marker = ItemFrameMarker.style(plain(line));
            if (marker == null) content.add(line);
            else if (frame == null) frame = marker;
        }
        if (frame == null) return null;
        if (name == null) {
            var material = Material.matchMaterial(item.getType().getName().toString());
            name = material == null ? Component.text(item.getType().getName().getKey())
                    : Component.translatable(material.getTranslationKey());
        }
        content.add(0, name.colorIfAbsent(NamedTextColor.WHITE).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        int width = content.stream().mapToInt(line -> TextWidth.of(plain(line))).max().orElse(0);
        var panel = FramePanels.bundled().panel(frame, plugin.automaticFrame(viewer), content.size(), width, 0);
        if (panel == null) return null;
        var shown = new ArrayList<Component>();
        shown.add(glyph(panel.header(), panel.font()));
        for (int i = 0; i < content.size(); i++) {
            shown.add(Component.empty().append(glyph(panel.prefixes().get(i), panel.font())).append(content.get(i)));
        }
        shown.add(glyph(panel.last(), panel.font()));
        if (panel.blankAfter()) shown.add(Component.empty());
        return shown;
    }

    private static String plain(Component line) {
        return ChatColor.stripColor(AdventureSerializer.toLegacyFormat(line));
    }

    private static Component glyph(String value, String font) {
        return Component.text(value, NamedTextColor.WHITE).font(Key.key(font)).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void close() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }
}
