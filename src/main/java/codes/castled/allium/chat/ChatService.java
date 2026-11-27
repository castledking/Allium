package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.messaging.MessagePolicy;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.moderation.StaffSpyService;
import codes.castled.allium.module.*;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.text.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Region-safe chat owner for audiences, render/history and replay; one bounded storage lane. */
public final class ChatService implements Listener,AutoCloseable {
    private final PluginStart plugin;
    private final ChatRepository repository;
    private final Executor io;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final Queue<Session> refresh=new ConcurrentLinkedQueue<>();
    private final Set<Session> refreshInFlight=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Replay> replays=new ConcurrentHashMap<>();
    private final ChatHistory history=new ChatHistory(4096,200,15*60_000);
    private final Object discordLock=new Object();
    private final LinkedHashMap<String,Boolean> discordSeen=new LinkedHashMap<>();
    private final Map<String,String> discordLinks=new HashMap<>();
    private final ChatDecorations decorations;
    private volatile ChatSettings settings;
    private volatile boolean closed;
    private boolean paperChat;
    private TaskHandle tick=TaskHandle.NONE;
    private AutoCloseable packetMirror;
    private ChatRelay relay=ChatRelay.NONE;
    public record IdentitySnapshot(String display, String gradient, boolean ready) {}
    private record Preloaded(ChatRepository.State state, long loadedAt) {}
    private static final long PRELOAD_TTL_MILLIS = 60_000L;
    private static final int MAX_PRELOADED = 1024;
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();
    private final java.util.function.Function<Player, ChatDecorations.Value> decorQuery;
    private final ChatFilterPolicy filterPolicy;
    private static final class Session {
        final Player player;
        volatile String nickname,display,gradient;
        volatile String write="";
        volatile Set<String> reads=Set.of();
        volatile boolean ready;
        final AtomicBoolean busy=new AtomicBoolean();
        volatile long decorationsAt;
        volatile ChatDecorations.Value decor=ChatDecorations.Value.EMPTY;
        volatile String lastTab,lastDisplay;
        final List<Runnable> readyCallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
        Session(Player p){player=p;display=p.getName();gradient=p.getName();}
    }
    private static final class Replay {
        final Player player;
        final boolean clearing;
        int clear;
        boolean headerSent;
        final Set<String> sent = new HashSet<>();
        volatile boolean dirty;
        Replay(Player player, boolean clearing) {
            this.player = player;
            this.clearing = clearing;
        }
    }
    public ChatService(PluginStart plugin,ChatRepository repository,Executor io){this(plugin,repository,io,null);}
    ChatService(PluginStart plugin,ChatRepository repository,Executor io,ChatRelay relay){
        this(plugin,repository,io,relay,null,System::currentTimeMillis);
    }
    ChatService(PluginStart plugin,ChatRepository repository,Executor io,ChatRelay relay,
                java.util.function.Function<Player,ChatDecorations.Value> decorQuery){
        this(plugin,repository,io,relay,decorQuery,System::currentTimeMillis);
    }
    ChatService(PluginStart plugin,ChatRepository repository,Executor io,ChatRelay relay,
                java.util.function.Function<Player,ChatDecorations.Value> decorQuery,
                java.util.function.LongSupplier clock){
        this.plugin=plugin;this.repository=repository;this.io=io;
        settings=ChatSettings.read(plugin.getConfig());
        decorations=new ChatDecorations(plugin);
        this.decorQuery=decorQuery!=null?decorQuery:decorations::query;
        this.filterPolicy=new ChatFilterPolicy(clock!=null?clock:System::currentTimeMillis);
        if(relay!=null)this.relay=relay;
    }
    public void enable(){
        plugin.getServer().getPluginManager().registerEvents(this,codes.castled.allium.PluginStart.bukkitPlugin(plugin));
        paperChat=installPaperInput();installInteractiveGuard();
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("packetevents")||plugin.getServer().getPluginManager().isPluginEnabled("PacketEvents"))packetMirror=new ChatPacketMirror(this);}
        catch(LinkageError|RuntimeException error){plugin.logger().warning("Optional chat packet mirror unavailable; replay covers Allium-rendered history only.");}
        for(Player p:plugin.getServer().getOnlinePlayers())join(p);
        tick=plugin.getScheduler().repeatOnServer(()->{if(closed)return false;refresh();replayTick();return true;},50,50);
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("DiscordSRV"))relay=new DiscordChatRelay(plugin,this);}
        catch(LinkageError|RuntimeException error){plugin.logger().warning("Discord relay unavailable: "+error.getMessage());}
    }
    public boolean ready(UUID id){var session=sessions.get(id);return !closed&&session!=null&&session.ready;}
    public ChatHistory history(){return history;}
    public ChatSettings settings(){return settings;}
    public boolean ownsChat(){return !closed&&settings.enabled();}
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) {
        ChatSettings replacement = ChatSettings.read(candidateRoot);
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(() -> applySettings(replacement));
    }
    public void reload(){applySettings(ChatSettings.read(plugin.getConfig()));}
    private void applySettings(ChatSettings replacement){settings=replacement;for(Session s:sessions.values()){s.decorationsAt=0;if(!replacement.channels().containsKey(s.write))tell(s.player,Messages.CHAT_WRITE_CHANNEL_REMOVED);}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void preLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent e){
        if(closed||e.getLoginResult()!=org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED)return;
        long now=System.currentTimeMillis();
        preloaded.entrySet().removeIf(entry->now-entry.getValue().loadedAt()>=PRELOAD_TTL_MILLIS);
        if(preloaded.size()>=MAX_PRELOADED)return;
        try{
            var stored=repository.load(e.getUniqueId());
            preloaded.put(e.getUniqueId(),new Preloaded(stored,System.currentTimeMillis()));
        }catch(Exception ignored){
            // Join falls back to bounded asynchronous loading if pre-login lookup failed.
        }
    }
    @EventHandler(priority=EventPriority.LOWEST) public void joined(PlayerJoinEvent e){join(e.getPlayer());}
    private void join(Player p){
        var session=new Session(p);
        sessions.put(p.getUniqueId(),session);
        refresh.add(session);
        session.decor=decorQuery.apply(p);
        session.decorationsAt=System.currentTimeMillis();
        Preloaded pre=preloaded.remove(p.getUniqueId());
        if(pre!=null&&System.currentTimeMillis()-pre.loadedAt()<PRELOAD_TTL_MILLIS){
            applyLoaded(session,pre.state());
            return;
        }
        load(session,0);
    }
    private void applyLoaded(Session session,ChatRepository.State stored){
        Player p=session.player;
        session.nickname=stored.nickname();
        session.write=stored.writeChannel().isEmpty()?settings.defaultChannel():stored.writeChannel();
        var reads=new LinkedHashSet<>(stored.reads());reads.add(settings.defaultChannel());
        if(stored.writeChannel().isEmpty())for(var c:settings.channels().values())if(p.hasPermission("allium.channel.autojoin."+c.id()+".read"))reads.add(c.id());
        session.reads=Set.copyOf(reads);
        if(session.decorationsAt==0){session.decor=decorQuery.apply(p);session.decorationsAt=System.currentTimeMillis();}
        session.ready=true;
        update(session);
        var callbacks=List.copyOf(session.readyCallbacks);
        session.readyCallbacks.clear();
        for(Runnable cb:callbacks)cb.run();
    }
    private void load(Session session,int attempt){
        Player p=session.player;
        workFor(p,()->repository.load(p.getUniqueId()),stored->{
            if(!same(session))return;
            applyLoaded(session,stored);
        },error->{if(same(session)){
            if(attempt<5)BukkitServerThread.runFor(plugin,plugin.getScheduler(),p,()->{if(same(session))load(session,attempt+1);},()->{},Math.min(10_000,500L<<attempt));
            else {tell(p,Messages.CHAT_PROFILE_LOAD_FAILED);plugin.logger().warning("Chat profile load failed for "+p.getUniqueId());}
        }});
    }
    /** Runs at MONITOR so HIGH-priority quit formatters still see the player's nickname and gradient snapshot. */
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){
        UUID id=e.getPlayer().getUniqueId();
        preloaded.remove(id);
        Session s=sessions.remove(id);
        if(s!=null){
            s.readyCallbacks.clear();
            refresh.remove(s);refreshInFlight.remove(s);
        }
        replays.remove(id);history.forget(id);filterPolicy.forget(id);
        if(s!=null&&s.lastTab!=null&&e.getPlayer().isOnline())e.getPlayer().setPlayerListName(null);
    }
    /** Returns the current display/gradient identity snapshot for join/quit templates. */
    public IdentitySnapshot identitySnapshot(Player p){
        Session s=sessions.get(p.getUniqueId());
        if(s!=null&&s.ready){
            if(s.decorationsAt==0){s.decor=decorQuery.apply(p);s.decorationsAt=System.currentTimeMillis();update(s);}
            return new IdentitySnapshot(s.display,s.gradient,true);
        }
        String fallback=p.getDisplayName();
        if(fallback==null||fallback.isEmpty())fallback=p.getName();
        return new IdentitySnapshot(fallback,fallback,false);
    }
    /** Invokes {@code callback} as soon as {@code p}'s identity is loaded, or after {@code maxWaitMillis}. */
    public void whenIdentityReady(Player p,long maxWaitMillis,java.util.function.Consumer<IdentitySnapshot> callback){
        Session s=sessions.get(p.getUniqueId());
        if(closed||s==null||s.ready||maxWaitMillis<=0){
            callback.accept(identitySnapshot(p));
            return;
        }
        AtomicBoolean fired=new AtomicBoolean();
        s.readyCallbacks.add(()->{
            if(same(s)&&fired.compareAndSet(false,true))callback.accept(identitySnapshot(p));
        });
        if(s.ready){
            if(fired.compareAndSet(false,true))callback.accept(identitySnapshot(p));
            return;
        }
        BukkitServerThread.runFor(plugin,plugin.getScheduler(),p,()->{
            if(same(s)&&fired.compareAndSet(false,true))callback.accept(identitySnapshot(p));
        },()->{},maxWaitMillis);
    }
    private boolean same(Session session){return !closed&&sessions.get(session.player.getUniqueId())==session&&session.player.isOnline();}
    private void refresh(){
        int n=Math.min(settings.updatesPerTick(),refresh.size());long now=System.currentTimeMillis();
        for(int i=0;i<n;i++){
            Session s=refresh.poll();if(s==null)break;
            if(!same(s)){refreshInFlight.remove(s);continue;}
            refresh.add(s);
            if(BukkitServerThread.isFolia()){
                if(!refreshInFlight.add(s))continue;
                Player p=s.player;
                BukkitServerThread.runFor(plugin,plugin.getScheduler(),p,()->{
                    try{
                        if(!same(s))return;
                        long tickNow=System.currentTimeMillis();
                        if(tickNow-s.decorationsAt>=1000){s.decor=decorQuery.apply(p);s.decorationsAt=tickNow;}
                        if(s.ready)update(s);
                    }finally{refreshInFlight.remove(s);}
                },()->refreshInFlight.remove(s),0L);
            }else{
                if(now-s.decorationsAt>=1000){s.decor=decorQuery.apply(s.player);s.decorationsAt=now;}
                if(s.ready)update(s);
            }
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
        BukkitServerThread.runFor(plugin,plugin.getScheduler(),source,()->send(source,null,body,allowed),()->{},0L);
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
                    ((Cancellable)raw).setCancelled(true);
                    Set<UUID> frozenAudience=Set.copyOf(audience);
                    BukkitServerThread.runFor(plugin,plugin.getScheduler(),source,()->send(source,null,body,frozenAudience),()->{},0L);
                }catch(ReflectiveOperationException error){((Cancellable)raw).setCancelled(true);plugin.logger().warning("Paper chat input refused: "+error.getMessage());}
            },PluginStart.bukkitPlugin(plugin),true);return true;
        }catch(ClassNotFoundException unavailable){return false;}
        catch(ReflectiveOperationException|LinkageError error){throw new IllegalStateException("Paper chat adapter unavailable",error);}
    }
    private static final class PaperText {
        static String plain(Object component){return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component)component);}
    }
    private boolean allowed(Player p){
        var s=sessions.get(p.getUniqueId());if(s==null||!s.ready){tell(p,Messages.CHAT_PROFILE_NOT_READY);return false;}
        if(plugin.getModuleManager().getModule("moderation") instanceof ModerationModule m&&m.isEnabled()){
            if(!m.moderation().isMuteStateReady(p.getUniqueId())||m.moderation().isMuted(p.getUniqueId())){tell(p,Messages.CHAT_MUTED_OR_LOADING);return false;}
            if(m.moderation().isVanished(p)&&!plugin.getConfig().getBoolean("moderation.vanish.allow-public-chat",false)){tell(p,Messages.CHAT_VANISHED_PUBLIC);return false;}
        }return true;
    }
    public boolean canRead(Player p,ChatSettings.Channel channel){return channel.local()||p.hasPermission("allium.channel.join."+channel.id()+".read")||p.hasPermission("allium.channel.join."+channel.id()+".write")||channel.id().equals(settings.staffChannel())&&p.hasPermission("allium.staffchat");}
    public boolean canWrite(Player p,ChatSettings.Channel channel){return p.hasPermission("allium.channel.join."+channel.id()+".write")||channel.id().equals(settings.staffChannel())&&p.hasPermission("allium.staffchat");}
    private boolean listening(Session viewer,ChatSettings.Channel channel){return canRead(viewer.player,channel)&&(channel.local()||viewer.reads.contains(channel.id()));}
    public static final String PERM_URL="allium.chat.url";
    public static final String PERM_FILTER_BYPASS="allium.chat.filter.bypass";
    public static final String PERM_FILTER_ALERTS="allium.chat.filter.alerts";
    public static final String PERM_QUESTION="allium.chat.question";
    public static final String PERM_MENTION="allium.chat.mention";
    public static final String PERM_IGNORE_EXEMPT="allium.chat.ignoreexempt";
    /** EssentialsChat's receive nodes (default true): without one, that kind of message is not delivered. */
    public static final String RECEIVE_LOCAL="allium.chat.receive.local",RECEIVE_SHOUT="allium.chat.receive.shout",
            RECEIVE_QUESTION="allium.chat.receive.question",RECEIVE_MENTION="allium.chat.receive.mention";
    private static final Pattern URL_PATTERN=Pattern.compile(
            "(?i)(?:https?://\\S+|www\\.\\S+|\\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.(?:com|net|org|gg|io|me|co|edu|gov|tv|xyz|dev|app|mc|info|biz|uk|ca|de|fr|au|jp|ru|br|es|it|nl|pl|se|no|fi|ch|at|be|dk|cz|eu|us|live|shop|store|site|online|cloud|fun|pro|club|tech|world|games|server|play)(?:/\\S*)?)");
    private static final Pattern MENTION_PATTERN=Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");
    static boolean containsUrl(String text){return text!=null&&URL_PATTERN.matcher(text).find();}
    public void send(Player p,String explicit,String raw,Set<UUID> originalAudience){
        if(BukkitServerThread.isFolia()){
            BukkitServerThread.onOwner(plugin,plugin.getScheduler(),p,()->sendOnOwner(p,explicit,raw,originalAudience));
            return;
        }
        sendOnOwner(p,explicit,raw,originalAudience);
    }
    private void sendOnOwner(Player p,String explicit,String raw,Set<UUID> originalAudience){
        if(!ownsChat()||!p.isOnline()||!allowed(p))return;
        Session s=sessions.get(p.getUniqueId());
        if(s==null||!same(s))return;
        if(!settings.channelsEnabled()&&explicit==null&&(!s.write.equals(settings.defaultChannel())||raw.startsWith("#"))){tell(p,Messages.CHAT_CHANNELS_DISABLED_SELECT_GLOBAL);return;}
        String target=!settings.channelsEnabled()?settings.defaultChannel():explicit==null?s.write:explicit;
        if(settings.channelsEnabled()&&explicit==null&&target.equals(settings.staffChannel())&&raw.startsWith(">")){target=settings.defaultChannel();raw=raw.substring(1).strip();}
        else if(settings.channelsEnabled()&&explicit==null&&raw.startsWith("#")&&!raw.matches("^#[0-9a-fA-F]{6}.*")){target=settings.staffChannel();raw=raw.substring(1);}
        var c=settings.resolve(target);if(c==null||!canWrite(p,c)){tell(p,Messages.CHAT_CHANNEL_WRITE_DENIED);return;}
        boolean question=false;
        if(settings.channelsEnabled()&&explicit==null&&c.local()&&settings.questionEnabled()
                &&raw.startsWith(settings.questionPrefix())&&raw.length()>settings.questionPrefix().length()){
            if(!p.hasPermission(PERM_QUESTION)){tell(p,Messages.CHAT_QUESTION_DENIED);return;}
            var global=settings.resolve(settings.defaultChannel());
            if(global!=null&&canWrite(p,global)){
                c=global;
                raw=raw.substring(settings.questionPrefix().length()).strip();
                question=true;
            }
        }
        if(raw.isBlank()||raw.length()>settings.maxBody()){tell(p,Messages.CHAT_MESSAGE_LENGTH_INVALID);return;}
        if(settings.urlsRequirePermission()&&!p.hasPermission(PERM_URL)&&containsUrl(raw)){tell(p,Messages.CHAT_URL_DENIED);return;}
        if(!checkFilter(p,c.id(),raw))return;
        var allow=new UntrustedText.Allow(p.hasPermission("allium.chat.color"),p.hasPermission("allium.chat.hex"),p.hasPermission("allium.chat.gradient"));
        var origin=p.getLocation();
        // /g and /shout with a message are shouts; a ?question from local chat is a question.
        boolean shout=!question&&explicit!=null&&c.id().equals(settings.defaultChannel());
        String receive=question?RECEIVE_QUESTION:shout?RECEIVE_SHOUT:c.local()?RECEIVE_LOCAL:null;
        boolean senderIgnoreExempt=p.hasPermission(PERM_IGNORE_EXEMPT);
        MessagingModule messaging=plugin.getModuleManager()!=null&&plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m&&m.isEnabled()?m:null;
        if(c.local()&&BukkitServerThread.isFolia()){
            World originWorld=origin==null?null:origin.getWorld();
            Location originCopy=origin==null?null:origin.clone();
            double maxDistSq=(double)c.radius()*c.radius();
            Queue<Session> matched=new ConcurrentLinkedQueue<>();
            List<Session> remoteCandidates=new ArrayList<>();
            for(Session viewer:sessions.values()){
                Player recipient=viewer.player;
                boolean own=recipient==p&&canRead(p,c);
                if(!viewer.ready||!same(viewer)||!(own||listening(viewer,c))||originalAudience!=null&&!originalAudience.contains(recipient.getUniqueId()))continue;
                if(!own&&receive!=null&&!recipient.hasPermission(receive))continue;
                if(!own&&!senderIgnoreExempt&&messaging!=null&&messaging.ignores().ignores(recipient.getUniqueId(),p.getUniqueId())==MessagePolicy.Ignore.YES)continue;
                if(own){
                    matched.add(viewer);
                }else if(BukkitServerThread.entity(plugin,recipient).isServerThread()){
                    Location loc=recipient.getLocation();
                    if(originWorld!=null&&originCopy!=null&&loc!=null&&originWorld.equals(loc.getWorld())&&originCopy.distanceSquared(loc)<=maxDistSq){
                        matched.add(viewer);
                    }
                }else{
                    remoteCandidates.add(viewer);
                }
            }
            if(remoteCandidates.isEmpty()){
                finishSend(p,s,c,raw,allow,question,new ArrayList<>(matched));
                return;
            }
            final var channel=c;
            final String bodyRaw=raw;
            final boolean isQuestion=question;
            AtomicInteger remaining=new AtomicInteger(1);
            Runnable finish=()->{
                if(remaining.decrementAndGet()==0){
                    BukkitServerThread.onOwner(plugin,plugin.getScheduler(),p,()->{
                        if(!ownsChat()||!same(s))return;
                        finishSend(p,s,channel,bodyRaw,allow,isQuestion,new ArrayList<>(matched));
                    });
                }
            };
            for(Session viewer:remoteCandidates){
                remaining.incrementAndGet();
                BukkitServerThread.runFor(plugin,plugin.getScheduler(),viewer.player,()->{
                    try{
                        if(!closed&&same(viewer)){
                            Location loc=viewer.player.getLocation();
                            if(originWorld!=null&&originCopy!=null&&loc!=null&&originWorld.equals(loc.getWorld())&&originCopy.distanceSquared(loc)<=maxDistSq){
                                matched.add(viewer);
                            }
                        }
                    }finally{finish.run();}
                },finish,0L);
            }
            finish.run();
            return;
        }
        var recipients=new ArrayList<Session>();
        for(Session viewer:sessions.values()){
            Player recipient=viewer.player;
            // The sender always sees their own message, also in a channel they write to without reading it (/a <message>).
            boolean own=recipient==p&&canRead(p,c);
            if(!viewer.ready||!same(viewer)||!(own||listening(viewer,c))||originalAudience!=null&&!originalAudience.contains(recipient.getUniqueId()))continue;
            if(c.local()&&(!origin.getWorld().equals(recipient.getWorld())||origin.distanceSquared(recipient.getLocation())>(double)c.radius()*c.radius()))continue;
            if(!own&&receive!=null&&!recipient.hasPermission(receive))continue;
            if(!own&&!senderIgnoreExempt&&messaging!=null&&messaging.ignores().ignores(recipient.getUniqueId(),p.getUniqueId())==MessagePolicy.Ignore.YES)continue;
            recipients.add(viewer);
        }
        finishSend(p,s,c,raw,allow,question,recipients);
    }
    private void finishSend(Player p,Session s,ChatSettings.Channel c,String raw,UntrustedText.Allow allow,boolean question,List<Session> recipients){
        Set<Player> mentioned=new LinkedHashSet<>();
        FormattedText body=parseWithMentions(p,raw,allow,recipients,mentioned);
        String id=history.nextId();var render=new LinkedHashMap<UUID,String>();
        String format=question?settings.questionFormat():(c.id().equals(settings.defaultChannel())?settings.groups().getOrDefault(s.decor.group(),c.format()):c.format());
        if(!settings.formatting())format="{name}: {message}";
        var values=values(s,body);
        var rendered = ChatOutput.prepare(format, values, id, p.getName(), s.decor.group(), plugin.getConfig(), decorations.hooks(p));
        for(Session viewer:recipients){
            render.put(viewer.player.getUniqueId(),rendered.forViewer(viewer.player));
        }
        var message=history.add(id,p.getUniqueId(),p.getName(),c.id(),System.currentTimeMillis(),render);
        for(var entry:render.entrySet()){Session viewer=sessions.get(entry.getKey());if(viewer!=null&&!replays.containsKey(entry.getKey())&&!message.deleted().get())ChatOutput.send(viewer.player,entry.getValue());}
        if(!mentioned.isEmpty()&&!settings.mentionSound().isBlank()&&plugin.getMessageManager()!=null){
            MessageSoundSpec spec=new MessageSoundSpec(settings.mentionSound(),settings.mentionVolume(),settings.mentionPitch());
            for(Player targetPlayer:mentioned){
                if(!targetPlayer.getUniqueId().equals(p.getUniqueId())&&render.containsKey(targetPlayer.getUniqueId())){
                    UtilitySupport.onOwner(plugin,targetPlayer,()->plugin.getMessageManager().playMessageSound(targetPlayer,spec));
                }
            }
        }
        if(c.local()&&!p.hasPermission(StaffSpyService.PERM_CHAT_SPY_EXEMPT)){
            notifyLocalSpy(p,body,render.keySet());
        }
        if(!c.local()&&!c.discordId().isEmpty())relay.send(message,c,p.getName(),body.toPlainText());
        plugin.getServer().getConsoleSender().sendMessage("["+c.id()+"] "+p.getName()+": "+body.toPlainText()+" ["+id+"]");
    }
    private static boolean canSeePlayer(CommandSender viewer,Player target){
        return BukkitServerThread.isFolia()
                ? PlayerNameVisibility.canSeeTarget(viewer,target)
                : PlayerNameVisibility.canSee(viewer,target);
    }
    private FormattedText parseWithMentions(Player sender,String raw,UntrustedText.Allow allow,List<Session> recipients,Set<Player> mentioned){
        if(!settings.mentionsEnabled()||!sender.hasPermission(PERM_MENTION)||!raw.contains("@")){
            return UntrustedText.parse(raw,allow);
        }
        Map<String,Player> visibleByName=new HashMap<>();
        for(Session viewer:recipients){
            Player r=viewer.player;
            // Without allium.chat.receive.mention a player is not highlighted or pinged.
            if(canSeePlayer(sender,r)&&r.hasPermission(RECEIVE_MENTION)){
                visibleByName.put(r.getName().toLowerCase(Locale.ROOT),r);
            }
        }
        Matcher matcher=MENTION_PATTERN.matcher(raw);
        List<FormattedText.StyledGlyph> glyphs=new ArrayList<>();
        int last=0;boolean matchedAny=false;
        while(matcher.find()){
            Player target=visibleByName.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if(target==null)continue;
            matchedAny=true;
            mentioned.add(target);
            if(matcher.start()>last){
                glyphs.addAll(UntrustedText.parse(raw.substring(last,matcher.start()),allow).glyphs());
            }
            glyphs.addAll(TextFormatter.parse(settings.mentionColor()+"@"+target.getName()).glyphs());
            last=matcher.end();
        }
        if(!matchedAny)return UntrustedText.parse(raw,allow);
        if(last<raw.length()){
            glyphs.addAll(UntrustedText.parse(raw.substring(last),allow).glyphs());
        }
        return new FormattedText(glyphs);
    }
    private boolean checkFilter(Player sender,String channelId,String raw){
        ChatFilterSettings fs=settings.filter();
        if(!fs.enabled())return true;
        if(fs.exemptStaffChannel()&&channelId.equals(settings.staffChannel()))return true;
        if(sender.hasPermission(PERM_FILTER_BYPASS))return true;
        ChatFilterPolicy.Decision d=filterPolicy.evaluate(sender.getUniqueId(),raw,fs);
        if(!d.alerted())return true;
        if(d.blocked()){
            switch(d.kind()){
                case PHRASE -> tell(sender,Messages.CHAT_FILTER_PHRASE_BLOCKED);
                case REPEAT -> tell(sender,Messages.CHAT_FILTER_REPEAT_BLOCKED);
                case RATE_LIMIT -> tell(sender,Messages.CHAT_FILTER_RATE_BLOCKED,"{time}",formatRetrySeconds(d.retryAfterMillis()));
            }
        }
        notifyFilterAudit(sender,channelId,raw,d,fs);
        return !d.blocked();
    }
    private static String formatRetrySeconds(long millis){
        if(millis<=0L)return "1";
        long tenths=(millis+99L)/100L;
        if(tenths%10L==0L)return Long.toString(tenths/10L);
        return (tenths/10L)+"."+(tenths%10L);
    }
    private void notifyFilterAudit(Player sender,String channelId,String raw,ChatFilterPolicy.Decision decision,ChatFilterSettings fs){
        String clean=UntrustedText.clean(raw==null?"":raw).strip();
        String preview=clean.length()>180?clean.substring(0,180)+"...":clean;
        String action=decision.blocked()?"BLOCK":"ALERT_ONLY";
        String rule=(decision.blocked()?"":"[alert-only] ")+decision.kind().name().toLowerCase(Locale.ROOT)+":"+decision.ruleDetail();
        plugin.logger().info("ChatFilter action="+action+" player="+sender.getName()+" ("+sender.getUniqueId()+") channel="+channelId+" rule="+rule+" message=\""+preview+"\"");
        if(fs.notifyStaff()&&plugin.getMessageManager()!=null){
            String template=plugin.getMessageManager().resolve(Messages.CHAT_FILTER_STAFF_ALERT,"{player}",sender.getName(),"{rule}",rule);
            if(template!=null&&!template.isEmpty()){
                FormattedText alert=UntrustedText.insert(template,UntrustedText.parse(preview,UntrustedText.Allow.NONE));
                for(Player observer:List.copyOf(plugin.getServer().getOnlinePlayers())){
                    if(!observer.hasPermission(PERM_FILTER_ALERTS))continue;
                    UtilitySupport.onOwner(plugin,observer,()->TextSender.sendLiteral(observer,alert));
                }
            }
        }
        if(fs.auditToModeration()&&plugin.getModuleManager()!=null
                &&plugin.getModuleManager().getModule("moderation") instanceof ModerationModule mod
                &&mod.isEnabled()&&mod.repository()!=null){
            var repo=mod.repository();
            UUID targetId=sender.getUniqueId();
            String targetName=sender.getName();
            String detail="channel="+channelId+" action="+action+" rule="+rule+" msg="+preview;
            try{
                io.execute(()->{
                    try{repo.recordAudit("CHAT_FILTER","ChatFilter",targetId,targetName,detail);}
                    catch(Exception err){plugin.logger().warning("Could not record chat filter audit: "+err.getMessage());}
                });
            }catch(RejectedExecutionException busy){
                plugin.logger().warning("Chat audit pool busy; skipped SQL audit for "+targetId);
            }
        }
    }
    private void notifyLocalSpy(Player sender,FormattedText body,Set<UUID> delivered){
        if(plugin.getModuleManager()==null)return;
        if(!(plugin.getModuleManager().getModule("moderation") instanceof ModerationModule mod)||!mod.isEnabled()||mod.spy()==null)return;
        StaffSpyService spy=mod.spy();
        String template=plugin.getMessageManager().resolve(Messages.CHAT_SPY_FORMAT,"{player}",sender.getName());
        if(template==null||template.isEmpty())return;
        FormattedText formatted=UntrustedText.insert(template,body);
        for(Player observer:List.copyOf(plugin.getServer().getOnlinePlayers())){
            if(delivered.contains(observer.getUniqueId()))continue;
            if(!observer.hasPermission(StaffSpyService.PERM_CHAT_SPY))continue;
            if(!spy.isSpyingLocal(observer.getUniqueId(),sender.getUniqueId()))continue;
            if(!canSeePlayer(observer,sender))continue;
            UtilitySupport.onOwner(plugin,observer,()->TextSender.sendLiteral(observer,formatted));
        }
    }
    /** {@code /me <message>}: {@code chat.action-format} to everyone online, as chat (deletable, never relayed). */
    private void action(CommandSender sender,String[] args){
        if(!(sender instanceof Player p))throw new ChatFailure(Messages.CHAT_CHANNEL_PLAYER_ONLY);
        if(args.length==0)throw new UsageFailure();
        if(!allowed(p))return;
        String raw=String.join(" ",args);if(raw.isBlank()||raw.length()>settings.maxBody()){tell(p,Messages.CHAT_MESSAGE_LENGTH_INVALID);return;}
        if(settings.urlsRequirePermission()&&!p.hasPermission(PERM_URL)&&containsUrl(raw)){tell(p,Messages.CHAT_URL_DENIED);return;}
        if(!checkFilter(p,"action",raw))return;
        Session s=sessions.get(p.getUniqueId());
        FormattedText body=UntrustedText.parse(raw,new UntrustedText.Allow(p.hasPermission("allium.chat.color"),p.hasPermission("allium.chat.hex"),p.hasPermission("allium.chat.gradient")));
        String format=plugin.getConfig().getString("chat.action-format","&5* <display_name> &5<message>");
        String id=history.nextId();var render=new LinkedHashMap<UUID,String>();var values=values(s,body);
        var rendered = ChatOutput.prepare(format, values, id, p.getName(), s.decor.group(), plugin.getConfig(), decorations.hooks(p));
        for(Session viewer:sessions.values())if(viewer.ready&&same(viewer))render.put(viewer.player.getUniqueId(),rendered.forViewer(viewer.player));
        var message=history.add(id,p.getUniqueId(),p.getName(),"action",System.currentTimeMillis(),render);
        for(var entry:render.entrySet()){Session viewer=sessions.get(entry.getKey());if(viewer!=null&&!replays.containsKey(entry.getKey())&&!message.deleted().get())ChatOutput.send(viewer.player,entry.getValue());}
        plugin.getServer().getConsoleSender().sendMessage("[action] * "+p.getName()+" "+body.toPlainText()+" ["+id+"]");
    }
    private Map<String,FormattedText> values(Session s,FormattedText body){
        var values=new LinkedHashMap<String,FormattedText>();values.put("prefix",TextFormatter.parse(s.decor.prefix()));values.put("name",name(s,false));values.put("gradient_name",name(s,true));values.put("suffix",TextFormatter.parse(s.decor.suffix()));values.put("player",UntrustedText.parse(s.player.getName(),UntrustedText.Allow.NONE));values.put("message",body);return values;
    }
    public void inbound(String discordId,String destination,String author,String body){
        plugin.getScheduler().runOnServer(()->{
            if(!ownsChat())return;
            synchronized(discordLock){
                if(discordSeen.putIfAbsent(discordId,Boolean.TRUE)!=null)return;
                while(discordSeen.size()>4096)discordSeen.remove(discordSeen.keySet().iterator().next());
            }
            var c=settings.channels().values().stream().filter(v->!v.local()&&!v.discordId().isEmpty()&&v.discordId().equals(destination)).findFirst().orElse(null);if(c==null)return;
            String incomingBody=body.length()>settings.maxBody()?body.substring(0,settings.maxBody()):body;
            String id=history.nextId();var render=new LinkedHashMap<UUID,String>();Map<String,FormattedText> values=Map.of("discord_username",UntrustedText.parse(author,UntrustedText.Allow.NONE),"message",UntrustedText.parse(incomingBody,UntrustedText.Allow.NONE));
            var rendered = ChatOutput.prepare(c.discordIn(), values, id, "Discord", "default", plugin.getConfig(), ChatOutput.Hooks.PLAIN);
            for(Session s:sessions.values())if(s.ready&&same(s)&&listening(s,c))render.put(s.player.getUniqueId(),rendered.forViewer(s.player));
            var message=history.add(id,new UUID(0,0),author,c.id(),System.currentTimeMillis(),render);
            synchronized(discordLock){
                discordLinks.put(discordId,id);
                while(discordLinks.size()>4096)discordLinks.remove(discordLinks.keySet().iterator().next());
            }
            plugin.getServer().getConsoleSender().sendMessage("[Discord/"+c.id()+"] "+UntrustedText.clean(author)+": "+UntrustedText.clean(incomingBody)+" ["+id+"]");
            render.forEach((viewer,json)->{var s=sessions.get(viewer);if(s!=null&&!replays.containsKey(viewer)&&!message.deleted().get())ChatOutput.send(s.player,json);});
        });
    }
    public void discordDeleted(String id){plugin.getScheduler().runOnServer(()->{String linked;synchronized(discordLock){linked=discordLinks.get(id);}if(linked!=null)deleteIds(List.of(linked));});}
    private void deleteIds(List<String> ids){
        Set<UUID> affected=new HashSet<>();
        for (String id : ids) {
            if (!history.delete(id)) continue;
            history.find(id).ifPresent(message -> message.rendered().keySet().stream()
                    .filter(viewer -> history.retained(viewer, id)).forEach(affected::add));
            relay.delete(id);
        }
        for(UUID viewer:affected){
            Session s=sessions.get(viewer);if(s==null||!same(s))continue;
            replays.compute(viewer,(key,existing)->{
                if(existing!=null){
                    synchronized(existing){if(!existing.clearing||!existing.sent.isEmpty())existing.dirty=true;}
                    return existing;
                }
                return new Replay(s.player,false);
            });
        }
    }
    private void replayTick(){
        int budget=settings.packetBudget();var jobs=new ArrayList<>(replays.entrySet());
        for(var entry:jobs){if(budget<=0)break;UUID viewerId=entry.getKey();Replay job=entry.getValue();Session s=sessions.get(viewerId);if(s==null||s.player!=job.player||!same(s)){replays.remove(viewerId,job);continue;}
            boolean exhausted;
            synchronized(job){
                while(budget>0&&job.clear<settings.clearEntries()){ChatOutput.send(job.player,ChatOutput.blank(job.clear++));budget--;}
                if(job.clear<settings.clearEntries())continue;
                if (!job.clearing && !job.headerSent && plugin.getConfig().getBoolean("chat.deletion_resend.header_enabled")) {
                    if (budget == 0) continue;
                    String header = plugin.getConfig().getString("chat.deletion_resend.header", "");
                    if (!header.isEmpty()) {
                        ChatOutput.replay(job.player, net.md_5.bungee.chat.ComponentSerializer.toString(
                                SpigotComponents.of(TextFormatter.parse(header))));
                        budget--;
                    }
                    job.headerSent = true;
                }
                exhausted=true;
                for(var message:history.visible(viewerId,System.currentTimeMillis())){
                    if(job.sent.contains(message.id())||message.deleted().get())continue;
                    // Foreign packets reached the client already. Only Allium messages queued during a clear need delivery.
                    if (job.clearing && message.channel().equals("external")) continue;
                    if(budget<=0){exhausted=false;break;}
                    ChatOutput.replay(job.player,message.rendered().get(viewerId));job.sent.add(message.id());budget--;
                }
            }
            if(exhausted){
                replays.compute(viewerId,(key,current)->{
                    if(current!=job)return current;
                    return job.dirty?new Replay(job.player,false):null;
                });
            }
        }
    }

    public void clearChat(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        if (!settings.enabled()) {
            tell(sender, Messages.CHAT_MODULE_DISABLED);
            return;
        }
        if (context.args().length > 1) {
            context.sendInvalidArgs(context.spec().selfUsage());
            return;
        }
        Collection<? extends Player> audience;
        if (context.args().length == 0) {
            audience = List.copyOf(plugin.getServer().getOnlinePlayers());
        } else {
            if (!(sender instanceof Player player && player.getName().equalsIgnoreCase(context.args()[0]))
                    && !sender.hasPermission("allium.clearchat.others")) {
                tell(sender, Messages.NO_PERMISSION, "{cmd}", "clearchat");
                return;
            }
            Player target = plugin.getServer().getPlayerExact(context.args()[0]);
            if (target == null) {
                tell(sender, Messages.CHAT_PLAYER_NOT_FOUND);
                return;
            }
            audience = List.of(target);
        }
        if (settings.clearEntries() == 0) {
            tell(sender, Messages.CHAT_CLEAR_DISABLED);
            return;
        }
        for (Player viewer : audience) {
            history.forget(viewer.getUniqueId());
            replays.put(viewer.getUniqueId(), new Replay(viewer, true));
        }
        plugin.logger().info("Chat clear by " + sender.getName() + " for "
                + (context.args().length == 0 ? "all online players" : context.args()[0]));
        if (!audience.contains(sender)) tell(sender, Messages.CHAT_CLEAR_SUCCESS);
    }
    public void dispatch(BukkitCommandContext context){
        if(!settings.enabled()){tell(context.sender(),Messages.CHAT_MODULE_DISABLED);return;}
        CommandSender sender=context.sender();String action=context.spec().canonicalName();String[] args=context.args();
        if (action.equals("nick") && args.length == 0 && sender instanceof Player player) {
            var menus = plugin.getServer().getServicesManager().load(codes.castled.allium.api.SettingsPresentation.class);
            Session session = sessions.get(player.getUniqueId());
            List<String> initial = session == null || session.nickname == null ? List.of() : List.of(session.nickname);
            if (menus != null && menus.edit(player, "nick", null, initial, values -> {
                if (!player.hasPermission("allium.nick") || plugin.getCommandManager().getFamilyPolicy().isDisabled("nick")) return;
                String raw = values.get(0);
                applyNicknameFromGui(player, player.getUniqueId(), raw.isBlank() ? null : raw);
            })) return;
        }
        try{switch(action){case "nick"->nick(sender,args);case "channel"->channel(sender,context.label(),args);case "delmsg"->delete(sender,args);case "action"->action(sender,args);default->throw new IllegalArgumentException();}}
        catch(UsageFailure usage){context.sendInvalidArgs(context.spec().selfUsage());}
        catch(IllegalArgumentException error){tell(sender,error instanceof ChatFailure failure?failure.message:Messages.CHAT_ARGUMENT_ERROR,"{message}",Objects.toString(error.getMessage(),"Invalid chat arguments."));}
    }
    public void realName(BukkitCommandContext context) {
        String query = TextFormatter.parse(String.join(" ", context.args())).toPlainText().strip();
        if (query.isEmpty()) {
            context.sendInvalidArgs(context.spec().selfUsage());
            return;
        }
        var sender = context.sender();
        List<String> matches = sessions.values().stream()
                .filter(session -> session.ready && session.player.isOnline() && session.nickname != null)
                .filter(session -> TextFormatter.parse(session.nickname).toPlainText().equalsIgnoreCase(query))
                .filter(session -> canSeePlayer(sender, session.player))
                .map(session -> session.player.getName()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (matches.isEmpty()) {
            tell(sender, Messages.REALNAME_NOT_FOUND);
        } else {
            tell(sender, Messages.REALNAME_FOUND, "{players}", String.join(", ", matches));
        }
    }

    public void applyNicknameFromGui(Player viewer, UUID targetId, @org.jetbrains.annotations.Nullable String raw) {
        if (!settings.enabled()) {
            tell(viewer, Messages.CHAT_MODULE_DISABLED);
            return;
        }
        if (raw != null) {
            if (!raw.equals(UntrustedText.clean(raw))) {
                tell(viewer, Messages.CHAT_NICK_CONTROL_TEXT);
                return;
            }
            var allow = new UntrustedText.Allow(viewer.hasPermission("allium.nick.color"),
                    viewer.hasPermission("allium.nick.hex"), viewer.hasPermission("allium.nick.gradient"));
            String plain = UntrustedText.parse(raw, allow).toPlainText();
            if (plain.codePointCount(0, plain.length()) > settings.nicknameLength()
                    || !plain.matches("[\\p{L}\\p{N}_ .-]+") || plain.isBlank()) {
                tell(viewer, Messages.CHAT_NICK_INVALID);
                return;
            }
        }
        saveNick(viewer, targetId, raw);
    }

    public void openNicknameEditor(BukkitCommandContext context, NicknameEditorGui gui) {
        if (!settings.enabled()) {
            tell(context.sender(), Messages.CHAT_MODULE_DISABLED);
            return;
        }
        if (!(context.sender() instanceof Player viewer)) {
            tell(context.sender(), Messages.CHAT_CHANNEL_PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(context.spec().selfUsage());
            return;
        }
        Player target = viewer;
        if (args.length == 1) {
            if (!viewer.hasPermission("allium.nick.others") && !viewer.hasPermission("allium.nick.other")) {
                tell(viewer, Messages.NO_PERMISSION);
                return;
            }
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !canSeePlayer(viewer, target)) {
                tell(viewer, Messages.CHAT_PLAYER_LOOKUP_FAILED);
                return;
            }
        }
        Session session = sessions.get(target.getUniqueId());
        String currentRaw = session != null && session.ready ? session.nickname : null;
        gui.open(viewer, target.getUniqueId(), target.getName(), currentRaw);
    }

    private void nick(CommandSender sender,String[] args){
        if(args.length<1||args.length>2)throw new UsageFailure();
        String raw=args[args.length-1];boolean reset=Set.of("off","reset").contains(raw.toLowerCase(Locale.ROOT));
        if(!reset){
            if(!raw.equals(UntrustedText.clean(raw)))throw new ChatFailure(Messages.CHAT_NICK_CONTROL_TEXT);
            var allow=new UntrustedText.Allow(sender.hasPermission("allium.nick.color"),sender.hasPermission("allium.nick.hex"),sender.hasPermission("allium.nick.gradient"));
            String plain=UntrustedText.parse(raw,allow).toPlainText();
            if(plain.codePointCount(0,plain.length())>settings.nicknameLength()||!plain.matches("[\\p{L}\\p{N}_ .-]+")||plain.isBlank())throw new ChatFailure(Messages.CHAT_NICK_INVALID);
        }
        if(args.length==1){if(!(sender instanceof Player p))throw new ChatFailure(Messages.CHAT_NICK_CONSOLE_TARGET);saveNick(sender,p.getUniqueId(),reset?null:raw);return;}
        if(!sender.hasPermission("allium.nick.others")){tell(sender,Messages.NO_PERMISSION);return;}
        Player online=plugin.getServer().getPlayerExact(args[0]);if(online!=null&&!canSeePlayer(sender,online)){tell(sender,Messages.CHAT_PLAYER_LOOKUP_FAILED);return;}
        plugin.getPlayerNames().resolve(args[0]).whenComplete((found,error)->BukkitServerThread.runFor(plugin,plugin.getScheduler(),sender,()->{if(closed)return;if(error!=null||found.isEmpty())tell(sender,Messages.CHAT_PLAYER_LOOKUP_FAILED);else saveNick(sender,found.get().uuid(),reset?null:raw);},()->{},0L));
    }
    private void saveNick(CommandSender sender,UUID id,String raw){
        String plain=raw==null?"":TextFormatter.parse(raw).toPlainText();
        if(raw!=null&&plain.matches("[A-Za-z0-9_]{1,16}")&&!sender.hasPermission("allium.nick.impersonate")){
            plugin.getPlayerNames().resolve(plain).whenComplete((found,error)->BukkitServerThread.runFor(plugin,plugin.getScheduler(),sender,()->{
                if(closed)return;if(error!=null){tell(sender,Messages.CHAT_NICK_LOOKUP_UNAVAILABLE);return;}
                if(found.isPresent()&&!found.get().uuid().equals(id)){tell(sender,Messages.CHAT_NICK_UNAVAILABLE);return;}
                persistNick(sender,id,raw);
            },()->{},0L));return;
        }
        persistNick(sender,id,raw);
    }
    private void persistNick(CommandSender sender,UUID id,String raw){
        Session session=sessions.get(id);if(session!=null&&!session.busy.compareAndSet(false,true)){tell(sender,Messages.CHAT_PROFILE_BUSY);return;}
        workFor(sender,()->{repository.nickname(id,raw);return repository.load(id);},stored->{
            Session current=sessions.get(id);
            if(session!=null&&session!=current)session.busy.set(false);
            if(current!=null){
                current.nickname=stored.nickname();
                current.busy.set(false);
                if(current.ready){
                    UtilitySupport.onOwner(plugin,current.player,()->{if(same(current)&&current.ready)update(current);});
                }
            }
            if(raw==null)tell(sender,Messages.NICK_RESET);else tell(sender,Messages.NICK_SET,"{nick}",stored.nickname());
        },error->{if(session!=null)session.busy.set(false);tell(sender,Messages.CHAT_NICK_SAVE_FAILED);});
    }
    private void channel(CommandSender sender,String label,String[] args){
        if(!(sender instanceof Player p))throw new ChatFailure(Messages.CHAT_CHANNEL_PLAYER_ONLY);Session s=sessions.get(p.getUniqueId());if(s==null||!s.ready)throw new ChatFailure(Messages.CHAT_PROFILE_NOT_READY);
        String alias=label.substring(label.lastIndexOf(':')+1);var shortcut=settings.resolve(alias);
        if(!settings.channelsEnabled()){
            if(ChatSettings.GLOBAL_LABELS.contains(alias)){if(args.length==0)choose(s,settings.defaultChannel(),true);else if(shout(p,alias))send(p,settings.defaultChannel(),String.join(" ",args),null);return;}
            throw new ChatFailure(Messages.CHAT_CHANNELS_DISABLED_USE_GLOBAL);
        }
        if(!alias.equals("channel")&&shortcut!=null){
            if(args.length>0){if(!shortcut.id().equals(settings.defaultChannel())||shout(p,alias))send(p,shortcut.id(),String.join(" ",args),null);return;}
            choose(s,s.write.equals(shortcut.id())?settings.defaultChannel():shortcut.id(),true);return;
        }
        if(args.length==0){tell(p,Messages.CHAT_CHANNELS_LIST,"{channels}",String.join(", ",complete(p,"")));return;}
        if(args.length<2||args.length>3)throw new UsageFailure();
        var target=settings.resolve(args[1]);if(target==null)throw new ChatFailure(Messages.CHAT_CHANNEL_UNKNOWN);
        if(args[0].equalsIgnoreCase("join")){if(args.length==3&&!Set.of("read","write").contains(args[2]))throw new ChatFailure(Messages.CHAT_CHANNEL_DIRECTION_INVALID);choose(s,target.id(),args.length<3||!args[2].equals("read"));}
        else if(args[0].equalsIgnoreCase("leave")){
            var reads=new LinkedHashSet<>(s.reads);reads.remove(target.id());String write=s.write.equals(target.id())?settings.defaultChannel():s.write;saveChannels(s,write,reads);
        }else throw new ChatFailure(Messages.CHAT_CHANNEL_ACTION_INVALID);
    }
    /** {@code /g <message>} and {@code /shout <message>}: one message to the default channel needs {@value #SHOUT}. */
    private boolean shout(Player p,String alias){if(p.hasPermission(SHOUT))return true;tell(p,Messages.HIDE_NO_PERMISSION,"{cmd}",alias);return false;}
    public static final String SHOUT="allium.chat.shout";
    private void choose(Session s,String id,boolean write){var c=settings.channels().get(id);if(c==null||!(write?canWrite(s.player,c):canRead(s.player,c)))throw new ChatFailure(Messages.CHAT_CHANNEL_JOIN_DENIED);var reads=new LinkedHashSet<>(s.reads);reads.add(id);saveChannels(s,write?id:s.write,reads);}
    private void saveChannels(Session s,String write,Set<String> reads){
        if(!s.busy.compareAndSet(false,true))throw new ChatFailure(Messages.CHAT_PROFILE_BUSY);
        workFor(s.player,()->{repository.channels(s.player.getUniqueId(),write,reads);return Boolean.TRUE;},saved->{
            s.busy.set(false);
            if(same(s)){s.write=write;s.reads=Set.copyOf(reads);tell(s.player,Messages.CHAT_CHANNEL_SAVED,"{channel}",write);}
        },error->{s.busy.set(false);tell(s.player,Messages.CHAT_CHANNEL_SAVE_FAILED);});
    }
    public List<String> complete(Player p,String prefix){return settings.channels().values().stream().filter(c->canRead(p,c)&&c.id().startsWith(prefix.toLowerCase(Locale.ROOT))).map(ChatSettings.Channel::id).sorted().limit(64).toList();}
    private void delete(CommandSender sender,String[] args){
        if(!(sender.hasPermission("allium.delmsg")||sender.hasPermission("allium.deletemsg")||sender.hasPermission("allium.staff"))){tell(sender,Messages.NO_PERMISSION);return;}
        if(args.length==1){if(history.find(args[0]).isEmpty()){tell(sender,Messages.CHAT_DELETE_NOT_FOUND);return;}auditDeletion(sender,List.of(args[0]));deleteIds(List.of(args[0]));tell(sender,Messages.CHAT_DELETE_SUCCESS);return;}
        if(args.length==2){final int amount;try{amount=Integer.parseInt(args[1]);}catch(NumberFormatException invalid){tell(sender,Messages.CHAT_DELETE_AMOUNT);return;}
            if(amount<1||amount>200){tell(sender,Messages.CHAT_DELETE_AMOUNT);return;}
            plugin.getPlayerNames().resolve(args[0]).whenComplete((found,error)->BukkitServerThread.runFor(plugin,plugin.getScheduler(),sender,()->{if(closed)return;if(error!=null||found.isEmpty()){tell(sender,Messages.CHAT_PLAYER_NOT_FOUND);return;}var ids=history.recentBySender(found.get().uuid(),amount).stream().map(ChatHistory.Message::id).toList();auditDeletion(sender,ids);deleteIds(ids);tell(sender,Messages.CHAT_DELETE_MULTIPLE);},()->{},0L));return;}
        throw new UsageFailure();
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
            },PluginStart.bukkitPlugin(plugin),false);
        }catch(ReflectiveOperationException error){plugin.logger().warning("InteractiveChat delayed-deletion guard unavailable.");}
    }
    private void auditDeletion(CommandSender sender,List<String> ids){plugin.logger().info("Chat deletion actor="+(sender instanceof Player p?p.getUniqueId():"CONSOLE")+" messages="+String.join(",",ids));}
    private void tell(CommandSender sender,Messages message,String... args){
        if(closed||sender instanceof Player p&&!p.isOnline())return;
        UtilitySupport.onOwner(plugin,sender,()->{
            if(!closed&&(!(sender instanceof Player p)||p.isOnline())){
                plugin.getMessageManager().send(sender,message,args);
            }
        });
    }
    /** Wrong or missing arguments: answered with the command's usage card. */
    private static final class UsageFailure extends IllegalArgumentException {UsageFailure(){super("usage");}}
    private static final class ChatFailure extends IllegalArgumentException {final Messages message;ChatFailure(Messages message){super(message.getDefault());this.message=message;}}
    @FunctionalInterface private interface Query<T>{T run()throws Exception;}
    private <T> void workFor(CommandSender target,Query<T> query,java.util.function.Consumer<T> done,java.util.function.Consumer<Throwable> failed){
        try{io.execute(()->{
            try{
                T result=query.run();
                BukkitServerThread.runFor(plugin,plugin.getScheduler(),target,()->{if(!closed)done.accept(result);},()->{if(!closed)done.accept(result);},0L);
            }catch(Exception error){
                BukkitServerThread.runFor(plugin,plugin.getScheduler(),target,()->{if(!closed)failed.accept(error);},()->{if(!closed)failed.accept(error);},0L);
            }
        });}
        catch(RejectedExecutionException error){failed.accept(error);}
    }
    @Override public void close(){
        closed=true;tick.cancel();HandlerList.unregisterAll(this);relay.close();
        if(packetMirror!=null)try{packetMirror.close();}catch(Exception ignored){}
        List<Session> snapshot=List.copyOf(sessions.values());
        sessions.clear();refresh.clear();refreshInFlight.clear();history.clear();replays.clear();filterPolicy.clear();
        for(Session s:snapshot){
            if(!s.player.isOnline())continue;
            boolean resetTab=s.lastTab!=null;
            if(!BukkitServerThread.isFolia()||!plugin.isEnabled()){
                s.player.setDisplayName(s.player.getName());
                if(resetTab)s.player.setPlayerListName(null);
            }else{
                UtilitySupport.onOwner(plugin,s.player,()->{
                    s.player.setDisplayName(s.player.getName());
                    if(resetTab)s.player.setPlayerListName(null);
                });
            }
        }
    }
}
