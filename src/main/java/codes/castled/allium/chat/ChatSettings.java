package codes.castled.allium.chat;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

public record ChatSettings(boolean enabled,boolean formatting,boolean channelsEnabled,String format,Map<String,String> groups,Map<String,Channel> channels,String defaultChannel,String staffChannel,int clearEntries,int packetBudget,int maxBody,int nicknameLength,boolean animate,int updatesPerTick){
    public record Channel(String id,String display,Set<String> aliases,String format,double radius,String discordId,String discordIn,String discordOut,boolean webhook){public boolean local(){return radius>0;}}
    public static ChatSettings read(ConfigurationSection config){
        var channels=new LinkedHashMap<String,Channel>();var root=config.getConfigurationSection("chat.channels");
        String def=root==null?"default":root.getString("default-write-channel","default"),staff=root==null?"staff-chat":root.getString("staff-channel","staff-chat");
        ChatRepository.channel(def);ChatRepository.channel(staff);if(def.equals(staff)||def.equals("local")||staff.equals("local"))throw new IllegalArgumentException("default, staff and local channels must be distinct");
        String format=config.getString("chat-format.default","<prefix><display_name>&f: <message>");
        var entries=root==null?null:root.getConfigurationSection("list");
        if(entries!=null)for(String id:entries.getKeys(false)){
            ChatRepository.channel(id);var c=entries.getConfigurationSection(id);if(c==null)throw new IllegalArgumentException("channel must be a section");
            Set<String> aliases=new LinkedHashSet<>();for(String alias:c.getStringList("aliases"))aliases.add(ChatRepository.channel(alias));
            channels.put(id,new Channel(id,c.getString("display-name",id),Set.copyOf(aliases),c.getString("format",format),0,c.getString("discord-channel-id",""),c.getString("discord-in-format","&8[Discord] &f{discord_username}: {message}"),c.getString("discord-out-format","{player}: {message}"),c.getBoolean("webhook-delivery",false)));
        }
        channels.putIfAbsent(def,new Channel(def,"Global",Set.of("g","global","shout"),format,0,"","{discord_username}: {message}","{player}: {message}",false));
        channels.putIfAbsent(staff,new Channel(staff,"Staff",Set.of("a","ac","amsg","staffchat"),"&8[Staff] <display_name>&f: <message>",0,"","{discord_username}: {message}","{player}: {message}",false));
        var local=root==null?null:root.getConfigurationSection("local-channel");double radius=local==null?100:local.getDouble("radius",100);
        if(!Double.isFinite(radius)||radius<=0||radius>10000)throw new IllegalArgumentException("invalid local radius");
        channels.put("local",new Channel("local","Local",Set.of("l","local"),local==null?"&8[Local] <display_name>&f: <message>":local.getString("format","&8[Local] <display_name>&f: <message>"),radius,"","","",false));
        if(channels.size()>64)throw new IllegalArgumentException("too many channels");
        var labels=new HashMap<String,String>();var discord=new HashSet<String>();
        for(var c:channels.values()){
            for(String alias:union(c.aliases(),c.id())){String prior=labels.putIfAbsent(alias,c.id());if(prior!=null&&!prior.equals(c.id()))throw new IllegalArgumentException("ambiguous channel alias: "+alias);}
            if(!c.discordId().isEmpty()&&(!c.discordId().matches("[0-9]{5,24}")||!discord.add(c.discordId())))throw new IllegalArgumentException("invalid/duplicate Discord destination");
        }
        var groups=new LinkedHashMap<String,String>();var g=config.getConfigurationSection("chat-format.groups");if(g!=null)for(String key:g.getKeys(false))groups.put(key,g.getString(key,format));
        int clear=config.getInt("chat.deletion_resend.clear_lines",500);clear=clear<=0?0:Math.max(100,Math.min(4096,clear));
        return new ChatSettings(config.getBoolean("modules.chat",true),config.getBoolean("enable-chat-formatting",true),config.getBoolean("chat.channels.enabled",true),format,Map.copyOf(groups),Map.copyOf(channels),def,staff,clear,bounded(config,"chat.replay-packets-per-tick",64,1,256),bounded(config,"chat.max-message-length",512,1,4096),bounded(config,"nickname.max-length",32,1,64),config.getBoolean("nickname.animate-tab",false),bounded(config,"nickname.updates-per-tick",16,1,64));
    }
    /** Fixed command labels of the default and staff channels, whatever their configured aliases. */
    public static final Set<String> GLOBAL_LABELS=Set.of("g","global","shout"),STAFF_LABELS=Set.of("a","ac","amsg","staffchat");
    private static Set<String> union(Set<String> set,String value){var out=new HashSet<>(set);out.add(value);return out;}
    private static int bounded(ConfigurationSection c,String key,int fallback,int min,int max){int value=c.getInt(key,fallback);if(value<min||value>max)throw new IllegalArgumentException("invalid "+key);return value;}
    public Channel resolve(String label){String normalized=label.toLowerCase(Locale.ROOT);if(GLOBAL_LABELS.contains(normalized))return channels.get(defaultChannel);if(STAFF_LABELS.contains(normalized))return channels.get(staffChannel);if(Set.of("l","local").contains(normalized))return channels.get("local");return channels.values().stream().filter(c->c.id().equals(normalized)||c.aliases().contains(normalized)).findFirst().orElse(null);}
}
