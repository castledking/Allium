package codes.castled.allium.packetevents;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.listeners.security.CommandManager;
import codes.castled.allium.listeners.security.CommandPath;
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
 *    tab-completion visibility rules (hide.yml groups) to command-name
 *    suggestions. Argument suggestions (player names and the like) are
 *    checked as complete paths, including the subcommand being completed.
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
    private record RequestKey(java.util.UUID playerId, int transactionId) {}

    private final Map<RequestKey, TrackedRequest> pendingRequests = new ConcurrentHashMap<>();
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
        if (!enabled && (plugin.getCommandManager() == null || !plugin.getCommandManager().isEnabled())) {
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
        pendingRequests.put(new RequestKey(event.getUser().getUUID(), transactionId), new TrackedRequest(text == null ? "" : text,
            System.currentTimeMillis()));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!enabled && (plugin.getCommandManager() == null || !plugin.getCommandManager().isEnabled())) {
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
            transactionId == null ? null : pendingRequests.remove(
                    new RequestKey(event.getUser().getUUID(), transactionId));

        // A response nobody asked for is dropped: it cannot be legitimate
        // completion and may be a probe relayed through a modified client.
        if (enabled && !bypass && cancelUnknownResponses && request == null) {
            debug(player, "cancelled untracked TAB_COMPLETE response (transactionId="
                + transactionId + ", matches=" + matches.size() + ")");
            event.setCancelled(true);
            return;
        }

        // Bare "/" (or any single-character input): the signature of the
        // LiquidBounce plugin-enumeration probe. Cancel so the client's
        // detection times out rather than receiving a command listing.
        if (enabled && !bypass && cancelBareSlashRequest && request != null
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
        java.util.function.Predicate<String> visible = commandManager == null
                ? path -> true : commandManager.tabCompletionFilter(player);

        // Evaluate each suggestion as a full path. A root rule such as "msg"
        // includes player-name arguments, while "party invite" excludes sibling
        // subcommands without requiring player names in the whitelist.
        boolean completingArguments = commandBeingCompleted(typedText) != null;
        int replacementStart = wrapper.getCommandRange()
                .map(WrapperPlayServerTabComplete.CommandRange::getBegin)
                .orElseGet(() -> CommandPath.completionStart(typedText));

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

            // Keep the existing namespace protection, including unknown namespaces.
            if (enabled && lower.indexOf(':') >= 0 && !allowedNamespaced.contains(lower)) {
                removedNamespaced++;
                continue;
            }

            if (enabled && !completingArguments && lower.indexOf(':') < 0 && matchesPrefixBlocklist(lower)) {
                continue;
            }

            String candidate = CommandPath.suggestion(typedText, text, replacementStart);
            if (!visible.test(candidate)) {
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

    /**
     * The command whose arguments {@code typedText} is completing, lowercased, or null
     * while the command name itself is still being typed.
     */
    static String commandBeingCompleted(String typedText) {
        if (typedText == null || !typedText.startsWith("/")) {
            return null;
        }
        int space = typedText.indexOf(' ');
        return space < 0 ? null : typedText.substring(1, space).toLowerCase(Locale.ROOT);
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
