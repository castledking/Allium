package codes.castled.allium.hide;

import com.github.retrooper.packetevents.protocol.chat.Node;
import com.github.retrooper.packetevents.protocol.chat.Parsers;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import java.util.*;
import java.util.function.Predicate;

/** Copies and compacts the graph: detached nodes must not remain enumerable in the packet. */
public final class CommandTreeFilter {
    public record Tree(List<Node> nodes,int root) {}
    private CommandTreeFilter(){}
    public static Tree filter(List<Node> source,int root,Predicate<String> visible,Set<String> serverSuggestedRoots){
        if(source==null||source.isEmpty()||source.size()>100_000||root<0||root>=source.size())throw new IllegalArgumentException("invalid command graph");
        var nodes=new ArrayList<Node>();for(Node node:source){if(node==null)throw new IllegalArgumentException("null command node");nodes.add(copy(node));}
        Node entry=nodes.get(root);var kept=new ArrayList<Integer>();
        for(int child:entry.getChildren()){
            if(child<0||child>=nodes.size())throw new IllegalArgumentException("invalid command child");Node n=nodes.get(child);String label=n.getName().orElse("");
            if((n.getFlags()&Node.TYPE_MASK)!=Node.TYPE_LITERAL||label.isEmpty()||!visible.test(label))continue;
            kept.add(child);
            if(serverSuggestedRoots.contains(HidePolicy.rootOfPath(label))||serverSuggestedRoots.contains(HidePolicy.plainOfPath(HidePolicy.rootOfPath(label)))||hasRedirect(nodes,child)){
                int args=nodes.size();nodes.add(new Node((byte)(Node.TYPE_ARGUMENT|Node.FLAG_EXECUTABLE|Node.FLAG_CUSTOM_SUGGESTIONS),new ArrayList<>(),0,"args",Parsers.BRIGADIER_STRING,List.<Object>of(2),new ResourceLocation("minecraft:ask_server")));
                n.setChildren(new ArrayList<>(List.of(args)));n.setFlags((byte)(n.getFlags()&~Node.FLAG_REDIRECT));n.setRedirectNodeIndex(0);
            }
        }
        entry.setChildren(kept);
        var reachable=new LinkedHashSet<Integer>();var queue=new ArrayDeque<Integer>();queue.add(root);
        while(!queue.isEmpty()){
            int index=queue.remove();if(index<0||index>=nodes.size())throw new IllegalArgumentException("invalid command reference");if(!reachable.add(index))continue;
            Node n=nodes.get(index);queue.addAll(n.getChildren());if((n.getFlags()&Node.FLAG_REDIRECT)!=0)queue.add(n.getRedirectNodeIndex());
        }
        Map<Integer,Integer> indices=new HashMap<>();for(int old:reachable)indices.put(old,indices.size());var out=new ArrayList<Node>();
        for(int old:reachable){Node n=nodes.get(old);n.setChildren(n.getChildren().stream().map(indices::get).toList());if((n.getFlags()&Node.FLAG_REDIRECT)!=0)n.setRedirectNodeIndex(indices.get(n.getRedirectNodeIndex()));out.add(n);}
        return new Tree(List.copyOf(out),indices.get(root));
    }
    private static boolean hasRedirect(List<Node> nodes,int start){
        var seen=new HashSet<Integer>();var pending=new ArrayDeque<Integer>();pending.add(start);
        while(!pending.isEmpty()){int i=pending.remove();if(i<0||i>=nodes.size())throw new IllegalArgumentException("invalid command reference");if(!seen.add(i))continue;Node n=nodes.get(i);if((n.getFlags()&Node.FLAG_REDIRECT)!=0)return true;pending.addAll(n.getChildren());}return false;
    }
    private static Node copy(Node node){return new Node(node.getFlags(),new ArrayList<>(node.getChildren()),node.getRedirectNodeIndex(),node.getName().orElse(null),node.getParser().orElse(null),node.getProperties().map(ArrayList::new).orElse(null),node.getSuggestionsType().orElse(null));}
}
