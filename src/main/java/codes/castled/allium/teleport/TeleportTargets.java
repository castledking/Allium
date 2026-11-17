package codes.castled.allium.teleport;
import codes.castled.allium.PluginStart;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import java.util.*;
/** Loaded targets only; visibility is applied before sorting/limiting. No offline/network lookup. */
public final class TeleportTargets {
 private final PluginStart plugin;
 public TeleportTargets(PluginStart plugin){this.plugin=plugin;}
 public static boolean selectors(CommandSender sender){return sender.hasPermission("allium.selectors")||sender.hasPermission("allium.admin");}
 public List<Entity> resolve(CommandSender sender,String token){
  if(!token.startsWith("@")){Player player=plugin.getServer().getPlayerExact(token);if(player!=null)return PlayerNameVisibility.canSee(sender,player)?List.of(player):List.of();Player found=null;for(Player candidate:plugin.getServer().getOnlinePlayers())if(candidate.getName().toLowerCase(Locale.ROOT).startsWith(token.toLowerCase(Locale.ROOT))&&PlayerNameVisibility.canSee(sender,candidate)){if(found!=null)return List.of();found=candidate;}return found==null?List.of():List.of(found);}
  if(!selectors(sender))throw new SecurityException("allium.selectors");
  if(token.length()>2048||!token.matches("@[pares](\\[.*])?"))throw new IllegalArgumentException("Invalid selector");
  char head=token.charAt(1);if(head=='a'&&!sender.hasPermission("allium.command.selector.all")&&!sender.hasPermission("allium.admin"))throw new SecurityException("allium.command.selector.all");if(head=='e'&&!sender.hasPermission("allium.command.selector.entities")&&!sender.hasPermission("allium.admin"))throw new SecurityException("allium.command.selector.entities");
  var args=new HashMap<String,String>();if(token.length()>2){String body=token.substring(3,token.length()-1);if(!body.isBlank())for(String piece:codes.castled.allium.item.spec.ItemSpecLexer.splitTopLevel(body,',')){String[] pair=piece.split("=",2);if(pair.length!=2||!Set.of("type","name","tag","gamemode","gm","distance","limit","sort","world","x","y","z","dx","dy","dz").contains(pair[0])||args.put(pair[0],pair[1].replaceAll("^\"|\"$",""))!=null)throw new IllegalArgumentException("Invalid selector option");}}
  World world=args.containsKey("world")?plugin.getServer().getWorld(args.get("world")):sender instanceof Entity e?e.getWorld():plugin.getServer().getWorlds().get(0);if(world==null)throw new IllegalArgumentException("Unknown world");Location origin=sender instanceof Entity e&&e.getWorld()==world?e.getLocation().clone():world.getSpawnLocation();if(args.containsKey("x"))origin.setX(number(args.get("x")));if(args.containsKey("y"))origin.setY(number(args.get("y")));if(args.containsKey("z"))origin.setZ(number(args.get("z")));
  List<Entity> list=new ArrayList<>(head=='s'?sender instanceof Entity e?List.of(e):List.of():head=='e'?world.getEntities():world.getPlayers());
  list.removeIf(e->e.getWorld()!=world||!e.isValid()||e instanceof Player p&&!PlayerNameVisibility.canSee(sender,p)||(head=='p'||head=='r')&&e==sender||!match(e,args,origin));
  String sort=args.getOrDefault("sort",head=='p'?"nearest":head=='r'?"random":"arbitrary");switch(sort){case "nearest"->list.sort(Comparator.comparingDouble(e->e.getLocation().distanceSquared(origin)));case "furthest"->list.sort(Comparator.comparingDouble((Entity e)->e.getLocation().distanceSquared(origin)).reversed());case "random"->Collections.shuffle(list);case "arbitrary"->{}default->throw new IllegalArgumentException("Invalid sort");}
  int limit=args.containsKey("limit")?Integer.parseInt(args.get("limit")):head=='p'||head=='r'?1:128;if(limit<1||limit>128)throw new IllegalArgumentException("Selector limit must be 1..128");return List.copyOf(list.subList(0,Math.min(limit,list.size())));
 }
 private static boolean match(Entity e,Map<String,String> a,Location o){
  for(String key:List.of("type","name","tag","gamemode","gm")){String raw=a.get(key);if(raw==null)continue;boolean negate=raw.startsWith("!");String value=negate?raw.substring(1):raw;boolean matches=switch(key){case "type"->e.getType().name().equalsIgnoreCase(value.replace("minecraft:",""));case "name"->e.getName().equals(value);case "tag"->e.getScoreboardTags().contains(value);default->e instanceof Player p&&p.getGameMode().name().equalsIgnoreCase(value);};if(matches==negate)return false;}
  if(a.containsKey("distance")){String[] range=a.get("distance").split("\\.\\.",-1);double distance=e.getLocation().distance(o);if(range.length==1){if(distance!=number(range[0]))return false;}else if(range.length==2){if(!range[0].isEmpty()&&distance<number(range[0])||!range[1].isEmpty()&&distance>number(range[1]))return false;}else throw new IllegalArgumentException("Invalid distance range");}
  Location p=e.getLocation();for(String axis:List.of("x","y","z")){String delta=a.get("d"+axis);if(delta==null)continue;double start=axis.equals("x")?o.getX():axis.equals("y")?o.getY():o.getZ(),v=axis.equals("x")?p.getX():axis.equals("y")?p.getY():p.getZ(),end=start+number(delta);if(v<Math.min(start,end)||v>Math.max(start,end)+1)return false;}return true;
 }
 private static double number(String value){double n=Double.parseDouble(value);if(!Double.isFinite(n))throw new IllegalArgumentException("Nonfinite coordinate");return n;}
}
