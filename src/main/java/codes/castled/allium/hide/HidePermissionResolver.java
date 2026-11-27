package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Inspects public permission metadata; UNKNOWN delegates to the command's native enforcement. */
public final class HidePermissionResolver {
    public enum Source { BUKKIT, PAPER_BASIC, BRIGADIER, NIGHTCORE, LUCKPERMS, REGISTERED, EFFECTIVE, OVERRIDE, UNKNOWN }
    public record Decision(boolean allowed,Source source,String permission,String label) {}
    private final PluginStart plugin;
    private Set<String> registeredCache=Set.of();
    private long registeredAt;
    public HidePermissionResolver(PluginStart plugin){this.plugin=plugin;}
    /**
     * What a command class exposes, found once per class: tab completion asks for every suggestion,
     * and repeated reflection (and the exceptions of absent methods) made each keystroke expensive.
     */
    private record Shape(boolean paperBasic,boolean framework,boolean brigadier){}
    private static final ClassValue<Shape> SHAPES=new ClassValue<>(){
        @Override protected Shape computeValue(Class<?> type){
            boolean basic=false,brig=false;
            try{
                Class<?> api=Class.forName("io.papermc.paper.command.brigadier.BasicCommand",false,type.getClassLoader());
                for(Class<?> t=type;t!=null;t=t.getSuperclass())for(Field f:t.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&api.isAssignableFrom(f.getType()))basic=true;
            }catch(ClassNotFoundException|LinkageError|RuntimeException absent){}
            for(Class<?> t=type;t!=null;t=t.getSuperclass())for(Field f:t.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&hasSuper(f.getType(),"com.mojang.brigadier.tree.CommandNode"))brig=true;
            boolean framework;try{type.getMethod("getRoot");framework=true;}catch(NoSuchMethodException|LinkageError|RuntimeException absent){framework=false;}
            return new Shape(basic,framework,brig);
        }
    };
    /**
     * The part of {@code path} that {@link #resolve} can depend on, so one completion batch resolves
     * each distinct key once. A plain Bukkit permission depends on nothing past the root; owner
     * candidates use the first argument; Paper, framework and Brigadier trees walk every argument.
     */
    public String decisionKey(Command command,String path){
        String rawPath=HidePolicy.normalizePath(path),rawRoot=HidePolicy.rootOfPath(rawPath);
        String normalized=(command!=null&&(rawRoot.equalsIgnoreCase(command.getName())
                ||(rawRoot.startsWith("/")&&command.getName()!=null&&command.getName().startsWith("/"))))
                ?rawPath
                :HidePolicy.normalize(path);
        String root=HidePolicy.rootOfPath(normalized);
        if(command==null)return root;Shape shape=SHAPES.get(command.getClass());
        if(shape.paperBasic())return root;
        if(shape.framework())return normalized;
        String permission=command.getPermission();if(permission!=null&&!permission.isBlank())return root;
        if(shape.brigadier())return normalized;
        String[] tokens=normalized.split(" ",3);return tokens.length>1?root+" "+tokens[1]:root;
    }
    public Decision resolve(Player player,String line,Map<String,Command> commands,Map<String,String> overrides){
        String rawPath=HidePolicy.normalizePath(line),rawLabel=HidePolicy.rootOfPath(rawPath);
        String path,label;
        if(commands.containsKey(rawLabel)){
            path=rawPath;
            label=rawLabel;
        }else{
            path=HidePolicy.normalize(line);
            label=HidePolicy.rootOfPath(path);
        }
        Command command=commands.get(label);
        if(command==null)return new Decision(false,Source.UNKNOWN,null,label);
        String[] tokens=path.split(" ",256);List<String> args=Arrays.asList(tokens).subList(1,tokens.length);
        Decision d=paperBasic(player,command,label);if(d!=null)return d;
        d=framework(player,command,label,args);if(d!=null)return d;
        String permission=command.getPermission();
        if(permission!=null&&!permission.isBlank())return new Decision(command.testPermissionSilent(player),Source.BUKKIT,permission,label);
        d=brigadier(player,command,label,args);if(d!=null)return d;
        String owner=command instanceof PluginIdentifiableCommand pc?pc.getPlugin().getName().toLowerCase(Locale.ROOT):null;
        long now=System.nanoTime();
        if(registeredAt==0||now-registeredAt>1_000_000_000L){registeredCache=plugin.getServer().getPluginManager().getPermissions().stream().map(Permission::getName).map(v->v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());registeredAt=now;}
        var registered=registeredCache;
        Set<String> effective=player.getEffectivePermissions().stream().map(v->v.getPermission().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        var candidates=candidates(owner,command,label,args,registered);
        Decision luckPerms=luckPerms(player,label,candidates,registered);if(luckPerms!=null)return luckPerms;
        for(String candidate:candidates){
            // A default-false broad wildcard does not identify a command's required node.
            if(candidate.endsWith(".*")&&!player.hasPermission(candidate))continue;
            if(registered.contains(candidate)||effective.contains(candidate))return new Decision(player.hasPermission(candidate),registered.contains(candidate)?Source.REGISTERED:Source.EFFECTIVE,candidate,label);
        }
        String mapped=overrides.get(label);if(mapped==null)mapped=overrides.get(command.getName().toLowerCase(Locale.ROOT));
        if(mapped!=null)return new Decision(player.hasPermission(mapped),Source.OVERRIDE,mapped,label);
        return new Decision(true,Source.UNKNOWN,null,label);
    }
    private Decision paperBasic(Player p,Command command,String label){
        if(!SHAPES.get(command.getClass()).paperBasic())return null;
        try{
            Class<?> api=Class.forName("io.papermc.paper.command.brigadier.BasicCommand",false,command.getClass().getClassLoader());
            for(Class<?> type=command.getClass();type!=null;type=type.getSuperclass())for(Field f:type.getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers())||!api.isAssignableFrom(f.getType()))continue;
                f.setAccessible(true);Object basic=f.get(command);if(basic==null)continue;
                return new Decision(Boolean.TRUE.equals(api.getMethod("canUse",CommandSender.class).invoke(basic,p)),Source.PAPER_BASIC,(String)api.getMethod("permission").invoke(basic),label);
            }
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){}return null;
    }
    private Decision framework(Player p,Command command,String label,List<String> args){
        if(!SHAPES.get(command.getClass()).framework())return null;
        try{
            // NightCore exposes a public root/node API. Do not run executors or parse arbitrary arguments.

            Object node=call(command,"getRoot");String permission=null;boolean inspectable=false;
            for(int i=-1;node!=null&&i<args.size();i++){
                Object raw=call(node,"getPermission");if(raw instanceof String v&&!v.isBlank()){permission=v;inspectable=true;if(!p.hasPermission(v))return new Decision(false,Source.NIGHTCORE,v,label);}
                if(!Boolean.TRUE.equals(invoke(node,"hasPermission",new Class<?>[]{CommandSender.class},p))||!Boolean.TRUE.equals(invoke(node,"canUse",new Class<?>[]{CommandSender.class},p)))return new Decision(false,Source.NIGHTCORE,permission,label);
                String next=i+1<args.size()?args.get(i+1):null;
                if(next==null){
                    if(args.isEmpty()&&optional(node,"getExecutor")!=null){
                        Object open=invoke(node,"getChild",new Class<?>[]{String.class},"open");
                        if(open!=null){Object v=call(open,"getPermission");if(v instanceof String key&&!key.isBlank())return new Decision(p.hasPermission(key)&&Boolean.TRUE.equals(invoke(open,"canUse",new Class<?>[]{CommandSender.class},p)),Source.NIGHTCORE,key,label);}
                    }break;
                }
                Object child=invoke(node,"getChild",new Class<?>[]{String.class},next);
                if(child==null){Object rawChildren=optional(node,"getChildren");if(rawChildren instanceof Collection<?> children&&children.size()==1)child=children.iterator().next();}
                node=child;
            }
            return inspectable?new Decision(true,Source.NIGHTCORE,permission,label):null;
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return null;}
    }
    @SuppressWarnings("unchecked") private Decision brigadier(Player p,Command command,String label,List<String> args){
        if(!SHAPES.get(command.getClass()).brigadier())return null;
        try{
            Object node=null,source=null;
            for(Class<?> type=command.getClass();type!=null;type=type.getSuperclass()){
                for(Field f:type.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&hasSuper(f.getType(),"com.mojang.brigadier.tree.CommandNode")){f.setAccessible(true);node=f.get(command);break;}
                for(Method m:type.getDeclaredMethods())if(m.getName().equals("getListener")&&Modifier.isStatic(m.getModifiers())&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{CommandSender.class})){m.setAccessible(true);source=m.invoke(null,p);}
            }
            if(node==null||source==null)return null;
            var visited=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            for(int i=-1;node!=null&&i<args.size();i++){
                while(node!=null){if(!visited.add(node))return new Decision(false,Source.BRIGADIER,null,label);if(!((Predicate<Object>)call(node,"getRequirement")).test(source))return new Decision(false,Source.BRIGADIER,null,label);Object redirect=call(node,"getRedirect");if(redirect==null)break;node=redirect;}
                if(i+1>=args.size())break;node=invoke(node,"getChild",new Class<?>[]{String.class},args.get(i+1));
            }
            return new Decision(true,Source.BRIGADIER,null,label);
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return null;}
    }
    private Decision luckPerms(Player player,String label,List<String> candidates,Set<String> registered){
        try{
            var peer=plugin.getServer().getPluginManager().getPlugin("LuckPerms");if(peer==null||!peer.isEnabled())return null;ClassLoader loader=peer.getClass().getClassLoader();Class<?> provider=Class.forName("net.luckperms.api.LuckPermsProvider",false,loader);
            Object lp=provider.getMethod("get").invoke(null);
            Class<?> api=Class.forName("net.luckperms.api.LuckPerms",false,loader),adapter=Class.forName("net.luckperms.api.platform.PlayerAdapter",false,loader),data=Class.forName("net.luckperms.api.cacheddata.CachedPermissionData",false,loader),result=Class.forName("net.luckperms.api.cacheddata.Result",false,loader);
            Object a=api.getMethod("getPlayerAdapter",Class.class).invoke(lp,Player.class),cache=adapter.getMethod("getPermissionData",Object.class).invoke(a,player);
            Object raw=data.getMethod("getPermissionMap").invoke(cache);Map<?,?> map=raw instanceof Map<?,?> m?m:Map.of();
            for(String candidate:candidates){if(candidate.endsWith(".*")||!registered.contains(candidate)&&!map.containsKey(candidate))continue;
                Object query=data.getMethod("queryPermission",String.class).invoke(cache,candidate);String state=Objects.toString(result.getMethod("result").invoke(query));
                if(!state.equals("UNDEFINED"))return new Decision(state.equals("TRUE"),Source.LUCKPERMS,candidate,label);
            }
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){}return null;
    }
    private static boolean hasSuper(Class<?> type,String name){for(Class<?> c=type;c!=null;c=c.getSuperclass())if(c.getName().equals(name))return true;return false;}
    static List<String> candidates(String owner,Command command,String label,List<String> args,Set<String> registered){
        if(owner==null)return List.of();
        String root=stripLeadingSlash(HidePolicy.plainOfPath(label));
        var identities=new LinkedHashSet<String>();
        for(String alias:command.getAliases())identities.add(stripLeadingSlash(alias.toLowerCase(Locale.ROOT)));
        identities.add(root);
        identities.add(stripLeadingSlash(command.getName().toLowerCase(Locale.ROOT)));
        identities.remove("");
        var modules=new TreeSet<String>();String prefix=owner+".";
        for(String key:registered){
            if(!key.startsWith(prefix))continue;
            // "<owner>.command.<x>" names no module; only "<owner>.<module>.command.<x>" does.
            int at=key.indexOf(".command.",prefix.length()-1);if(at<prefix.length())continue;
            String module=key.substring(prefix.length(),at);if(identities.stream().anyMatch(v->module.equals(v)||module.endsWith("."+v)))modules.add(module);
        }
        var actions=new LinkedHashSet<String>();if(!args.isEmpty())actions.add(args.get(0));else {if(modules.stream().anyMatch(m->registered.contains(prefix+m+".command.open")))actions.add("open");actions.addAll(identities);}
        var out=new LinkedHashSet<String>();for(String action:actions){for(String module:modules)out.add(prefix+module+".command."+action);out.add(prefix+"command."+action);for(String module:modules)out.add(prefix+module+"."+action);out.add(prefix+action);}
        for(String id:identities)out.add(prefix+id);
        for(String module:modules)out.add(prefix+module+".command.*");out.add(prefix+"command.*");for(String module:modules)out.add(prefix+module+".*");out.add(prefix+"*");return List.copyOf(out);
    }
    private static String stripLeadingSlash(String value){return value==null?"":(value.startsWith("/")?value.substring(1):value);}
    private static Object optional(Object target,String method)throws ReflectiveOperationException{try{return call(target,method);}catch(NoSuchMethodException missing){return null;}}
    private static Object call(Object target,String method)throws ReflectiveOperationException{return invoke(target,method,new Class<?>[0]);}
    private static Object invoke(Object target,String method,Class<?>[] parameters,Object...args)throws ReflectiveOperationException{Method m=target.getClass().getMethod(method,parameters);m.setAccessible(true);return m.invoke(target,args);}
}
