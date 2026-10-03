package codes.castled.allium.packetevents.impl;

import codes.castled.allium.tradingcards.item.CardTooltipStyle;
import codes.castled.allium.tradingcards.item.FrameMarker;
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
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Draws the card frame on any item whose lore carries a {@code [frame:...]}
 * marker, by rewriting the copy of the item sent to the player.
 *
 * <p>Only the outgoing copy changes, so a shop or menu plugin that owns the item
 * still reads back exactly what it wrote. The rewrite does what such a plugin
 * cannot: frames every lore line, moves the item's name into the frame as its
 * title and blanks the real name line, points the tooltip style at the pack's
 * empty panel, and hides the component lines the client would otherwise draw
 * between the name and the lore, where they would land on the frame's header.
 *
 * <p>Creative players are sent the item as it is. A creative client sends items
 * back to the server whole, so a framed copy would be saved over the real one.
 * The inventory is re-sent on a switch into or out of creative so it never holds
 * the wrong version.
 *
 * <p>Loaded by name only when PacketEvents is installed, like the other
 * listeners in this package.
 */
public final class FrameMarkerPacketListener extends PacketListenerAbstract implements Listener {

    private static final ResourceLocation STYLE = new ResourceLocation(
        CardTooltipStyle.STYLE.namespace(), CardTooltipStyle.STYLE.value());

    /**
     * Everything the client draws between the name and the lore, from the 26.3
     * client's {@code addDetailsToTooltip}, plus the attribute and unbreakable
     * lines, which would otherwise hang under the frame's bottom cap. Hiding a
     * component only stops its line being drawn: an enchanted item still glints.
     */
    private static final List<ComponentType<?>> HIDDEN = List.of(
        ComponentTypes.TROPICAL_FISH_PATTERN, ComponentTypes.INSTRUMENT, ComponentTypes.MAP_ID,
        ComponentTypes.BEES, ComponentTypes.CONTAINER_LOOT, ComponentTypes.CONTAINER,
        ComponentTypes.BUNDLE_CONTENTS, ComponentTypes.BANNER_PATTERNS,
        ComponentTypes.POT_DECORATIONS, ComponentTypes.WRITTEN_BOOK_CONTENT,
        ComponentTypes.CHARGED_PROJECTILES, ComponentTypes.FIREWORKS,
        ComponentTypes.FIREWORK_EXPLOSION, ComponentTypes.POTION_CONTENTS,
        ComponentTypes.JUKEBOX_PLAYABLE, ComponentTypes.TRIM, ComponentTypes.STORED_ENCHANTMENTS,
        ComponentTypes.ENCHANTMENTS, ComponentTypes.DYED_COLOR, ComponentTypes.PROFILE,
        ComponentTypes.ATTRIBUTE_MODIFIERS, ComponentTypes.UNBREAKABLE);

    private final Logger logger;
    private volatile boolean failed;

    private FrameMarkerPacketListener(Logger logger) {
        super(PacketListenerPriority.NORMAL);
        this.logger = logger;
    }

    /** Registers the listener. Called by name, so this class only loads when needed. */
    public static void install(Plugin plugin) {
        FrameMarkerPacketListener listener = new FrameMarkerPacketListener(plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (failed || (type != PacketType.Play.Server.WINDOW_ITEMS
            && type != PacketType.Play.Server.SET_SLOT
            && type != PacketType.Play.Server.SET_CURSOR_ITEM
            && type != PacketType.Play.Server.SET_PLAYER_INVENTORY)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)
            || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        try {
            if (rewrite(event, type)) {
                event.markForReEncode(true);
            }
        } catch (Throwable t) {
            // A PacketEvents build that cannot decode this version's items would
            // throw on every inventory packet; once is enough to know.
            failed = true;
            logger.warning("Frame markers are off: could not read an item packet ("
                + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    private boolean rewrite(PacketSendEvent event, PacketTypeCommon type) {
        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            boolean changed = false;
            List<ItemStack> items = new ArrayList<>(packet.getItems());
            for (ItemStack item : items) {
                changed |= frame(item);
            }
            var carried = packet.getCarriedItem();
            if (carried.isPresent()) {
                changed |= frame(carried.get());
            }
            if (changed) {
                packet.setItems(items);
                carried.ifPresent(packet::setCarriedItem);
            }
            return changed;
        }
        if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            return frame(packet.getItem());
        }
        if (type == PacketType.Play.Server.SET_CURSOR_ITEM) {
            return frame(new WrapperPlayServerSetCursorItem(event).getStack());
        }
        return frame(new WrapperPlayServerSetPlayerInventory(event).getStack());
    }

    /** Frames one item in place. False when it carries no marker. */
    private static boolean frame(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        ItemLore lore = item.getComponentOr(ComponentTypes.LORE, null);
        if (lore == null) {
            return false;
        }
        List<Component> lines = lore.getLines();
        FrameMarker.Match match = FrameMarker.find(lines);
        if (match == null) {
            return false;
        }
        Component title = title(item);
        item.setComponent(ComponentTypes.LORE, new ItemLore(FrameMarker.frame(title, lines, match)));
        item.setComponent(ComponentTypes.ITEM_NAME, CardTooltipStyle.hiddenName(title));
        item.unsetComponent(ComponentTypes.CUSTOM_NAME);
        item.setComponent(ComponentTypes.TOOLTIP_STYLE, new ItemTooltipStyle(STYLE));
        ItemTooltipDisplay display = item.getComponentOr(ComponentTypes.TOOLTIP_DISPLAY, null);
        Set<ComponentType<?>> hidden = new HashSet<>(HIDDEN);
        if (display != null) {
            hidden.addAll(display.getHiddenComponents());
        }
        item.setComponent(ComponentTypes.TOOLTIP_DISPLAY,
            new ItemTooltipDisplay(display != null && display.isHideTooltip(), hidden));
        return true;
    }

    /**
     * The item's name, styled the way the name line would have drawn it.
     *
     * <p>Lore draws dark purple and italic unless told otherwise, so the name
     * keeps the defaults it had in the name line: white, and italic only when
     * it is a custom name, which vanilla italicises.
     */
    private static Component title(ItemStack item) {
        Component custom = item.getComponentOr(ComponentTypes.CUSTOM_NAME, null);
        if (custom != null) {
            return custom.applyFallbackStyle(Style.style(NamedTextColor.WHITE, TextDecoration.ITALIC));
        }
        Style plain = Style.style(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
        Component name = item.getComponentOr(ComponentTypes.ITEM_NAME, null);
        if (name != null) {
            return name.applyFallbackStyle(plain);
        }
        Material material = Material.matchMaterial(item.getType().getName().toString());
        return material == null
            ? Component.text(item.getType().getName().getKey(), plain)
            : Component.translatable(material.translationKey(), plain);
    }

    /**
     * Re-sends the inventory when a player enters or leaves creative, so a
     * creative client never holds a framed copy it could send back as real.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        boolean wasCreative = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        boolean nowCreative = event.getNewGameMode() == GameMode.CREATIVE;
        if (wasCreative != nowCreative) {
            Player player = event.getPlayer();
            codes.castled.allium.scheduler.SchedulerAdapter.runEntity(
                codes.castled.allium.PluginStart.getInstance(), player, player::updateInventory, null);
        }
    }
}
