package codes.castled.allium.packetevents;

import codes.castled.allium.listeners.security.CommandPath;
import com.github.retrooper.packetevents.protocol.chat.Node;
import com.github.retrooper.packetevents.protocol.chat.Parsers;
import com.github.retrooper.packetevents.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Filters the client tree without changing the server's command dispatcher. */
final class CommandTreeFilter {
    record Result(List<Node> nodes, int rootIndex) {}

    private CommandTreeFilter() {}

    static Result filter(List<Node> source, int rootIndex, Set<Integer> removed,
                         Predicate<String> visible, Set<String> serverCompletionRoots) {
        List<Node> nodes = new ArrayList<>(source);
        Node root = copy(nodes.get(rootIndex));
        nodes.set(rootIndex, root);
        List<Integer> children = new ArrayList<>();

        for (int index : root.getChildren()) {
            if (removed.contains(index)) {
                continue;
            }
            Node child = nodes.get(index);
            String name = CommandPath.normalize(child.getName().orElse(""));
            if (!visible.test(name)) {
                continue;
            }
            children.add(index);
            String plainName = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
            if (serverCompletionRoots.contains(name) || serverCompletionRoots.contains(plainName)) {
                // Literal and built-in argument suggestions can be generated entirely
                // by the client. Route affected roots through ask_server so every
                // candidate is checked against the actual command path instead.
                child = copy(child);
                child.setFlags((byte) (child.getFlags() & ~Node.FLAG_REDIRECT));
                child.setRedirectNodeIndex(0);
                child.setChildren(List.of(nodes.size()));
                nodes.set(index, child);
                nodes.add(new Node((byte) (Node.TYPE_ARGUMENT | Node.FLAG_EXECUTABLE
                                | Node.FLAG_CUSTOM_SUGGESTIONS), List.of(), 0, "args",
                        Parsers.BRIGADIER_STRING, List.<Object>of(2),
                        new ResourceLocation("minecraft:ask_server")));
            }
        }
        root.setChildren(children);

        // Drop unreachable nodes as well as edges, and remap both children and
        // redirects. Removing entries in place would corrupt protocol indices.
        List<Node> compacted = new ArrayList<>();
        int newRoot = copyReachable(rootIndex, nodes, removed, new HashMap<>(), compacted);
        return new Result(compacted, newRoot);
    }

    private static int copyReachable(int index, List<Node> source, Set<Integer> removed,
                                     Map<Integer, Integer> indices, List<Node> output) {
        Integer existing = indices.get(index);
        if (existing != null) {
            return existing;
        }
        Node node = copy(source.get(index));
        int newIndex = output.size();
        indices.put(index, newIndex);
        output.add(node);
        List<Integer> children = new ArrayList<>();
        for (int child : node.getChildren()) {
            if (!removed.contains(child)) {
                children.add(copyReachable(child, source, removed, indices, output));
            }
        }
        node.setChildren(children);
        if ((node.getFlags() & Node.FLAG_REDIRECT) != 0) {
            int target = node.getRedirectNodeIndex();
            if (removed.contains(target)) {
                node.setFlags((byte) (node.getFlags() & ~Node.FLAG_REDIRECT));
                node.setRedirectNodeIndex(0);
            } else {
                node.setRedirectNodeIndex(copyReachable(target, source, removed, indices, output));
            }
        }
        return newIndex;
    }

    private static Node copy(Node node) {
        return new Node(node.getFlags(), node.getChildren(), node.getRedirectNodeIndex(),
                node.getName().orElse(null), node.getParser().orElse(null),
                node.getProperties().orElse(null), node.getSuggestionsType().orElse(null));
    }
}
