package codes.castled.allium.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.chat.Node;
import com.github.retrooper.packetevents.protocol.chat.Parsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandTreeFilterTest {
    private static PacketEventsAPI<?> previousApi;

    @BeforeAll
    static void initializeParserResources() {
        previousApi = PacketEvents.getAPI();
        // The real parser registry loads bundled resources through the API's
        // settings. Supply those defaults without starting a Minecraft server.
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return ServerVersion::getLatest; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public NettyManager getNettyManager() { return null; }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    @AfterAll
    static void restoreApi() {
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void subcommandRulesReplaceCachedLiteralsWithServerSuggestions() {
        List<Node> original = List.of(root(1, 4), literal("party", 2, 3),
                literal("invite"), literal("kick"), literal("spawn"));
        var result = CommandTreeFilter.filter(original, 0, Set.of(), path -> true, Set.of("party"));

        assertEquals(List.of("party", "args", "spawn"), result.nodes().stream()
                .flatMap(node -> node.getName().stream()).toList());
        Node party = result.nodes().get(result.nodes().get(result.rootIndex()).getChildren().getFirst());
        Node args = result.nodes().get(party.getChildren().getFirst());
        assertEquals(Parsers.BRIGADIER_STRING, args.getParser().orElseThrow());
        assertEquals(List.of(2), args.getProperties().orElseThrow());
        assertEquals("minecraft:ask_server", args.getSuggestionsType().orElseThrow().toString());
        assertTrue((args.getFlags() & Node.FLAG_CUSTOM_SUGGESTIONS) != 0);
        assertEquals(List.of(2, 3), original.get(1).getChildren(), "Do not mutate another player's tree");
    }

    @Test
    void removesHiddenRootsAndRemapsChildrenRedirectsAndNonzeroRootIndex() {
        Node alias = literal("alias");
        alias.setFlags((byte) (alias.getFlags() | Node.FLAG_REDIRECT));
        alias.setRedirectNodeIndex(3);
        List<Node> original = List.of(literal("secret"), root(0, 2, 3), alias,
                literal("party", 4), literal("invite"));
        var result = CommandTreeFilter.filter(original, 1, Set.of(),
                path -> !path.equals("secret"), Set.of());

        assertEquals(0, result.rootIndex());
        assertEquals(List.of(1, 2), result.nodes().getFirst().getChildren());
        assertEquals(2, result.nodes().get(1).getRedirectNodeIndex());
        assertEquals(List.of(3), result.nodes().get(2).getChildren());
        assertEquals(4, result.nodes().size());
    }

    @Test
    void subcommandRuleOnAliasDoesNotChangeSharedTarget() {
        Node alias = literal("alias");
        alias.setFlags((byte) (alias.getFlags() | Node.FLAG_REDIRECT));
        alias.setRedirectNodeIndex(2);
        var result = CommandTreeFilter.filter(List.of(root(1, 2), alias,
                literal("party", 3), literal("invite")), 0, Set.of(), path -> true, Set.of("alias"));

        Node filteredAlias = result.nodes().get(1);
        assertEquals(0, filteredAlias.getFlags() & Node.FLAG_REDIRECT);
        Node party = result.nodes().stream().filter(n -> n.getName().orElse("").equals("party")).findFirst().orElseThrow();
        assertEquals("invite", result.nodes().get(party.getChildren().getFirst()).getName().orElseThrow());
    }

    @Test
    void removesUnsafeDescendantsAndHandlesRedirectCycles() {
        Node loop = literal("loop");
        loop.setFlags((byte) (loop.getFlags() | Node.FLAG_REDIRECT));
        loop.setRedirectNodeIndex(0);
        var result = CommandTreeFilter.filter(List.of(root(1, 2), literal("secret", 3), loop,
                literal("unsafe-child")), 0, Set.of(1), path -> true, Set.of());

        assertEquals(2, result.nodes().size());
        assertEquals(List.of(1), result.nodes().getFirst().getChildren());
        assertEquals(0, result.nodes().get(1).getRedirectNodeIndex());
    }

    private static Node root(Integer... children) {
        return new Node(Node.TYPE_ROOT, List.of(children), 0, null, (Parsers.Parser) null, null, null);
    }

    private static Node literal(String name, Integer... children) {
        return new Node(Node.TYPE_LITERAL, List.of(children), 0, name, (Parsers.Parser) null, null, null);
    }
}
