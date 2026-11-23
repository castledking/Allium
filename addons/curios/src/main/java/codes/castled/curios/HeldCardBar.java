package codes.castled.curios;

import codes.castled.allium.scheduler.TaskHandle;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Legacy trading cards' action bar: the held card's title above the hotbar, in its mob's colour,
 * shown once when a card comes into the main hand. The client keeps it up for two seconds and
 * fades it; it is never resent while the same card stays held.
 *
 * <p>A slot change, hand swap or join shows it on the next tick, once the held item has settled,
 * and clears it when no card is held. A check every {@link #PERIOD_MILLIS} (legacy's 40 ticks)
 * catches a card that reaches the hand without a slot change, showing it only when the held card
 * differs from the one last shown.
 */
final class HeldCardBar implements Listener {
    static final long PERIOD_MILLIS = 2_000;

    private final CuriosPlugin plugin;
    /** Players showing a title, so a clear only goes to those who had one. */
    private final Map<UUID, String> shown = new java.util.concurrent.ConcurrentHashMap<>();
    private TaskHandle task;

    HeldCardBar(CuriosPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        task = plugin.core().scheduler().repeatOnServer(() -> {
            if (plugin.core().isFolia()) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    plugin.core().runForEntity(player, () -> update(player, false),
                            () -> shown.remove(player.getUniqueId()), 0L);
                }
            } else {
                for (Player player : plugin.getServer().getOnlinePlayers()) update(player, false);
            }
            return true;
        }, PERIOD_MILLIS, PERIOD_MILLIS);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (UUID id : shown.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && (!plugin.core().isFolia() || plugin.core().ownsEntity(player))) send(player, "");
        }
        shown.clear();
    }

    /** The bar text for the card in the player's main hand, or null when none is held. */
    private String text(Player player) {
        if (!plugin.cardRules().heldActionBar || !player.hasPermission("curios.use")) return null;
        Catalog.Entry entry = plugin.entry(player.getInventory().getItemInMainHand());
        return entry == null || entry.legacyCard().isEmpty() ? null : title(entry, plugin.cardRules());
    }

    /** A card's title as its lore shows it: small caps, in the mob's colour. */
    static String title(Catalog.Entry entry, CardRules rules) {
        String colour = rules.mobColour(entry.legacyCard());
        String code = colour.startsWith("#") ? "&" + colour : CardRules.legacyColourCode(colour);
        return code + CardRules.smallCaps(CardRules.titleCase(entry.legacyCard()) + " Trading Card");
    }

    /**
     * Shows the held card's title, or clears the bar when none is held.
     *
     * @param force show it even if it is the card last shown (a slot change or swap)
     */
    private void update(Player player, boolean force) {
        if (!player.isOnline()) return;
        String text = text(player);
        if (text == null) {
            if (shown.remove(player.getUniqueId()) != null) send(player, "");
        } else if (force || !text.equals(shown.get(player.getUniqueId()))) {
            send(player, text);
            shown.put(player.getUniqueId(), text);
        }
    }

    private void send(Player player, String text) {
        String legacy = text.isEmpty() ? "" : plugin.core().text().legacy(text);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, legacy.isEmpty()
                ? new TextComponent[]{new TextComponent("")} : TextComponent.fromLegacyText(legacy));
    }

    private void nextTick(Player player) {
        plugin.core().runForEntity(player, () -> update(player, true),
                () -> shown.remove(player.getUniqueId()), 0L);
    }

    @EventHandler
    public void held(PlayerItemHeldEvent event) {
        nextTick(event.getPlayer());
    }

    @EventHandler
    public void swap(PlayerSwapHandItemsEvent event) {
        nextTick(event.getPlayer());
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        nextTick(event.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }
}
