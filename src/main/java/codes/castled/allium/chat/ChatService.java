package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.module.*;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.text.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.concurrent.*;

/** One server-thread owner for audiences, render/history and replay; one bounded storage lane. */
public final class ChatService implements Listener,AutoCloseable {
    private final PluginStart plugin;
    private final ChatRepository repository;
    private final Executor io;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final ArrayDeque<Player> refresh=new ArrayDeque<>();
    private final Map<UUID,Replay> replays=new LinkedHashMap<>();
    private final ChatHistory history=new ChatHistory(4096,200,15*60_000);
    private final LinkedHashMap<String,Boolean> discordSeen=new LinkedHashMap<>();
    private final Map<String,String> discordLinks=new HashMap<>();
    private final ChatDecorations decorations;
    private volatile ChatSettings settings;
    private volatile boolean closed;
    private boolean paperChat;
    private TaskHandle tick=TaskHandle.NONE;
    private AutoCloseable expansion,packetMirror;
    private ChatRelay relay=ChatRelay.NONE;
    private static final class Session {
        final Player player;
        volatile String nickname,display,gradient;
        String write="";Set<String> reads=Set.of();boolean ready,busy;
        long decorationsAt;ChatDecorations.Value decor=ChatDecorations.Value.EMPTY;
        String lastTab,lastDisplay;
        Session(Player p){player=p;display=p.getName();gradient=p.getName();}
    }
    private static final class Replay {
        final Player player;int clear;final Set<String> sent=new HashSet<>();boolean dirty;
        Replay(Player player){this.player=player;}
    }
    public ChatService(PluginStart plugin,ChatRepository repository,Executor io){this.plugin=plugin;this.repository=repository;this.io=io;settings=ChatSettings.read(plugin.getConfig());decorations=new ChatDecorations(plugin);}
    public void enable(){
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        paperChat=installPaperInput();installInteractiveGuard();
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("packetevents")||plugin.getServer().getPluginManager().isPluginEnabled("PacketEvents"))packetMirror=new ChatPacketMirror(this);}
        catch(LinkageError|RuntimeException error){plugin.logger().warning("Optional chat packet mirror unavailable; replay covers Allium-rendered history only.");}
        for(Player p:plugin.getServer().getOnlinePlayers())join(p);
        tick=plugin.getScheduler().repeatOnServer(()->{if(closed)return false;refresh();replayTick();return true;},50,50);
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))expansion=new AlliumChatExpansion(this,plugin);}
        catch(LinkageError|RuntimeException error){plugin.logger().warning("Chat placeholders unavailable: "+error.getMessage());}
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("DiscordSRV"))relay=new DiscordChatRelay(plugin,this);}
        catch(LinkageError|RuntimeException error){plugin.logger().warning("Discord relay unavailable: "+error.getMessage());}
    }
    ChatService(PluginStart plugin,ChatRepository repository,Executor io,ChatRelay relay){this(plugin,repository,io);this.relay=relay;}
    public boolean ready(UUID id){var session=sessions.get(id);return !closed&&session!=null&&session.ready;}
    public ChatHistory history(){return history;}
    public ChatSettings settings(){return settings;}
    public boolean ownsChat(){return !closed&&settings.enabled();}
    public void reload(){ChatSettings replacement=ChatSettings.read(plugin.getConfig());settings=replacement;for(Session s:sessions.values()){s.decorationsAt=0;if(!replacement.channels().containsKey(s.write))tell(s.player,"Your write channel was removed; choose a channel before speaking.");}}
    @EventHandler public void joined(PlayerJoinEvent e){join(e.getPlayer());}
    private void join(Player p){
        var session=new Session(p);sessions.put(p.getUniqueId(),session);refresh.add(p);
        load(session,0);
    }
    private void load(Session session,int attempt){
        Player p=session.player;
        work(()->repository.load(p.getUniqueId()),stored->{
            if(!same(session))return;session.nickname=stored.nickname();session.write=stored.writeChannel().isEmpty()?settings.defaultChannel():stored.writeChannel();
            var reads=new LinkedHashSet<>(stored.reads());reads.add(settings.defaultChannel());
            if(stored.writeChannel().isEmpty())for(var c:settings.channels().values())if(p.hasPermission("allium.channel.autojoin."+c.id()+".read"))reads.add(c.id());
            session.reads=Set.copyOf(reads);session.ready=true;update(session);
        },error->{if(same(session)){
            if(attempt<5)plugin.getScheduler().runOnServerLater(()->{if(same(session))load(session,attempt+1);},Math.min(10_000,500L<<attempt));
            else {tell(p,"Chat profile could not load; reconnect to retry.");plugin.logger().warning("Chat profile load failed for "+p.getUniqueId());}
        }});
    }
    @EventHandler public void quit(PlayerQuitEvent e){Session s=sessions.remove(e.getPlayer().getUniqueId());refresh.remove(e.getPlayer());replays.remove(e.getPlayer().getUniqueId());history.forget(e.getPlayer().getUniqueId());if(s!=null&&s.lastTab!=null&&e.getPlayer().isOnline())e.getPlayer().setPlayerListName(null);}
    private boolean same(Session session){return !closed&&sessions.get(session.player.getUniqueId())==session&&session.player.isOnline();}
    private void refresh(){
        int n=Math.min(settings.updatesPerTick(),refresh.size());long now=System.currentTimeMillis();
        for(int i=0;i<n;i++){
            Player p=refresh.removeFirst();Session s=sessions.get(p.getUniqueId());if(s==null||!same(s))continue;refresh.addLast(p);
            if(now-s.decorationsAt>=1000){s.decor=decorations.query(p);s.decorationsAt=now;}
            if(s.ready)update(s);
        }
    }
    private void update(Session s){
        if(!settings.enabled()){s.display=s.player.getName();s.gradient=s.display;if(!Objects.equals(s.lastDisplay,s.display)){s.player.setDisplayName(s.display);s.lastDisplay=s.display;}if(s.lastTab!=null){s.player.setPlayerListName(null);s.lastTab=null;}return;}
        FormattedText name=name(s,false),gradient=name(s,true);s.display=name.toLegacy();s.gradient=gradient.toLegacy();
        if(!Objects.equals(s.lastDisplay,s.display)){s.player.setDisplayName(s.display);s.lastDisplay=s.display;}
        if(settings.animate()){
            String desired=s.gradient;if(!Objects.equals(desired,s.lastTab)){s.player.setPlayerListName(desired);s.lastTab=desired;}
        }else if(s.lastTab!=null){s.player.setPlayerListName(null);s.lastTab=null;}
    }
    public String placeholder(Player p,boolean gradient){var s=sessions.get(p.getUniqueId());return s==null?p.getName():gradient?s.gradient:s.display;}
    private FormattedText name(Session s,boolean gradient){
        String raw=s.nickname==null?s.player.getName():s.nickname;
        var allowed=new UntrustedText.Allow(s.player.hasPermission("allium.nick.color"),s.player.hasPermission("allium.nick.hex"),s.player.hasPermission("allium.nick.gradient"));
        String clean=TextFormatter.parse(raw).toPlainText();
        if(gradient&&!s.decor.colors().isEmpty()){
            int first=s.decor.colors().get(0),last=s.decor.colors().get(s.decor.colors().size()-1);
            double phase=s.player.hasPermission("allium.gradientname")?(System.nanoTime()/1_000_000L%6000)/3000.0:0;
            return TextFormatter.parse(clean,(index,length)->{
                double x=(length<=1?0:(double)index/(length-1))+phase;x%=2;if(x>1)x=2-x;
                int r=(int)Math.round(((first>>16)&255)*(1-x)+((last>>16)&255)*x),g=(int)Math.round(((first>>8)&255)*(1-x)+((last>>8)&255)*x),b=(int)Math.round((first&255)*(1-x)+(last&255)*x);return r<<16|g<<8|b;
            });
        }
        FormattedText parsed=UntrustedText.parse(raw,allowed);
        return parsed.toPlainText().equals(clean)?parsed:UntrustedText.parse(clean,UntrustedText.Allow.NONE);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void legacyChat(AsyncPlayerChatEvent event){
        if(paperChat||!ownsChat())return;
        var allowed=event.getRecipients().stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        String body=event.getMessage();event.setCancelled(true);Player source=event.getPlayer();
        plugin.getScheduler().runOnServer(()->send(source,null,body,allowed));
    }
    @SuppressWarnings("unchecked") private boolean installPaperInput(){
        try{
            Class<? extends Event> event=(Class<? extends Event>)Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            var player=event.getMethod("getPlayer");var message=event.getMethod("message");var viewers=event.getMethod("viewers");
            plugin.getServer().getPluginManager().registerEvent(event,this,EventPriority.MONITOR,(listener,raw)->{
                if(!ownsChat()||((Cancellable)raw).isCancelled())return;
                try{
                    Player source=(Player)player.invoke(raw);String body=PaperText.plain(message.invoke(raw));
                    Set<UUID> audience=new HashSet<>();for(Object viewer:(Set<?>)viewers.invoke(raw))if(viewer instanceof Player p)audience.add(p.getUniqueId());
                    ((Cancellable)raw).setCancelled(true);plugin.getScheduler().runOnServer(()->send(source,null,body,Set.copyOf(audience)));
                }catch(ReflectiveOperationException error){((Cancellable)raw).setCancelled(true);plugin.logger().warning("Paper chat input refused: "+error.getMessage());}
            },plugin,true);return true;
        }catch(ClassNotFoundException unavailable){return false;}
        catch(ReflectiveOperationException|LinkageError error){throw new IllegalStateException("Paper chat adapter unavailable",error);}
    }
    private static final class PaperText {
        static String plain(Object component){return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component)component);}
    }
    private boolean allowed(Player p){
        var s=sessions.get(p.getUniqueId());if(s==null||!s.ready){tell(p,"Chat profile is not ready.");return false;}
        if(plugin.getModuleManager().getModule("moderation") instanceof ModerationModule m&&m.isEnabled()){
            if(!m.moderation().isMuteStateReady(p.getUniqueId())||m.moderation().isMuted(p.getUniqueId())){tell(p,"You cannot chat while muted or moderation is loading.");return false;}
            if(m.moderation().isVanished(p)&&!plugin.getConfig().getBoolean("moderation.vanish.allow-public-chat",false)){tell(p,"Public chat is disabled while vanished.");return false;}
        }return true;
    }
    public boolean canRead(Player p,ChatSettings.Channel channel){return channel.local()||p.hasPermission("allium.channel.join."+channel.id()+".read")||p.hasPermission("allium.channel.join."+channel.id()+".write")||channel.id().equals(settings.staffChannel())&&p.hasPermission("allium.staffchat");}
    public boolean canWrite(Player p,ChatSettings.Channel channel){return p.hasPermission("allium.channel.join."+channel.id()+".write")||channel.id().equals(settings.staffChannel())&&p.hasPermission("allium.staffchat");}
    private boolean listening(Session viewer,ChatSettings.Channel channel){return canRead(viewer.player,channel)&&(channel.local()||viewer.reads.contains(channel.id()));}
    public void send(Player p,String explicit,String raw,Set<UUID> originalAudience){
        if(!ownsChat()||!p.isOnline()||!allowed(p))return;
        Session s=sessions.get(p.getUniqueId());
        if(!settings.channelsEnabled()&&explicit==null&&(!s.write.equals(settings.defaultChannel())||raw.startsWith("#"))){tell(p,"Channels are disabled; explicitly select global before speaking.");return;}
        String target=!settings.channelsEnabled()?settings.defaultChannel():explicit==null?s.write:explicit;
        if(settings.channelsEnabled()&&explicit==null&&raw.startsWith("#")&&!raw.matches("^#[0-9a-fA-F]{6}.*")){target=settings.staffChannel();raw=raw.substring(1);}
        var c=settings.resolve(target);if(c==null||!canWrite(p,c)){tell(p,"You cannot write to that channel.");return;}
        if(raw.isBlank()||raw.length()>settings.maxBody()){tell(p,"Invalid chat message length.");return;}
        FormattedText body=UntrustedText.parse(raw,new UntrustedText.Allow(p.hasPermission("allium.chat.color"),p.hasPermission("allium.chat.hex"),p.hasPermission("allium.chat.gradient")));
        String id=history.nextId();var render=new LinkedHashMap<UUID,String>();
        String format=c.id().equals(settings.defaultChannel())?settings.groups().getOrDefault(s.decor.group(),c.format()):c.format();
        if(!settings.formatting())format="{name}: {message}";
        var values=values(s,body);var origin=p.getLocation();
        for(Session viewer:sessions.values()){
            Player recipient=viewer.player;
            if(!viewer.ready||!same(viewer)||!listening(viewer,c)||originalAudience!=null&&!originalAudience.contains(recipient.getUniqueId()))continue;
            if(c.local()&&(!origin.getWorld().equals(recipient.getWorld())||origin.distanceSquared(recipient.getLocation())>c.radius()*c.radius()))continue;
            render.put(recipient.getUniqueId(),ChatOutput.render(format,values,recipient,id,p.getName(),s.decor.group(),plugin.getConfig()));
        }
        var message=history.add(id,p.getUniqueId(),p.getName(),c.id(),System.currentTimeMillis(),render);
        for(var entry:render.entrySet()){Session viewer=sessions.get(entry.getKey());if(viewer!=null&&!replays.containsKey(entry.getKey())&&!message.deleted().get())ChatOutput.send(viewer.player,entry.getValue());}
        if(!c.local()&&!c.discordId().isEmpty())relay.send(message,c,p.getName(),body.toPlainText());
        plugin.getServer().getConsoleSender().sendMessage("["+c.id()+"] "+p.getName()+": "+body.toPlainText()+" ["+id+"]");
    }
    private Map<String,FormattedText> values(Session s,FormattedText body){
        var values=new LinkedHashMap<String,FormattedText>();values.put("prefix",TextFormatter.parse(s.decor.prefix()));values.put("name",name(s,false));values.put("gradient_name",name(s,true));values.put("suffix",TextFormatter.parse(s.decor.suffix()));values.put("player",UntrustedText.parse(s.player.getName(),UntrustedText.Allow.NONE));values.put("message",body);return values;
    }
    public void inbound(String discordId,String destination,String author,String body){
        plugin.getScheduler().runOnServer(()->{
            if(!ownsChat()||discordSeen.putIfAbsent(discordId,Boolean.TRUE)!=null)return;
            while(discordSeen.size()>4096)discordSeen.remove(discordSeen.keySet().iterator().next());
            var c=settings.channels().values().stream().filter(v->!v.local()&&!v.discordId().isEmpty()&&v.discordId().equals(destination)).findFirst().orElse(null);if(c==null)return;
            String incomingBody=body.length()>settings.maxBody()?body.substring(0,settings.maxBody()):body;
            String id=history.nextId();var render=new LinkedHashMap<UUID,String>();Map<String,FormattedText> values=Map.of("discord_username",UntrustedText.parse(author,UntrustedText.Allow.NONE),"message",UntrustedText.parse(incomingBody,UntrustedText.Allow.NONE));
            for(Session s:sessions.values())if(s.ready&&same(s)&&listening(s,c))render.put(s.player.getUniqueId(),ChatOutput.render(c.discordIn(),values,s.player,id,"Discord","default",plugin.getConfig()));
            var message=history.add(id,new UUID(0,0),author,c.id(),System.currentTimeMillis(),render);discordLinks.put(discordId,id);while(discordLinks.size()>4096)discordLinks.remove(discordLinks.keySet().iterator().next());
            plugin.getServer().getConsoleSender().sendMessage("[Discord/"+c.id()+"] "+UntrustedText.clean(author)+": "+UntrustedText.clean(incomingBody)+" ["+id+"]");
            render.forEach((viewer,json)->{var s=sessions.get(viewer);if(s!=null&&!replays.containsKey(viewer)&&!message.deleted().get())ChatOutput.send(s.player,json);});
        });
    }
    public void discordDeleted(String id){plugin.getScheduler().runOnServer(()->{String linked=discordLinks.get(id);if(linked!=null)deleteIds(List.of(linked));});}
    private void deleteIds(List<String> ids){
        Set<UUID> affected=new HashSet<>();
        for(String id:ids)if(history.delete(id)){history.find(id).ifPresent(m->affected.addAll(m.rendered().keySet()));relay.delete(id);}
        for(UUID viewer:affected){Session s=sessions.get(viewer);if(s==null||!same(s))continue;var existing=replays.get(viewer);if(existing!=null)existing.dirty=true;else replays.put(viewer,new Replay(s.player));}
    }
    private void replayTick(){
        int budget=settings.packetBudget();var jobs=new ArrayList<>(replays.entrySet());
        for(var entry:jobs){if(budget<=0)break;Replay job=entry.getValue();Session s=sessions.get(entry.getKey());if(s==null||s.player!=job.player||!same(s)){replays.remove(entry.getKey());continue;}
            while(budget>0&&job.clear<settings.clearEntries()){ChatOutput.send(job.player,ChatOutput.blank(job.clear++));budget--;}
            if(job.clear<settings.clearEntries())continue;
            boolean exhausted=true;
            for(var message:history.visible(entry.getKey(),System.currentTimeMillis())){
                if(job.sent.contains(message.id())||message.deleted().get())continue;
                if(budget<=0){exhausted=false;break;}
                ChatOutput.replay(job.player,message.rendered().get(entry.getKey()));job.sent.add(message.id());budget--;
            }
            if(exhausted){if(job.dirty)replays.put(entry.getKey(),new Replay(job.player));else replays.remove(entry.getKey());}
        }
    }
    public void dispatch(BukkitCommandContext context){
        if(!settings.enabled()){tell(context.sender(),"Chat module is disabled.");return;}
        CommandSender sender=context.sender();String action=context.spec().canonicalName();String[] args=context.args();
        try{switch(action){case "nick"->nick(sender,args);case "channel"->channel(sender,context.label(),args);case "delmsg"->delete(sender,args);default->throw new IllegalArgumentException();}}
        catch(IllegalArgumentException error){tell(sender,error.getMessage()==null?"Invalid chat arguments.":error.getMessage());}
    }
    private void nick(CommandSender sender,String[] args){
        if(args.length<1||args.length>2)throw new IllegalArgumentException("Usage: /nick <name|off> or /nick <player> <name|off>");
        String raw=args[args.length-1];boolean reset=Set.of("off","reset").contains(raw.toLowerCase(Locale.ROOT));
        if(!reset){
            if(!raw.equals(UntrustedText.clean(raw)))throw new IllegalArgumentException("Nickname contains forbidden control text.");
            var allow=new UntrustedText.Allow(sender.hasPermission("allium.nick.color"),sender.hasPermission("allium.nick.hex"),sender.hasPermission("allium.nick.gradient"));
            String plain=UntrustedText.parse(raw,allow).toPlainText();
            if(plain.codePointCount(0,plain.length())>settings.nicknameLength()||!plain.matches("[\\p{L}\\p{N}_ .-]+")||plain.isBlank())throw new IllegalArgumentException("Invalid nickname or formatting permission.");
        }
        if(args.length==1){if(!(sender instanceof Player p))throw new IllegalArgumentException("Console must specify a player.");saveNick(sender,p.getUniqueId(),reset?null:raw);return;}
        if(!sender.hasPermission("allium.nick.others")){tell(sender,"You do not have permission.");return;}
        Player online=plugin.getServer().getPlayerExact(args[0]);if(online!=null&&!PlayerNameVisibility.canSee(sender,online)){tell(sender,"Player not found.");return;}
        plugin.getPlayerNames().resolve(args[0]).whenComplete((found,error)->plugin.getScheduler().runOnServer(()->{if(error!=null||found.isEmpty())tell(sender,"Player not found.");else saveNick(sender,found.get().uuid(),reset?null:raw);}));
    }
    private void saveNick(CommandSender sender,UUID id,String raw){
        String plain=raw==null?"":TextFormatter.parse(raw).toPlainText();
        if(raw!=null&&plain.matches("[A-Za-z0-9_]{1,16}")&&!sender.hasPermission("allium.nick.impersonate")){
            plugin.getPlayerNames().resolve(plain).whenComplete((found,error)->plugin.getScheduler().runOnServer(()->{
                if(closed)return;if(error!=null){tell(sender,"Nickname identity check unavailable.");return;}
                if(found.isPresent()&&!found.get().uuid().equals(id)){tell(sender,"That nickname is unavailable.");return;}
                persistNick(sender,id,raw);
            }));return;
        }
        persistNick(sender,id,raw);
    }
    private void persistNick(CommandSender sender,UUID id,String raw){
        Session session=sessions.get(id);if(session!=null&&session.busy){tell(sender,"Chat profile is busy.");return;}
        if(session!=null)session.busy=true;
        work(()->{repository.nickname(id,raw);return repository.load(id);},stored->{
            Session current=sessions.get(id);if(current!=null){current.nickname=stored.nickname();current.busy=false;if(current.ready)update(current);}if(raw==null)tell(sender,Messages.NICK_RESET);else tell(sender,Messages.NICK_SET,"{nick}",stored.nickname());
        },error->{if(session!=null)session.busy=false;tell(sender,"Nickname could not be saved.");});
    }
    private void channel(CommandSender sender,String label,String[] args){
        if(!(sender instanceof Player p))throw new IllegalArgumentException("Channels require a player.");Session s=sessions.get(p.getUniqueId());if(s==null||!s.ready)throw new IllegalArgumentException("Chat profile is not ready.");
        String alias=label.substring(label.lastIndexOf(':')+1);var shortcut=settings.resolve(alias);
        if(!settings.channelsEnabled()){
            if(Set.of("g","global").contains(alias)){if(args.length==0)choose(s,settings.defaultChannel(),true);else send(p,settings.defaultChannel(),String.join(" ",args),null);return;}
            throw new IllegalArgumentException("Chat channels are disabled; use /g to select global explicitly.");
        }
        if(!alias.equals("channel")&&shortcut!=null){
            if(args.length>0){send(p,shortcut.id(),String.join(" ",args),null);return;}
            choose(s,s.write.equals(shortcut.id())?settings.defaultChannel():shortcut.id(),true);return;
        }
        if(args.length==0){tell(p,"Channels: "+String.join(", ",complete(p,"")));return;}
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Usage: /channel <join|leave> <channel> [read|write]");
        var target=settings.resolve(args[1]);if(target==null)throw new IllegalArgumentException("Unknown channel.");
        if(args[0].equalsIgnoreCase("join")){if(args.length==3&&!Set.of("read","write").contains(args[2]))throw new IllegalArgumentException("Choose read or write.");choose(s,target.id(),args.length<3||!args[2].equals("read"));}
        else if(args[0].equalsIgnoreCase("leave")){
            var reads=new LinkedHashSet<>(s.reads);reads.remove(target.id());String write=s.write.equals(target.id())?settings.defaultChannel():s.write;saveChannels(s,write,reads);
        }else throw new IllegalArgumentException("Choose join or leave.");
    }
    private void choose(Session s,String id,boolean write){var c=settings.channels().get(id);if(c==null||!(write?canWrite(s.player,c):canRead(s.player,c)))throw new IllegalArgumentException("You cannot join that channel.");var reads=new LinkedHashSet<>(s.reads);reads.add(id);saveChannels(s,write?id:s.write,reads);}
    private void saveChannels(Session s,String write,Set<String> reads){
        if(s.busy)throw new IllegalArgumentException("Chat profile is busy.");s.busy=true;
        work(()->{repository.channels(s.player.getUniqueId(),write,reads);return Boolean.TRUE;},saved->{if(same(s)){s.write=write;s.reads=Set.copyOf(reads);s.busy=false;tell(s.player,"Channel preference saved: "+write);}},error->{s.busy=false;tell(s.player,"Channel preference could not be saved.");});
    }
    public List<String> complete(Player p,String prefix){return settings.channels().values().stream().filter(c->canRead(p,c)&&c.id().startsWith(prefix.toLowerCase(Locale.ROOT))).map(ChatSettings.Channel::id).sorted().limit(64).toList();}
    private void delete(CommandSender sender,String[] args){
        if(!(sender.hasPermission("allium.delmsg")||sender.hasPermission("allium.deletemsg")||sender.hasPermission("allium.staff"))){tell(sender,Messages.NO_PERMISSION);return;}
        if(args.length==1){if(history.find(args[0]).isEmpty()){tell(sender,Messages.CHAT_DELETE_NOT_FOUND);return;}auditDeletion(sender,List.of(args[0]));deleteIds(List.of(args[0]));tell(sender,Messages.CHAT_DELETE_SUCCESS);return;}
        if(args.length==2){final int amount;try{amount=Integer.parseInt(args[1]);}catch(NumberFormatException invalid){tell(sender,Messages.CHAT_DELETE_AMOUNT);return;}
            if(amount<1||amount>200){tell(sender,Messages.CHAT_DELETE_AMOUNT);return;}
            plugin.getPlayerNames().resolve(args[0]).whenComplete((found,error)->plugin.getScheduler().runOnServer(()->{if(error!=null||found.isEmpty()){tell(sender,Messages.CHAT_PLAYER_NOT_FOUND);return;}var ids=history.recentBySender(found.get().uuid(),amount).stream().map(ChatHistory.Message::id).toList();auditDeletion(sender,ids);deleteIds(ids);tell(sender,Messages.CHAT_DELETE_MULTIPLE);}));return;}
        tell(sender,Messages.CHAT_DELETE_USAGE);
    }
    public boolean deletedIdentity(String id){return history.find(id).map(m->m.deleted().get()).orElse(true);}
    @SuppressWarnings("unchecked") private void installInteractiveGuard(){
        var peer=plugin.getServer().getPluginManager().getPlugin("InteractiveChat");if(peer==null||!peer.isEnabled())return;
        try{
            Class<? extends Event> event=(Class<? extends Event>)Class.forName("com.loohp.interactivechat.api.events.PreChatPacketSendEvent",false,peer.getClass().getClassLoader());
            var component=event.getMethod("getComponent");var insertion=component.getReturnType().getMethod("insertion");var children=component.getReturnType().getMethod("children");var original=event.getMethod("setSendOriginalIfCancelled",boolean.class);
            plugin.getServer().getPluginManager().registerEvent(event,this,EventPriority.HIGHEST,(listener,raw)->{
                try{var queue=new ArrayDeque<Object>();Object root=component.invoke(raw);if(root!=null)queue.add(root);int visited=0;
                    while(!queue.isEmpty()&&visited++<16384){Object node=queue.remove();String marker=(String)insertion.invoke(node);
                        if(marker!=null&&marker.startsWith(ChatIdentity.ID)&&deletedIdentity(marker.substring(ChatIdentity.ID.length()))){original.invoke(raw,false);((Cancellable)raw).setCancelled(true);return;}
                        queue.addAll((List<?>)children.invoke(node));
                    }
                }catch(ReflectiveOperationException error){throw new org.bukkit.event.EventException(error);}
            },plugin,false);
        }catch(ReflectiveOperationException error){plugin.logger().warning("InteractiveChat delayed-deletion guard unavailable.");}
    }
    private void auditDeletion(CommandSender sender,List<String> ids){plugin.logger().info("Chat deletion actor="+(sender instanceof Player p?p.getUniqueId():"CONSOLE")+" messages="+String.join(",",ids));}
    private void tell(CommandSender sender,Messages message,String... args){if(!closed&&(!(sender instanceof Player p)||p.isOnline()))plugin.getMessageManager().send(sender,message,args);}
    private void tell(CommandSender sender,String text){if(!closed&&(!(sender instanceof Player p)||p.isOnline()))sender.sendMessage("[Allium] "+text);}
    @FunctionalInterface private interface Query<T>{T run()throws Exception;}
    private <T> void work(Query<T> query,java.util.function.Consumer<T> done,java.util.function.Consumer<Throwable> failed){
        try{io.execute(()->{try{T result=query.run();plugin.getScheduler().runOnServer(()->{if(!closed)done.accept(result);});}catch(Exception error){plugin.getScheduler().runOnServer(()->{if(!closed)failed.accept(error);});}});}
        catch(RejectedExecutionException error){failed.accept(error);}
    }
    @Override public void close(){closed=true;tick.cancel();HandlerList.unregisterAll(this);relay.close();if(packetMirror!=null)try{packetMirror.close();}catch(Exception ignored){}if(expansion!=null)try{expansion.close();}catch(Exception ignored){}for(Session s:sessions.values()){if(s.player.isOnline()){s.player.setDisplayName(s.player.getName());if(s.lastTab!=null)s.player.setPlayerListName(null);}}sessions.clear();refresh.clear();history.clear();replays.clear();}
}
