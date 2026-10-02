package codes.castled.allium.tradingcards.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the card name above the hotbar while a card is held.
 *
 * <p>Read off the item rather than rebuilt, so the name always matches whatever
 * the item definition says. Cards are also named on the item itself now, so this
 * is a second place the name appears rather than the only one — kept because a
 * held card is the one you are looking at, and reading it from the hotbar slot
 * beats moving the mouse to it.
 *
 * <h2>Why this repeats rather than firing once</h2>
 *
 * <p>An action bar message fades after a couple of seconds, so a single send on
 * swap would be gone almost immediately. A slow repeat keeps it up for as long
 * as the card is held, and clears it the moment it is not — which is why this
 * reads the held slot every tick rather than tracking a "currently held" flag.
 * Nothing here writes to the item, so there is no packet traffic and no client
 * refresh; a swap event only triggers an immediate repaint so the name does not
 * wait out the timer.
 */
public class HeldCardNameListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * Ticks between refreshes, matched to how long the action bar stays up.
     *
     * <p>Refreshing on the bar's own lifetime rather than faster: a repaint
     * costs a packet per player per tick, and anything faster than the fade
     * buys nothing visible.
     */
    static final long PERIOD_TICKS = 40L;

    private final Plugin plugin;

    /** Players currently showing a name, so the clear only goes where needed. */
    private final Set<UUID> showing = ConcurrentHashMap.newKeySet();

    /** The last text shown per player, to avoid repainting an unchanged bar. */
    private final ConcurrentHashMap<UUID, String> lastShown = new ConcurrentHashMap<>();

    private codes.castled.allium.scheduler.TaskHandle task;

    public HeldCardNameListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Starts the refresh loop and registers the immediate-repaint events. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = codes.castled.allium.scheduler.SchedulerAdapter.runRepeatingGlobal(
            plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        showing.clear();
        lastShown.clear();
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = heldCardName(player);
            if (name == null) {
                clear(player);
            } else if (!name.equals(lastShown.get(player.getUniqueId()))) {
                show(player, name);
            }
        }
    }

    /** The name of the card in the player's main hand, or null. */
    private String heldCardName(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!TradingCardData.isCard(held)) {
            return null;
        }
        // Read the name off the item itself, so an operator renaming it in the
        // item definition gets the change here with no code edit.
        var meta = held.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            var name = meta.displayName();
            if (name != null) {
                return MM.serialize(name);
            }
        }
        // No custom name, so fall back to the mob rather than showing nothing.
        var card = TradingCardData.read(held).orElse(null);
        if (card == null) {
            return null;
        }
        return "<white>" + pretty(card.mob()) + " Trading Card</white>";
    }

    private void show(Player player, String name) {
        player.sendActionBar(MM.deserialize(name));
        lastShown.put(player.getUniqueId(), name);
        showing.add(player.getUniqueId());
    }

    /** Blanks the bar, but only for a player who actually had one up. */
    private void clear(Player player) {
        UUID id = player.getUniqueId();
        if (!showing.remove(id)) {
            return;
        }
        lastShown.remove(id);
        player.sendActionBar(Component.empty());
    }

    // ==================== immediate repaint ====================

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        // Painted on the next tick rather than in the event: the held slot has
        // not settled when the event fires.
        nextTick(event.getPlayer());
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        nextTick(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        nextTick(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        showing.remove(id);
        lastShown.remove(id);
    }

    /** Forces the bar to match what the player is holding right now. */
    private void repaint(Player player) {
        if (!player.isOnline()) {
            return;
        }
        String name = heldCardName(player);
        if (name == null) {
            clear(player);
        } else {
            show(player, name);
        }
    }

    /**
     * Repaints on the next tick.
     *
     * <p>Not in the event itself: on a slot change the new held item is not the
     * one the player is actually holding until the tick ends, so reading it
     * during the event would show the outgoing card.
     */
    private void nextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> repaint(player));
    }

    /**
     * Capitalises a Bukkit entity type for display.
     *
     * <p>Every word, not just the first: lowercasing the whole string turns
     * MAGMA_CUBE into "Magma cube", which is the sort of thing that looks fine
     * in a test and wrong in an inventory.
     */
    static String pretty(String mob) {
        if (mob == null || mob.isBlank()) {
            return "Card";
        }
        StringBuilder out = new StringBuilder();
        for (String word : mob.toLowerCase(java.util.Locale.ROOT).split("[_\\s]")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? "Card" : out.toString();
    }

}
