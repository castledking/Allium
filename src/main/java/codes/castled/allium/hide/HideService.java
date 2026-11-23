package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.CommandMapBridge;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Server-thread authorization plus immutable packet-thread snapshots. No disk work in command listeners. */
public final class HideService implements Listener,AutoCloseable {
    public static final String BYPASS="allium.hide.bypass",ADMIN="allium.hide.admin";
    public record Viewer(HideSettings settings,List<HidePolicy.Group> groups,boolean bypass,Map<String,List<String>> labels,long refreshed) {}
    private final PluginStart plugin;
    private final HideGroups groups;
    private final HidePermissionResolver permissions;
    private final ThreadPoolExecutor io;
    private final Path file;
    private final ConcurrentHashMap<UUID,Viewer> viewers=new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Player> poll=new ConcurrentLinkedQueue<>(),refresh=new ConcurrentLinkedQueue<>();
    private final Set<UUID> pollTracked=ConcurrentHashMap.newKeySet(),pollInFlight=ConcurrentHashMap.newKeySet(),refreshQueued=ConcurrentHashMap.newKeySet();
    private volatile HideSettings settings;
    private volatile boolean closed;
    private volatile Map<String,Command> commands=Map.of();
    private volatile Map<String,List<String>> labels=Map.of();
    private TaskHandle timer=TaskHandle.NONE;
    private volatile HidePacketHooks packets;
    private volatile YamlConfiguration config;
    private final AtomicBoolean updating=new AtomicBoolean();
    public HideService(PluginStart plugin){this.plugin=plugin;groups=new HideGroups(plugin);permissions=new HidePermissionResolver(plugin);io=plugin.getScheduler().boundedPool("Allium-Hide-Config",1,32);file=plugin.getDataFolder().toPath().resolve("hide.yml");}
    public void enable(){
        if(!Files.exists(file))plugin.saveResource("hide.yml",false);
        try{config=read();settings=prepare(config);normalize();}catch(Exception invalid){
            plugin.logger().warning("Invalid hide.yml; non-bypass command access is blocked until a valid reload.");
            config=new YamlConfiguration();config.set("settings.enabled",true);config.set("groups.default.whitelist",true);settings=HideSettings.read(config,Map.of());
        }
        var access=CommandMapBridge.inspectPlatformAccess(plugin.getServer(),plugin.logger()::warning);if(access!=null&&access.knownCommands()!=null)commands=access.knownCommands();
        refreshLabels();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);installPackets();
        for(Player player:List.copyOf(plugin.getServer().getOnlinePlayers())){enqueuePoll(player);refreshOnOwner(player,true,()->{});}
        timer=plugin.getScheduler().repeatOnServer(()->{if(closed)return false;tick();return true;},50,50);
        logCapability();
    }
    private void normalize()throws IOException{if(!config.contains("settings.use-allium-permission-resolver"))config.set("settings.use-allium-permission-resolver",true);codes.castled.allium.config.YamlFiles.normalize(file,config);}
    public HideSettings settings(){return settings;}
    public Viewer viewer(UUID id){return viewers.get(id);}
    private HideSettings prepare(YamlConfiguration c){
        var root=c.getConfigurationSection("groups");Set<String> names=root==null?Set.of():root.getKeys(false).stream().map(v->v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        HideSettings read=HideSettings.read(c,groups.parents(names));
        HideSettings kept=read.keepingVisible(this::ownsLabel);
        if(kept!=read){var dropped=new ArrayList<>(read.unsafe());dropped.removeAll(kept.unsafe());
            plugin.logger().info("hide.yml unsafe-commands-list names Allium's own "+dropped+"; those stay in the command list sent to players.");}
        return kept;
    }
    /** Whether a plain label is Allium's command (by name or alias) in the server's command map. */
    private boolean ownsLabel(String label){
        try{var command=plugin.getServer().getPluginCommand(label);return command!=null&&command.getPlugin()==plugin;}
        catch(RuntimeException unavailable){return false;}
    }
    private YamlConfiguration read()throws IOException,org.bukkit.configuration.InvalidConfigurationException{if(Files.size(file)>1_048_576)throw new IOException("hide.yml exceeds 1 MiB");var c=new YamlConfiguration();c.load(file.toFile());return c;}
    private Viewer refresh(Player p,boolean update){
        var selected=new ArrayList<>(settings.policy().select(groups.memberships(p,settings.policy().groupNames())));boolean bypass=p.hasPermission(BYPASS);
        if(bypass)selected.add(new HidePolicy.Group("bypass",true,List.of("*"),List.of(),List.of("*")));
        Viewer next=new Viewer(settings,List.copyOf(selected),bypass,labels,System.currentTimeMillis()),old=viewers.put(p.getUniqueId(),next);
        if(update&&(old==null||old.settings()!=next.settings()||old.bypass()!=next.bypass()||!old.groups().equals(next.groups())))queueRefresh(p);
        return next;
    }
    private void enqueuePoll(Player p){if(pollTracked.add(p.getUniqueId()))poll.add(p);}
    private void queueRefresh(Player p){if(refreshQueued.add(p.getUniqueId()))refresh.add(p);}
    void tick(){
        long now=System.currentTimeMillis();
        for(int n=Math.min(8,poll.size());n>0;n--){
            Player p=poll.poll();if(p==null)break;
            UUID id=p.getUniqueId();
            if(!pollTracked.contains(id)||!isCurrent(p)){pollTracked.remove(id);continue;}
            poll.add(p);
            var cached=viewers.get(id);
            if((cached==null||now-cached.refreshed()>=2000)&&pollInFlight.add(id)){
                refreshOnOwner(p,true,()->pollInFlight.remove(id));
            }
        }
        for(int n=4;n>0;n--){
            Player p=refresh.poll();if(p==null)break;
            refreshQueued.remove(p.getUniqueId());
            updateCommandsOnOwner(p);
        }
    }
    private void refreshOnOwner(Player p,boolean update,Runnable after){
        if(!BukkitServerThread.isFolia()||plugin.getScheduler()==null){
            try{if(!closed&&isCurrent(p))refresh(p,update);}finally{after.run();}
            return;
        }
        var owner=BukkitServerThread.entity(plugin,p);
        if(owner.isServerThread()){
            try{if(!closed&&isCurrent(p))refresh(p,update);}finally{after.run();}
            return;
        }
        TaskHandle handle=plugin.getScheduler().runOn(owner,()->{
            try{if(!closed&&isCurrent(p))refresh(p,update);}finally{after.run();}
        },after,0L);
        if(handle==TaskHandle.NONE)after.run();
    }
    private void updateCommandsOnOwner(Player p){
        if(!BukkitServerThread.isFolia()||plugin.getScheduler()==null){
            if(!closed&&isCurrent(p))p.updateCommands();
            return;
        }
        var owner=BukkitServerThread.entity(plugin,p);
        if(owner.isServerThread()){
            if(!closed&&isCurrent(p))p.updateCommands();
            return;
        }
        plugin.getScheduler().runOn(owner,()->{if(!closed&&isCurrent(p))p.updateCommands();},()->{},0L);
    }
    private boolean isCurrent(Player p){
        if(!p.isOnline())return false;
        Player current=plugin.getServer().getPlayer(p.getUniqueId());
        return current==null||current==p;
    }
    private void installPackets(){
        if(packets!=null)return;
        try{if(plugin.getServer().getPluginManager().isPluginEnabled("packetevents")||plugin.getServer().getPluginManager().isPluginEnabled("PacketEvents"))packets=new HidePackets(this,plugin);}
        catch(LinkageError|RuntimeException unavailable){plugin.logger().warning("Hide packet filtering unavailable; scoped completion roots are withheld from client trees.");}
    }
    private void logCapability(){if(settings.enabled())plugin.logger().info("Hide execution/root filtering enabled; packet filtering="+(packets!=null)+". Native command permissions remain authoritative.");}
    public Set<String> scopedRoots(Viewer v){
        if(v==null||v.bypass()||!v.settings().enabled())return Set.of();var out=new LinkedHashSet<String>();
        for(var group:v.groups())for(String path:group.completions())if(path.contains(" ")){out.add(HidePolicy.root(path));out.add(HidePolicy.plain(HidePolicy.root(path)));}
        return Set.copyOf(out);
    }
    private void refreshLabels(){
        var out=new HashMap<String,List<String>>();for(var entry:commands.entrySet()){
            Command command=entry.getValue();if(command instanceof PluginIdentifiableCommand pc){String owner=pc.getPlugin().getName().toLowerCase(Locale.ROOT);out.put(entry.getKey(),List.of(owner+":"+HidePolicy.plain(entry.getKey()),owner+":"+command.getName().toLowerCase(Locale.ROOT)));}
        }labels=Map.copyOf(out);
    }
    private List<String> variants(String path){
        var out=new ArrayList<String>();path=HidePolicy.normalize(path);out.add(path);String label=HidePolicy.root(path),args=path.substring(label.length());Command command=commands.get(label);
        if(command instanceof PluginIdentifiableCommand pc){String owner=pc.getPlugin().getName().toLowerCase(Locale.ROOT);out.add(owner+":"+HidePolicy.plain(label)+args);out.add(owner+":"+command.getName().toLowerCase(Locale.ROOT)+args);}
        return out;
    }
    private boolean groupAllows(Viewer v,String path,boolean completion){
        List<String> paths=variants(path);
        // A denied alias/qualified path must not escape another group's blacklist.
        for(var g:v.groups())if(!g.whitelist())for(String candidate:paths)if(!g.allows(candidate,completion))return false;
        if(v.groups().stream().noneMatch(HidePolicy.Group::whitelist))return true;
        return paths.stream().anyMatch(candidate->v.groups().stream().anyMatch(g->g.whitelist()&&g.allows(candidate,completion)));
    }
    /** Pure snapshot check used by Netty packet listeners, without Bukkit permission/provider calls. */
    public static boolean packetVisible(Viewer v,String path){
        if(v==null)return false;HideSettings s=v.settings();if(!s.enabled())return true;
        if(!s.namespacedVisible(path,v.bypass()))return false;if(v.bypass())return true;
        String root=HidePolicy.root(path);
        if(s.removeUnsafe()&&s.unsafe().contains(root))return false;
        if(s.filterSuggestions()&&s.suggestionPrefixes().stream().anyMatch(root::startsWith))return false;
        String normalized=HidePolicy.normalize(path),suffix=normalized.substring(root.length());
        var variants=new ArrayList<String>();variants.add(normalized);for(String label:v.labels().getOrDefault(root,List.of()))variants.add(label+suffix);
        for(var g:v.groups())if(!g.whitelist())for(String variant:variants)if(!g.allows(variant,true))return false;
        return v.groups().stream().noneMatch(HidePolicy.Group::whitelist)||variants.stream().anyMatch(variant->v.groups().stream().anyMatch(g->g.whitelist()&&g.allows(variant,true)));
    }
    private boolean visible(Player p,Viewer v,String path){return visible(p,v,path,null);}
    /** {@code memo} (per completion batch) reuses one resolver decision for arguments that cannot change it. */
    private boolean visible(Player p,Viewer v,String path,Map<String,Boolean> memo){
        if(!v.settings().enabled())return true;
        if(!v.settings().namespacedVisible(path,v.bypass()))return false;
        if(!v.bypass()){
            String root=HidePolicy.root(path);if(v.settings().removeUnsafe()&&v.settings().unsafe().contains(root))return false;
            if(v.settings().filterSuggestions()&&v.settings().suggestionPrefixes().stream().anyMatch(root::startsWith))return false;
            if(!groupAllows(v,path,true))return false;
        }
        if(!v.settings().useResolver()||commands.isEmpty())return true;
        if(memo==null)return permissions.resolve(p,path,commands,v.settings().permissionOverrides()).allowed();
        String key=permissions.decisionKey(commands.get(HidePolicy.root(path)),path);
        Boolean known=memo.get(key);if(known!=null)return known;
        boolean allowed=permissions.resolve(p,path,commands,v.settings().permissionOverrides()).allowed();memo.put(key,allowed);return allowed;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent event){
        if(closed||!settings.enabled())return;Player p=event.getPlayer();String line=HidePolicy.normalize(event.getMessage());Viewer v=refresh(p,false);
        if(v.bypass()&&v.settings().blockNamespacedForBypass()&&HidePolicy.root(line).contains(":" )||!groupAllows(v,line,false)){
            event.setCancelled(true);unknown(p,line);return;
        }
        if(!v.settings().useResolver())return;
        var decision=permissions.resolve(p,line,commands,v.settings().permissionOverrides());
        if(!decision.allowed()){
            event.setCancelled(true);if(!commands.containsKey(HidePolicy.root(line)))unknown(p,line);else plugin.getMessageManager().send(p,Messages.HIDE_NO_PERMISSION,"{cmd}",HidePolicy.root(line));
        }
    }
    public record HelpEntry(String path,String owner,String description,String usage,String permission) {
        public HelpEntry(String path,String owner,String description){this(path,owner,description,"/"+path,"");}
        public static HelpEntry of(PluginStart plugin,String path,Command command,String resolvedPermission){
            String owner=command instanceof PluginIdentifiableCommand pc?pc.getPlugin().getName():"Minecraft";
            String desc=Objects.toString(command.getDescription(),"").trim();
            String usage=normalizeUsage(command.getUsage(),path);
            String perm=resolvedPermission!=null&&!resolvedPermission.isBlank()?resolvedPermission.trim():Objects.toString(command.getPermission(),"").trim();
            if(plugin!=null&&plugin.getCommandManager()!=null&&owner.equalsIgnoreCase(plugin.getName())){
                String root=HidePolicy.root(path);
                var specOpt=plugin.getCommandManager().getFamilyPolicy().findSpec(root);
                if(specOpt.isPresent()){
                    var spec=specOpt.get();
                    if(desc.isBlank()&&spec.description()!=null)desc=spec.description().trim();
                    if((usage.isBlank()||usage.equals("/"+path))&&spec.selfUsage()!=null&&!spec.selfUsage().isBlank())usage=spec.selfUsage().trim();
                    if(perm.isBlank()&&spec.basePermission()!=null)perm=spec.basePermission().trim();
                }
            }
            if(usage.isBlank())usage="/"+path;
            return new HelpEntry(path,owner,desc,usage,perm);
        }
        public static String normalizeUsage(String raw,String path){
            if(raw==null||raw.isBlank())return "/"+path;
            String line=raw.lines().map(String::trim).filter(l->!l.isEmpty()).findFirst().orElse("");
            if(line.isEmpty())return "/"+path;
            String expanded=line.replaceAll("(?i)<command>",java.util.regex.Matcher.quoteReplacement(path));
            return expanded.startsWith("/")?expanded:"/"+expanded;
        }
    }
    public List<HelpEntry> helpEntries(CommandSender sender){
        Viewer v=sender instanceof Player p?refresh(p,false):null;
        var out=new TreeMap<String,HelpEntry>();
        for(var entry:commands.entrySet()){
            String root=entry.getKey();if(root.contains(":"))continue;Command command=entry.getValue();
            if(plugin!=null&&plugin.getCommandManager()!=null&&command instanceof PluginIdentifiableCommand pc
                    &&pc.getPlugin().getName().equalsIgnoreCase(plugin.getName())
                    &&plugin.getCommandManager().getFamilyPolicy().isDisabled(root))continue;
            var candidates=new LinkedHashSet<String>();candidates.add(root);
            if(v!=null&&v.settings().enabled())for(var group:v.groups())for(String rule:group.completions()){
                if(rule.contains(" ")&&!rule.contains("*")&&variants(root).stream().anyMatch(label->HidePolicy.root(rule).equals(label)||HidePolicy.root(rule).equals(root)))candidates.add(root+rule.substring(rule.indexOf(' ')));
            }
            for(String path:candidates){
                if(v!=null&&v.settings().enabled()&&(!packetVisible(v,path)||!groupAllows(v,path,false)))continue;
                String resolvedPerm=null;boolean allowed;
                if(v!=null&&v.settings().enabled()){
                    if(!v.settings().useResolver()){allowed=true;}
                    else{var decision=permissions.resolve((Player)sender,path,commands,v.settings().permissionOverrides());allowed=decision.allowed();resolvedPerm=decision.permission();}
                }else{allowed=command.testPermissionSilent(sender);}
                if(allowed)out.put(path,HelpEntry.of(plugin,path,command,resolvedPerm));
            }
        }
        return List.copyOf(out.values());
    }
    private void unknown(Player player,String line){
        String root=HidePolicy.root(line);String suggestion=root.contains(":")?null:closest(root,helpEntries(player).stream().map(HelpEntry::path).toList());
        plugin.getMessageManager().send(player,suggestion==null?Messages.HIDE_UNKNOWN:Messages.HIDE_SUGGESTION,"{cmd}",root,"{suggestion}",Objects.toString(suggestion,""));
    }
    static String closest(String input,List<String> allowed){
        if(input.isEmpty()||input.length()>64)return null;String best=null;int score=3;
        for(String candidate:allowed){if(candidate.contains(" ")||candidate.equals(input)||Math.abs(candidate.length()-input.length())>2)continue;
            int[] prev=new int[candidate.length()+1];for(int j=0;j<prev.length;j++)prev[j]=j;
            for(int i=1;i<=input.length();i++){int[] next=new int[prev.length];next[0]=i;for(int j=1;j<next.length;j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(input.charAt(i-1)==candidate.charAt(j-1)?0:1));prev=next;}
            int distance=prev[candidate.length()];if(distance<score){score=distance;best=candidate;}
        }return best;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void roots(PlayerCommandSendEvent event){
        if(closed)return;refreshLabels();Viewer v=refresh(event.getPlayer(),false);if(!settings.enabled())return;Set<String> scoped=scopedRoots(v);
        event.getCommands().removeIf(label->!visible(event.getPlayer(),v,label)||packets==null&&scoped.contains(HidePolicy.plain(label)));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void completions(TabCompleteEvent event){
        if(closed||!settings.enabled()||!(event.getSender() instanceof Player p)||!event.getBuffer().startsWith("/"))return;
        Viewer v=event.isAsynchronous()?viewers.get(p.getUniqueId()):refresh(p,false);String buffer=event.getBuffer();
        var memo=new HashMap<String,Boolean>();
        event.setCompletions(event.getCompletions().stream().filter(value->{String path=HidePolicy.suggestionPath(buffer,value,-1);return event.isAsynchronous()?packetVisible(v,path):visible(p,v,path,memo);}).toList());
    }
    @EventHandler public void joined(PlayerJoinEvent event){enqueuePoll(event.getPlayer());refresh(event.getPlayer(),true);}
    @EventHandler public void quit(PlayerQuitEvent event){UUID id=event.getPlayer().getUniqueId();viewers.remove(id);pollTracked.remove(id);pollInFlight.remove(id);refreshQueued.remove(id);HidePacketHooks currentPackets=packets;if(currentPackets!=null)currentPackets.forget(id);}
    @EventHandler public void pluginEnabled(PluginEnableEvent event){if(event.getPlugin().getName().equalsIgnoreCase("packetevents")){installPackets();for(Player p:List.copyOf(plugin.getServer().getOnlinePlayers()))queueRefresh(p);}}
    @EventHandler public void pluginDisabled(PluginDisableEvent event){if(event.getPlugin().getName().equalsIgnoreCase("packetevents")&&packets!=null){packets.close();packets=null;for(Player p:List.copyOf(plugin.getServer().getOnlinePlayers()))queueRefresh(p);}}
    /**
     * Reads and validates {@code hide.yml} without mutating live hide state or scheduling async work.
     * Used by {@code /allium reload} so invalid hide settings reject the reload before any changes publish.
     */
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload()
            throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        if (updating.get()) {
            throw new IllegalStateException("another hide reload or edit is currently in progress");
        }
        YamlConfiguration next = read();
        HideSettings prepared = prepare(next);
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(() -> {
            if (closed) return;
            config = next;
            settings = prepared;
            refreshLabels();
            for (Player p : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                refreshOnOwner(p, true, () -> {});
            }
            logCapability();
        });
    }
    public void reload(){update(null,null);}
    private void update(CommandSender actor,Consumer<YamlConfiguration> edit){
        if(!updating.compareAndSet(false,true)){if(actor!=null)send(actor,Messages.HIDE_BUSY);return;}
        try{io.execute(()->{
            try{YamlConfiguration next=read();if(edit!=null)edit.accept(next);
                plugin.getScheduler().runOnServer(()->{
                    if(closed){updating.set(false);return;}
                    try{HideSettings prepared=prepare(next);
                        io.execute(()->{try{
                            if(edit!=null)save(next);
                            plugin.getScheduler().runOnServer(()->{
                                if(closed){updating.set(false);return;}
                                String oldHint = settings.enabled() ? settings.hint() : "";
                                String newHint = prepared.enabled() ? prepared.hint() : "";
                                config=next;settings=prepared;refreshLabels();updating.set(false);
                                for(Player p:List.copyOf(plugin.getServer().getOnlinePlayers()))refreshOnOwner(p,true,()->{});
                                logCapability();
                                if (!Objects.equals(oldHint, newHint)) {
                                    plugin.refreshResourcePack(line -> {
                                        if (actor != null) UtilitySupport.onOwner(plugin, actor, () -> {
                                            if (!closed) codes.castled.allium.text.TextSender.send(actor, line);
                                        });
                                    });
                                }
                                if(actor!=null){send(actor,edit==null?Messages.HIDE_RELOADED:Messages.HIDE_UPDATED);audit(actor,"applied");}
                            });
                        }catch(Exception failure){failed(actor,failure);}});
                    }catch(Exception invalid){updating.set(false);plugin.logger().warning("Hide reload refused: "+invalid.getMessage());if(actor!=null)send(actor,Messages.HIDE_FAILED);}
                });
            }catch(Exception invalid){failed(actor,invalid);}
        });}catch(RejectedExecutionException busy){updating.set(false);if(actor!=null)send(actor,Messages.HIDE_BUSY);}
    }
    private void failed(CommandSender actor,Exception failure){plugin.logger().warning("Hide update failed: "+failure.getClass().getSimpleName());plugin.getScheduler().runOnServer(()->{if(closed){updating.set(false);return;}updating.set(false);if(actor!=null)send(actor,Messages.HIDE_FAILED);});}
    private void save(YamlConfiguration next)throws IOException{
        byte[] before=Files.readAllBytes(file);Path temp=Files.createTempFile(file.getParent(),".allium-hide-",".tmp");
        try{Files.writeString(temp,next.saveToString(),java.nio.charset.StandardCharsets.UTF_8);Files.write(file.resolveSibling("hide.yml.before-edit"),before);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    private void send(CommandSender to,Messages key,String... args){
        UtilitySupport.onOwner(plugin,to,()->{if(!closed&&(!(to instanceof Player p)||p.isOnline()))plugin.getMessageManager().send(to,key,args);});
    }
    public void admin(CommandSender actor,String[] args){admin(actor,args,usage->UtilitySupport.onOwner(plugin,actor,()->plugin.getMessageManager().send(actor,Messages.INVALID_ARGS,"{usage}",usage)));}
    /** {@code usage} shows the usage card for the narrowest form that was wrong ({@code /allium hide group}). */
    public void admin(CommandSender actor,String[] args,java.util.function.Consumer<String> usage){
        if(!actor.hasPermission(ADMIN)){send(actor,Messages.NO_PERMISSION);return;}
        try{
            if(args.length==0||args[0].equals("status")){send(actor,Messages.HIDE_INFO,"{message}","enabled="+settings.enabled()+", groups="+String.join(", ",settings.policy().groupNames())+", packet-filter="+(packets!=null));return;}
            if(args[0].equals("reload")&&args.length==1){update(actor,null);return;}
            if(args[0].equals("enabled")&&args.length==2&&Set.of("true","false").contains(args[1])){boolean on=Boolean.parseBoolean(args[1]);audit(actor,"enabled");update(actor,c->c.set("settings.enabled",on));return;}
            if(args[0].equals("resolve")&&args.length>=3){Player target=plugin.getServer().getPlayerExact(args[1]);if(target==null||!codes.castled.allium.naming.PlayerNameVisibility.canSeeTarget(actor,target)){send(actor,Messages.CHAT_PLAYER_NOT_FOUND);return;}String line=String.join(" ",Arrays.copyOfRange(args,2,args.length));UtilitySupport.onTarget(plugin,actor,target,()->{var d=permissions.resolve(target,line,commands,settings.permissionOverrides());send(actor,Messages.HIDE_PERMISSION,"{player}",target.getName(),"{command}",d.label(),"{source}",d.source().name(),"{permission}",Objects.toString(d.permission(),"native/unknown"),"{allowed}",Boolean.toString(d.allowed()));});return;}
            if(args[0].equals("fix")&&args.length==3){String label=HidePolicy.root(args[1]),permission=args[2];audit(actor,"permission-map");update(actor,c->{var rows=new ArrayList<Map<?,?>>(c.getMapList("permission-overrides"));rows.removeIf(r->label.equals(r.get("command")));rows.add(Map.of("command",label,"permission",permission));c.set("permission-overrides",rows);});return;}
            if(args[0].equals("unfix")&&args.length==2){String label=HidePolicy.root(args[1]);audit(actor,"permission-unmap");update(actor,c->{var rows=new ArrayList<Map<?,?>>(c.getMapList("permission-overrides"));rows.removeIf(r->label.equals(r.get("command")));c.set("permission-overrides",rows);});return;}
            if(args[0].equals("group")){groupAdmin(actor,Arrays.copyOfRange(args,1,args.length),usage);return;}
        }catch(IllegalArgumentException invalid){send(actor,Messages.HIDE_FAILED);return;}
        if(!closed)usage.accept(args.length>0&&Set.of("enabled","resolve","fix","unfix").contains(args[0])?"/allium hide "+args[0]:"/allium hide");
    }
    private void groupAdmin(CommandSender actor,String[] args,java.util.function.Consumer<String> usage){
        if(args.length<2){usage.accept("/allium hide group");return;}String name=args[0].toLowerCase(Locale.ROOT),action=args[1];
        if(!name.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("group name");
        if(action.equals("create")&&args.length==3&&Set.of("whitelist","blacklist").contains(args[2])){boolean white=args[2].equals("whitelist");audit(actor,"group-create");update(actor,c->{if(c.contains("groups."+name))throw new IllegalArgumentException("group exists");c.set("groups."+name+".whitelist",white);c.set("groups."+name+".commands",List.of());c.set("groups."+name+".tabcompletes",List.of("^"));});return;}
        if(!settings.policy().groupNames().contains(name))throw new IllegalArgumentException("unknown group");
        if(action.equals("whitelist")&&args.length==3&&Set.of("true","false").contains(args[2])){audit(actor,"group-mode");update(actor,c->c.set(groupPath(c,name)+".whitelist",Boolean.parseBoolean(args[2])));return;}
        if(action.equals("inherit")&&args.length==4&&Set.of("commands","tabcompletes").contains(args[2])){audit(actor,"group-inherit");update(actor,c->c.set(groupPath(c,name)+".inherits."+args[2],Arrays.stream(args[3].split(",")).filter(v->!v.isBlank()).toList()));return;}
        if(!Set.of("add","remove","check").contains(action)||args.length<3){usage.accept("/allium hide group");return;}
        int start=2;String flag="-tc";if(args[start].startsWith("-")){flag=args[start++];if(!Set.of("-c","-t","-tc","-ct").contains(flag))throw new IllegalArgumentException("scope");}
        if(start>=args.length)throw new IllegalArgumentException("rule missing");List<String> rules=Arrays.stream(String.join(" ",Arrays.copyOfRange(args,start,args.length)).split(",")).map(HidePolicy::normalize).toList();
        var scopes=flag.equals("-c")?List.of("commands"):flag.equals("-t")?List.of("tabcompletes"):List.of("commands","tabcompletes");
        if(action.equals("check")){var g=settings.policy().select(List.of(name)).get(0);for(String rule:rules)send(actor,Messages.HIDE_INFO,"{message}",name+" "+rule+": command="+g.allows(rule,false)+", tab="+g.allows(rule,true));return;}
        audit(actor,"group-"+action);update(actor,c->{for(String scope:scopes){String path=groupPath(c,name)+"."+scope;var values=new LinkedHashSet<>(c.getStringList(path));if(action.equals("add"))values.addAll(rules);else values.removeAll(rules);c.set(path,List.copyOf(values));}});
    }
    private static String groupPath(YamlConfiguration c,String name){var g=c.getConfigurationSection("groups");if(g==null)throw new IllegalArgumentException("missing groups");return "groups."+g.getKeys(false).stream().filter(v->v.equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IllegalArgumentException("missing group"));}
    private void audit(CommandSender actor,String action){plugin.logger().info("Hide settings action="+action+" actor="+(actor instanceof Player p?p.getUniqueId():"CONSOLE"));}
    public List<String> complete(CommandSender actor,String[] args){
        if(!actor.hasPermission(ADMIN))return List.of();String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);Collection<String> values=List.of();
        if(args.length<=1)values=List.of("status","reload","enabled","group","resolve","fix","unfix");
        else if(args[0].equals("group")){if(args.length==2)values=settings.policy().groupNames();else if(args.length==3)values=List.of("create","add","remove","check","whitelist","inherit");else if(args.length==4)values=List.of("-c","-t","-tc");}
        else if(args[0].equals("enabled"))values=List.of("true","false");
        else if(args[0].equals("resolve")&&args.length==2)values=plugin.getServer().getOnlinePlayers().stream().filter(p->codes.castled.allium.naming.PlayerNameVisibility.canSeeTarget(actor,p)).map(Player::getName).toList();
        else if(Set.of("fix","unfix").contains(args[0])&&args.length==2)values=commands.keySet();
        return values.stream().filter(v->v.startsWith(prefix)).sorted().limit(128).toList();
    }
    @Override public void close(){closed=true;timer.cancel();HandlerList.unregisterAll(this);HidePacketHooks currentPackets=packets;packets=null;if(currentPackets!=null)currentPackets.close();io.shutdown();viewers.clear();poll.clear();pollTracked.clear();pollInFlight.clear();refresh.clear();refreshQueued.clear();}
}
