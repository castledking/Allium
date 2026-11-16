package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import org.bukkit.entity.Player;
import java.util.*;
import java.lang.reflect.*;

/** Optional providers are consulted on the server thread, never packet threads. */
final class HideGroups {
    private final PluginStart plugin;
    HideGroups(PluginStart plugin){this.plugin=plugin;}
    Set<String> memberships(Player player,Collection<String> configured){
        var names=new LinkedHashSet<String>();
        for(String group:configured)if(player.hasPermission("group."+group))names.add(group);
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("Vault"))names.addAll(Vault.groups(plugin,player,configured));}catch(LinkageError|RuntimeException unavailable){}
        return Set.copyOf(names);
    }
    Map<String,Set<String>> parents(Collection<String> configured){
        var output=new LinkedHashMap<String,Set<String>>();
        var peer=plugin.getServer().getPluginManager().getPlugin("LuckPerms");if(peer==null||!peer.isEnabled())return Map.of();
        try{
            ClassLoader loader=peer.getClass().getClassLoader();Class<?> api=Class.forName("net.luckperms.api.LuckPerms",false,loader);
            Object lp=Class.forName("net.luckperms.api.LuckPermsProvider",false,loader).getMethod("get").invoke(null);
            Object manager=api.getMethod("getGroupManager").invoke(lp);Class<?> groupManager=Class.forName("net.luckperms.api.model.group.GroupManager",false,loader);
            Class<?> nodeType=Class.forName("net.luckperms.api.node.NodeType",false,loader),holder=Class.forName("net.luckperms.api.model.PermissionHolder",false,loader),inheritance=Class.forName("net.luckperms.api.node.types.InheritanceNode",false,loader);
            Object type=nodeType.getField("INHERITANCE").get(null);
            for(String child:configured){
                Set<String> seen=new LinkedHashSet<>();var queue=new ArrayDeque<String>();queue.add(child);
                while(!queue.isEmpty()&&seen.size()<256){String next=queue.remove();Object group=groupManager.getMethod("getGroup",String.class).invoke(manager,next);if(group==null)continue;
                    Object raw=holder.getMethod("getNodes",nodeType).invoke(group,type);if(!(raw instanceof Collection<?> nodes))continue;
                    for(Object node:nodes){
                        if(!Boolean.TRUE.equals(Class.forName("net.luckperms.api.node.Node",false,loader).getMethod("getValue").invoke(node)))continue;
                        String parent=((String)inheritance.getMethod("getGroupName").invoke(node)).toLowerCase(Locale.ROOT);
                        if(!parent.equals(child)&&seen.add(parent))queue.add(parent);
                    }
                }
                int weight=weight(child);seen.removeIf(parent->!configured.contains(parent)||weight(parent)>weight);output.put(child,Set.copyOf(seen));
            }
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return Map.of();}
        return Map.copyOf(output);
    }
    private int weight(String name){try{return Vault.weight(plugin,name);}catch(LinkageError|RuntimeException unavailable){return 0;}}
    private static final class Vault {
        static Set<String> groups(PluginStart plugin,Player p,Collection<String> configured){
            var permission=plugin.getServer().getServicesManager().load(net.milkbowl.vault.permission.Permission.class);if(permission==null||!permission.hasGroupSupport())return Set.of();
            var out=new LinkedHashSet<String>();for(String group:configured)if(permission.playerInGroup((String)null,p.getName(),group))out.add(group);return out;
        }
        static int weight(PluginStart plugin,String name){var chat=plugin.getServer().getServicesManager().load(net.milkbowl.vault.chat.Chat.class);if(chat==null)return 0;try{return Integer.parseInt(chat.getGroupInfoString((String)null,name,"weight","0"));}catch(NumberFormatException invalid){return 0;}}
    }
}
