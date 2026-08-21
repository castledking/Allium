package codes.castled.allium.packetevents;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.listeners.security.CommandManager;
import codes.castled.allium.managers.core.Text;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * PacketEvents listener that filters the tab-completion suggestion protocol
 * (Play.Client.TAB_COMPLETE request / Play.Server.TAB_COMPLETE response),
 * mirroring the reference behavior of Pl-Hide-Pro:
 *
 *  - Tracks each incoming suggestion request by its transaction id.
 *  - Cancels responses with no matching tracked request (unsolicited/unknown).
 *  - Cancels the response to a bare "/" request (input shorter than two
 *    characters), which is exactly what hack clients such as LiquidBounce use
 *    as their plugin-enumeration probe (.serverinfo Plugins). The client then
 *    times out instead of receiving a command list.
 *  - Strips every namespaced ("plugin:command") suggestion unless explicitly
 *    allowlisted, so unknown namespaces (jbt:*, someplugin:*) can never leak.
 *  - Applies the configured blocklist prefixes and Allium's own group-based
 *    tab-completion visibility rules (hide.yml groups) to plain suggestions.
 *  - Cancels the response entirely if filtering removes every suggestion,
 *    instead of sending an empty list.
 *
 * Legitimate completion ("/he", "/msg <partial>", player-name completion in
 * chat) is preserved: requests that do not start with "/" pass through
 * untouched apart from the bare-slash rule.
 */
public class CommandSuggestionsListener extends PacketListenerAbstract {

    private static final int MAX_TRACKED_REQUESTS = 1024;
    private static final long REQUEST_TTL_MS = 60_000L;

    private final PluginStart plugin;
    private final Set<String> unsafePrefixes = ConcurrentHashMap.newKeySet();
    private final Set<String> allowedNamespaced = ConcurrentHashMap.newKeySet();
    private final Map<Integer, TrackedRequest> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile boolean cancelBareSlashRequest;
    private volatile boolean cancelUnknownResponses;

    private record TrackedRequest(String text, long timestamp) {}

    public CommandSuggestionsListener(PluginStart plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("hide.settings.filter-command-suggestions", true);
        this.cancelBareSlashRequest =
            plugin.getConfig().getBoolean("hide.settings.cancel-bare-slash-request", true);
        this.cancelUnknownResponses =
            plugin.getConfig().getBoolean("hide.settings.cancel-unknown-suggestion-responses", true);

        unsafePrefixes.clear();
        for (String prefix : plugin.getConfig()
                .getStringList("hide.settings.command-suggestion-filters")) {
            if (prefix != null && !prefix.isBlank()) {
                unsafePrefixes.add(prefix.toLowerCase(Locale.ROOT));
            }
        }

        allowedNamespaced.clear();
        for (String entry : plugin.getConfig()
                .getStringList("hide.settings.allow-namespaced-suggestions")) {
            if (entry != null && !entry.isBlank()) {
                allowedNamespaced.add(entry.toLowerCase(Locale.ROOT));
            }
        }

        if (enabled) {
            Text.sendDebugLog(INFO,
                "CommandSuggestionsListener active (prefix filters: " + unsafePrefixes.size()
                + ", namespaced allowlist: " + allowedNamespaced.size()
                + ", cancel-bare-slash: " + cancelBareSlashRequest + ")");
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getPacketType() != PacketType.Play.Client.TAB_COMPLETE) {
            return;
        }

        WrapperPlayClientTabComplete wrapper = new WrapperPlayClientTabComplete(event);
        Integer transactionId = wrapper.getTransactionId().orElse(null);
        if (transactionId == null) {
            return;
        }

        String text = wrapper.getText();
        pruneTrackedRequests();
        pendingRequests.put(transactionId, new TrackedRequest(text == null ? "" : text,
            System.currentTimeMillis()));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.TAB_COMPLETE) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        WrapperPlayServerTabComplete wrapper = new WrapperPlayServerTabComplete(event);
        List<WrapperPlayServerTabComplete.CommandMatch> matches = wrapper.getCommandMatches();
        if (matches == null || matches.isEmpty()) {
            return;
        }

        boolean bypass = player.hasPermission("allium.hide.bypass");
        Integer transactionId = wrapper.getTransactionId().orElse(null);
        TrackedRequest request =
            transactionId == null ? null : pendingRequests.remove(transactionId);

        // A response nobody asked for is dropped: it cannot be legitimate
        // completion and may be a probe relayed through a modified client.
        if (!bypass && cancelUnknownResponses && request == null) {
            debug(player, "cancelled untracked TAB_COMPLETE response (transactionId="
                + transactionId + ", matches=" + matches.size() + ")");
            event.setCancelled(true);
            return;
        }

        // Bare "/" (or any single-character input): the signature of the
        // LiquidBounce plugin-enumeration probe. Cancel so the client's
        // detection times out rather than receiving a command listing.
        if (!bypass && cancelBareSlashRequest && request != null
                && request.text().length() < 2) {
            debug(player, "cancelled TAB_COMPLETE response to bare '/' probe ("
                + matches.size() + " matches withheld)");
            event.setCancelled(true);
            return;
        }

        if (bypass) {
            return;
        }

        // Chat/player-name completion contexts never leak plugin namespaces;
        // only process command-line completions.
        String typedText = request == null ? "" : request.text();
        if (!typedText.isEmpty() && !typedText.startsWith("/")) {
            return;
        }

        CommandManager commandManager = plugin.getCommandManager();
        List<WrapperPlayServerTabComplete.CommandMatch> filtered = new ArrayList<>(matches.size());
        int removedNamespaced = 0;

        for (WrapperPlayServerTabComplete.CommandMatch match : matches) {
            if (match == null) {
                continue;
            }
            String text = match.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);

            // Namespaced suggestions are the enumeration vector: strip every
            // one unless it is explicitly allowlisted.
            if (lower.indexOf(':') >= 0) {
                if (!allowedNamespaced.contains(lower)) {
                    removedNamespaced++;
                    continue;
                }
                filtered.add(match);
                continue;
            }

            // Extra configured prefix blocklist.
            if (matchesPrefixBlocklist(lower)) {
                continue;
            }

            // Enforce Allium's own group visibility rules so hidden commands
            // are equally absent from suggestions regardless of namespace.
            if (commandManager != null && !commandManager.shouldAllowTabComplete(player, lower)) {
                continue;
            }

            filtered.add(match);
        }

        if (filtered.size() == matches.size()) {
            return;
        }

        if (filtered.isEmpty()) {
            debug(player, "cancelled empty TAB_COMPLETE response (withheld "
                + removedNamespaced + " namespaced suggestions)");
            event.setCancelled(true);
            return;
        }

        wrapper.setCommandMatches(filtered);
        event.markForReEncode(true);
        debug(player, "filtered TAB_COMPLETE response: kept " + filtered.size()
            + "/" + matches.size() + " suggestions (removed " + removedNamespaced
            + " namespaced)");
    }

    private boolean matchesPrefixBlocklist(String lowerSuggestion) {
        for (String prefix : unsafePrefixes) {
            if (lowerSuggestion.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void pruneTrackedRequests() {
        if (pendingRequests.size() < MAX_TRACKED_REQUESTS) {
            return;
        }
        long now = System.currentTimeMillis();
        pendingRequests.values().removeIf(request -> now - request.timestamp() > REQUEST_TTL_MS);
        if (pendingRequests.size() >= MAX_TRACKED_REQUESTS) {
            pendingRequests.clear();
        }
    }

    private void debug(Player player, String message) {
        if (plugin.getConfig().getBoolean("debug-mode", false)) {
            Text.sendDebugLog(INFO, "CommandSuggestionsListener [" + player.getName() + "]: "
                + message);
        }
    }

    public void reload() {
        loadConfig();
        pendingRequests.clear();
    }

    public void shutdown() {
        try {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .unregisterListener(this);
        } catch (Exception e) {
            Text.sendDebugLog(WARN,
                "CommandSuggestionsListener: Failed to unregister: " + e.getMessage());
        }
    }
}
