package codes.castled.allium.packetevents;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.listeners.security.CommandManager;
import codes.castled.allium.managers.core.Text;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareCommands;
import com.github.retrooper.packetevents.protocol.chat.Node;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * PacketEvents listener that removes unsafe commands from the DECLARE_COMMANDS packet.
 * This prevents hacked clients/mods from enumerating sensitive commands like /version,
 * /plugins, /pl, etc. by removing them from the command list sent to the client on join.
 */
public class DeclareCommandsListener extends PacketListenerAbstract {

    private final PluginStart plugin;
    private final Set<String> unsafeCommands;
    private boolean enabled;

    public DeclareCommandsListener(PluginStart plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("hide.settings.remove-unsafe-commands", true);
        
        List<String> configuredList = plugin.getConfig().getStringList("hide.settings.unsafe-commands-list");
        this.unsafeCommands = ConcurrentHashMap.newKeySet();
        for (String cmd : configuredList) {
            if (cmd != null && !cmd.isBlank()) {
                unsafeCommands.add(cmd.toLowerCase(Locale.ROOT));
            }
        }
        
        if (enabled && !unsafeCommands.isEmpty()) {
            Text.sendDebugLog(INFO, "DeclareCommandsListener: Removing " + unsafeCommands.size() + " unsafe commands from DECLARE_COMMANDS packet");
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.DECLARE_COMMANDS) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        // Bypass for players with allium.hide.bypass permission
        if (player.hasPermission("allium.hide.bypass")) {
            if (plugin.getConfig().getBoolean("debug-mode", false)) {
                Text.sendDebugLog(INFO, "DeclareCommandsListener: Bypassing unsafe command removal for " + player.getName() + " (allium.hide.bypass)");
            }
            return;
        }

        try {
            WrapperPlayServerDeclareCommands declareCommandsPacket = new WrapperPlayServerDeclareCommands(event);
            List<Node> nodes = declareCommandsPacket.getNodes();
            int rootIndex = declareCommandsPacket.getRootIndex();

            if (nodes == null || nodes.isEmpty() || rootIndex < 0 || rootIndex >= nodes.size()) {
                return;
            }

            Node rootNode = nodes.get(rootIndex);
            if (rootNode == null) {
                return;
            }

            Set<Integer> nodesToRemove = new HashSet<>();
            if (enabled && !unsafeCommands.isEmpty()) {
                collectNodesToRemove(rootNode, nodes, nodesToRemove, "", new HashSet<>());
            }
            CommandManager commandManager = plugin.getCommandManager();
            if (nodesToRemove.isEmpty() && (commandManager == null || !commandManager.isEnabled())) {
                return;
            }
            CommandTreeFilter.Result filtered = CommandTreeFilter.filter(nodes, rootIndex, nodesToRemove,
                    commandManager == null ? path -> true : commandManager.tabCompletionFilter(player),
                    commandManager == null ? Set.of() : commandManager.subcommandCompletionRoots(player));
            declareCommandsPacket.setNodes(filtered.nodes());
            declareCommandsPacket.setRootIndex(filtered.rootIndex());
            event.markForReEncode(true);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "DeclareCommandsListener: Failed to process DECLARE_COMMANDS packet: " + e.getMessage());
            if (plugin.getConfig().getBoolean("debug-mode", false)) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Recursively collects node indices that match unsafe commands.
     */
    private void collectNodesToRemove(Node node, List<Node> allNodes, Set<Integer> nodesToRemove,
                                      String currentPath, Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }

        // Check if this node's name matches an unsafe command
        String nodeName = node.getName().orElse("").toLowerCase(Locale.ROOT);
        String fullPath = currentPath.isEmpty() ? nodeName : currentPath + " " + nodeName;

        if (unsafeCommands.contains(nodeName) || unsafeCommands.contains(fullPath)) {
            int index = allNodes.indexOf(node);
            if (index >= 0 && !nodesToRemove.contains(index)) {
                nodesToRemove.add(index);
            }
        }

        // Recurse into children
        for (Integer childIndex : node.getChildren()) {
            if (childIndex >= 0 && childIndex < allNodes.size()) {
                Node childNode = allNodes.get(childIndex);
                collectNodesToRemove(childNode, allNodes, nodesToRemove, fullPath, visited);
            }
        }
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("hide.settings.remove-unsafe-commands", true);
        this.unsafeCommands.clear();
        List<String> configuredList = plugin.getConfig().getStringList("hide.settings.unsafe-commands-list");
        for (String cmd : configuredList) {
            if (cmd != null && !cmd.isBlank()) {
                unsafeCommands.add(cmd.toLowerCase(Locale.ROOT));
            }
        }
    }

    public void shutdown() {
        try {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "DeclareCommandsListener: Failed to unregister: " + e.getMessage());
        }
    }
}