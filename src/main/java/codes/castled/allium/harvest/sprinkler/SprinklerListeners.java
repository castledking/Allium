package codes.castled.allium.harvest.sprinkler;

import codes.castled.allium.harvest.HarvestBranding;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.harvest.soil.SoilService;
import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Turns placing and breaking a sprinkler item into tracked sprinkler state.
 *
 * <p>The placed block itself is left entirely alone — the sprinkler is whatever
 * block the item places, and this listener only records that the block at that
 * position is a sprinkler of a given tier.
 */
public final class SprinklerListeners implements Listener {

    private final SprinklerRegistry registry;
    private final SprinklerService sprinklers;
    private final SoilService soils;
    private final ItemResolverChain items;

    public SprinklerListeners(SprinklerRegistry registry, SprinklerService sprinklers,
                              SoilService soils, ItemResolverChain items) {
        this.registry = registry;
        this.sprinklers = sprinklers;
        this.soils = soils;
        this.items = items;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        if (!sprinklers.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<ItemRef> ref = items.identify(event.getItemInHand());
        if (ref.isEmpty()) {
            return;
        }
        Optional<SprinklerDefinition> definition = registry.byItem(ref.get());
        if (definition.isEmpty()) {
            return;
        }
        if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".sprinkler.place")) {
            return;
        }
        Block block = event.getBlockPlaced();
        BlockPositionKey position = new BlockPositionKey(
            block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());

        Optional<PlacedSprinkler> placed = sprinklers.place(
            position, definition.get(), player.getUniqueId(), System.currentTimeMillis());
        if (placed.isEmpty()) {
            // The per-chunk cap exists so a player cannot make coverage lookups
            // expensive by carpeting a chunk in sprinklers.
            event.setCancelled(true);
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<red>Too many sprinklers in this chunk.</red>"));
            return;
        }
        player.sendActionBar(MiniMessage.miniMessage().deserialize(
            "<green>Sprinkler placed — watering <white>"
                + coverageDescription(definition.get()) + "</white>.</green>"));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (!sprinklers.isEnabled()) {
            return;
        }
        Block block = event.getBlock();
        BlockPositionKey position = new BlockPositionKey(
            block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        sprinklers.at(position).ifPresent(placed -> sprinklers.remove(position));
    }

    private static String coverageDescription(SprinklerDefinition definition) {
        int span = definition.radius() * 2 + 1;
        return span + "x" + span;
    }

    /** Exposed for the soil service to share the same key derivation. */
    public static BlockPositionKey keyOf(Block block) {
        return SoilService.keyOf(block);
    }
}
