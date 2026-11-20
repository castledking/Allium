package codes.castled.allium.teleport;
import codes.castled.allium.PluginStart;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import java.util.*;
/** Loaded targets only; visibility is applied before sorting/limiting. No offline/network lookup. */
public final class TeleportTargets {
 public record Resolution(List<Entity> targets,int matchedCount,boolean selector,boolean entitySelector){}
 private final PluginStart plugin;
 public TeleportTargets(PluginStart plugin){this.plugin=plugin;}
 public static boolean selectors(CommandSender sender){return sender.hasPermission("allium.selectors")||sender.hasPermission("allium.admin");}
 public List<Entity> resolve(CommandSender sender,String token){
  return resolveInternal(sender,token,128,null,true).targets();
 }
 /** Resolves targets with a configurable maximum limit, reporting the full matched count when exceeded. */
 public Resolution resolveDetailed(CommandSender sender,String token,int maxEntities,Entity exclude){
  return resolveInternal(sender,token,Math.max(1,maxEntities),exclude,false);
 }
 private Resolution resolveInternal(CommandSender sender,String token,int maxEntities,Entity exclude,boolean truncateOnOverflow){
  if(!token.startsWith("@")){Player player=plugin.getServer().getPlayerExact(token);if(player!=null){if(player==exclude||!PlayerNameVisibility.canSee(sender,player))return new Resolution(List.of(),0,false,false);return new Resolution(List.of(player),1,false,false);}Player found=null;for(Player candidate:plugin.getServer().getOnlinePlayers())if(candidate.getName().toLowerCase(Locale.ROOT).startsWith(token.toLowerCase(Locale.ROOT))&&PlayerNameVisibility.canSee(sender,candidate)){if(found!=null)return new Resolution(List.of(),0,false,false);found=candidate;}if(found==null||found==exclude)return new Resolution(List.of(),0,false,false);return new Resolution(List.of(found),1,false,false);}
  if(token.equalsIgnoreCase("@s")){if(!(sender instanceof Entity e)||e==exclude||!e.isValid())return new Resolution(List.of(),0,true,false);return new Resolution(List.of(e),1,true,false);}
  if(!selectors(sender))throw new SecurityException("allium.selectors");
  if(token.length()>2048||!token.matches("@[pares](\\[.*])?"))throw new IllegalArgumentException("Invalid selector");
  char head=token.charAt(1);if(head=='a'&&!sender.hasPermission("allium.command.selector.all")&&!sender.hasPermission("allium.admin"))throw new SecurityException("allium.command.selector.all");if(head=='e'&&!sender.hasPermission("allium.command.selector.entities")&&!sender.hasPermission("allium.admin"))throw new SecurityException("allium.command.selector.entities");
  var args=new HashMap<String,String>();if(token.length()>2){String body=token.substring(3,token.length()-1);if(!body.isBlank())for(String piece:codes.castled.allium.item.spec.ItemSpecLexer.splitTopLevel(body,',')){String[] pair=piece.split("=",2);if(pair.length!=2||!Set.of("type","name","tag","gamemode","gm","distance","limit","sort","world","x","y","z","dx","dy","dz").contains(pair[0])||args.put(pair[0],pair[1].replaceAll("^\"|\"$",""))!=null)throw new IllegalArgumentException("Invalid selector option");}}
  World world=args.containsKey("world")?plugin.getServer().getWorld(args.get("world")):sender instanceof Entity e?e.getWorld():plugin.getServer().getWorlds().get(0);if(world==null)throw new IllegalArgumentException("Unknown world");Location origin=sender instanceof Entity e&&e.getWorld()==world?e.getLocation().clone():world.getSpawnLocation();if(args.containsKey("x"))origin.setX(number(args.get("x")));if(args.containsKey("y"))origin.setY(number(args.get("y")));if(args.containsKey("z"))origin.setZ(number(args.get("z")));
  List<Entity> list=new ArrayList<>(head=='s'?sender instanceof Entity e?List.of(e):List.of():head=='e'?world.getEntities():world.getPlayers());
  list.removeIf(e->e.getWorld()!=world||!e.isValid()||e==exclude||e instanceof Player p&&!PlayerNameVisibility.canSee(sender,p)||(head=='p'||head=='r')&&e==sender||!match(e,args,origin));
  String sort=args.getOrDefault("sort",head=='p'?"nearest":head=='r'?"random":"arbitrary");switch(sort){case "nearest"->list.sort(Comparator.comparingDouble(e->e.getLocation().distanceSquared(origin)));case "furthest"->list.sort(Comparator.comparingDouble((Entity e)->e.getLocation().distanceSquared(origin)).reversed());case "random"->Collections.shuffle(list);case "arbitrary"->{}default->throw new IllegalArgumentException("Invalid sort");}
  boolean entitySel=head=='e';
  if(args.containsKey("limit")){
   int limit=Integer.parseInt(args.get("limit"));
   if(limit<1||limit>maxEntities)throw new IllegalArgumentException("Selector limit must be 1.."+maxEntities);
   List<Entity> sliced=List.copyOf(list.subList(0,Math.min(limit,list.size())));
   return new Resolution(sliced,sliced.size(),true,entitySel);
  }
  if(head=='p'||head=='r'){
   List<Entity> sliced=list.isEmpty()?List.of():List.of(list.get(0));
   return new Resolution(sliced,sliced.size(),true,entitySel);
  }
  int total=list.size();
  if(!truncateOnOverflow&&total>maxEntities){
   return new Resolution(List.of(),total,true,entitySel);
  }
  List<Entity> sliced=List.copyOf(list.subList(0,Math.min(maxEntities,total)));
  return new Resolution(sliced,total,true,entitySel);
 }
 private static boolean match(Entity e,Map<String,String> a,Location o){
  for(String key:List.of("type","name","tag","gamemode","gm")){String raw=a.get(key);if(raw==null)continue;boolean negate=raw.startsWith("!");String value=negate?raw.substring(1):raw;boolean matches=switch(key){case "type"->e.getType().name().equalsIgnoreCase(value.replace("minecraft:",""));case "name"->e.getName().equals(value);case "tag"->e.getScoreboardTags().contains(value);default->e instanceof Player p&&p.getGameMode().name().equalsIgnoreCase(value);};if(matches==negate)return false;}
  if(a.containsKey("distance")){String[] range=a.get("distance").split("\\.\\.",-1);double distance=e.getLocation().distance(o);if(range.length==1){if(distance!=number(range[0]))return false;}else if(range.length==2){if(!range[0].isEmpty()&&distance<number(range[0])||!range[1].isEmpty()&&distance>number(range[1]))return false;}else throw new IllegalArgumentException("Invalid distance range");}
  Location p=e.getLocation();for(String axis:List.of("x","y","z")){String delta=a.get("d"+axis);if(delta==null)continue;double start=axis.equals("x")?o.getX():axis.equals("y")?o.getY():o.getZ(),v=axis.equals("x")?p.getX():axis.equals("y")?p.getY():p.getZ(),end=start+number(delta);if(v<Math.min(start,end)||v>Math.max(start,end)+1)return false;}return true;
 }
 private static double number(String value){double n=Double.parseDouble(value);if(!Double.isFinite(n))throw new IllegalArgumentException("Nonfinite coordinate");return n;}
}
