package codes.castled.allium.command;

import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandIdentityCopyTest {
    public static final class Node {
        private final String name;
        private final Object command;
        private final List<Node> children;
        Node(String name, Object command, List<Node> children) {
            this.name=name; this.command=command; this.children=children;
        }
        public String getName() { return name; }
        public Node getRedirect() { return null; }
        public Object getCommand() { return command; }
        public List<Node> getChildren() { return children; }
    }
    public static final class Wrapper extends BukkitCommand {
        public final Node vanillaCommand;
        Wrapper(String label, Node node) { super(label); vanillaCommand=node; }
        @Override public boolean execute(CommandSender sender,String label,String[] args) { return true; }
    }
    @Test void paperCopiesWithSharedArgumentNodesRetainCanonicalIdentity() {
        Object action=new Object();
        Node message=new Node("message", action, List.of());
        Wrapper bare=new Wrapper("me",new Node("me",null,List.of(message)));
        Wrapper namespaced=new Wrapper("minecraft:me",new Node("minecraft:me",null,List.of(message)));
        assertSame(CommandMapBridge.canonicalCommandIdentity(bare),
                CommandMapBridge.canonicalCommandIdentity(namespaced));
        Wrapper unrelated=new Wrapper("me",new Node("me",null,
                List.of(new Node("message",new Object(),List.of()))));
        assertNotSame(CommandMapBridge.canonicalCommandIdentity(bare),
                CommandMapBridge.canonicalCommandIdentity(unrelated));
    }
    @Test void copiedExecutableRootsUseTheSameExecutorWithoutComparingLabels() {
        Object action=new Object();
        Wrapper bare=new Wrapper("say",new Node("say",action,List.of()));
        Wrapper copy=new Wrapper("minecraft:say",new Node("minecraft:say",action,List.of()));
        assertSame(CommandMapBridge.canonicalCommandIdentity(bare),
                CommandMapBridge.canonicalCommandIdentity(copy));
        assertNotSame(CommandMapBridge.canonicalCommandIdentity(bare),
                CommandMapBridge.canonicalCommandIdentity(new Wrapper("say",new Node("say",new Object(),List.of()))));
    }
}
