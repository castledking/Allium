package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.economy.EconomyResult.*;
import codes.castled.allium.module.*;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceSessions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/** Async command admission, frozen identities, ordered policy writes and post-commit messages. */
public final class EconomyRuntime implements Listener, AutoCloseable {
    private final PluginStart plugin;
    private final EconomyEngine engine;
    private final Executor worker;
    private final Phaser accepted=new Phaser(1);
    private final Object admission=new Object();
    private volatile boolean closed,reloading;
    public EconomyRuntime(PluginStart plugin)throws SQLException {
        this.plugin=plugin;
        engine=new EconomyEngine(plugin.getDatabaseManager().connectionSource(),readSettings(plugin),this::permits,(c,id)->{
            boolean capture=false;
            if(codes.castled.allium.core.FaultInjection.armed("economy.capture.halt-before")>0||codes.castled.allium.core.FaultInjection.armed("economy.capture.halt-after")>0){
                try(var s=c.prepareStatement("SELECT kind FROM allium_economy_operations WHERE operation_id=?")){s.setString(1,id.toString());try(var r=s.executeQuery()){capture=r.next()&&(r.getString(1).equals("CAPTURE")||r.getString(1).equals("SPEND"));}}
            }
            if(capture&&codes.castled.allium.core.FaultInjection.trip("economy.capture.halt-before"))Runtime.getRuntime().halt(77);
            if(codes.castled.allium.core.FaultInjection.trip("economy.halt-before-commit"))Runtime.getRuntime().halt(77);
            try(var statement=c.createStatement()){statement.execute("COMMIT");}
            if(capture&&codes.castled.allium.core.FaultInjection.trip("economy.capture.halt-after"))Runtime.getRuntime().halt(78);
            if(codes.castled.allium.core.FaultInjection.trip("economy.halt-after-commit"))Runtime.getRuntime().halt(78);
            if(codes.castled.allium.core.FaultInjection.trip("economy.commit-unknown"))throw new SQLException("fault injection: committed acknowledgement discarded");
        },VaultMoney::validate);
        EconomyRecovery.sweep(plugin.getDatabaseManager().connectionSource(),engine.reservations(),
                codes.castled.allium.mail.PlayerDataFiles.directory(plugin.getServer()),
                id->plugin.getServer().getPlayer(id)!=null,message->plugin.logger().warning(message));
        worker=plugin.getScheduler().boundedPool("Allium-Economy",1,256);
        var preferences=preferences();if(preferences!=null)preferences.repository().coordinateWritesWith(engine.coordinator());
        var messaging=messaging();if(messaging!=null)messaging.ignores().repository().coordinateWritesWith(engine.coordinator());
    }
    public EconomyEngine engine(){return engine;}
    public EconomyReservations reservations(){return engine.reservations(this::synchronousWorkflow);}
    private Mutation synchronousWorkflow(EconomyRequest request,EconomyEngine.WorkflowTransition transition){
        if(!admit())return new Unavailable("economy admission is closed");
        try{return engine.execute(request,transition);}finally{accepted.arriveAndDeregister();}
    }
    public boolean ready(){return !closed&&engine.ready();}
    public Mutation synchronous(EconomyRequest request){
        if(!admit())return new Unavailable("economy admission is closed");
        try{return engine.execute(request);}finally{accepted.arriveAndDeregister();}
    }
    public Balance synchronousBalance(UUID id){
        if(!admit())return new Unavailable("economy admission is closed");
        try{return engine.balance(id);}finally{accepted.arriveAndDeregister();}
    }

    public static EconomySettings readSettings(PluginStart plugin){
        var c=plugin.getConfig();
        return new EconomySettings(decimal(c.get("economy.starting-balance","100.00")),decimal(c.get("economy.min-money","0.00")),
                decimal(c.get("economy.max-money","10000000000000.00")),decimal(c.get("economy.minimum-pay-amount","0.01")),
                c.getBoolean("economy.offline-pay-enabled",true),c.getBoolean("economy.pay-excludes-ignore-list",true),
                c.getBoolean("preferences.defaults.accept-payments",true));
    }
    private static Money decimal(Object value){if(!(value instanceof String text))throw new IllegalArgumentException("economy amounts must be quoted decimal text");return Money.parse(text,true);}
    private PreferencesModule preferences(){return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule p&&p.isEnabled()?p:null;}
    private MessagingModule messaging(){return plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m&&m.isEnabled()?m:null;}
    private boolean permits(Connection c,UUID sender,UUID recipient)throws SQLException {
        return EconomyPaymentPolicy.permits(c,sender,recipient,engine.settings());
    }
    public void enable(){
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(Player p:plugin.getServer().getOnlinePlayers())open(p.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void joined(PlayerJoinEvent e){open(e.getPlayer().getUniqueId());}
    private void open(UUID id){submit(null,()->{
        if(engine.balance(id) instanceof Missing){
            UUID operation=UUID.nameUUIDFromBytes(("Allium-OPEN:"+id).getBytes(StandardCharsets.US_ASCII));
            engine.execute(engine.request(operation,"OPEN",id.toString(),EconomyRequest.Kind.OPEN,"SYSTEM",id,null,engine.settings().starting()));
        }
    });}
    private boolean admit(){synchronized(admission){if(closed||reloading)return false;accepted.register();return true;}}
    private boolean submit(CommandSender sender,Runnable task){
        if(!admit()){if(sender!=null)tell(sender,"Economy is unavailable.");return false;}
        try{worker.execute(()->{try{task.run();}finally{accepted.arriveAndDeregister();}});return true;}
        catch(RejectedExecutionException error){accepted.arriveAndDeregister();if(sender!=null)tell(sender,"Economy is busy; try again.");return false;}
    }
    private void tell(CommandSender sender,String text){plugin.getScheduler().runOnServer(()->{if(!closed&&(!(sender instanceof Player p)||p.isOnline()))sender.sendMessage("[Allium] "+text);});}
    private static String refusal(){return "That player can't receive payments right now.";}
    public void dispatch(CommandSender sender,String action,String[] args){
        if(closed||reloading){tell(sender,"Economy is unavailable.");return;}
        if(!sender.hasPermission("allium."+action)&&!(action.equals("eco")&&(!(sender instanceof Player)||sender.hasPermission("allium.eco.admin")))){tell(sender,"You do not have permission.");return;}
        try{
            switch(action){
                case "pay" -> pay(sender,args);
                case "paytoggle" -> toggle(sender,args);
                case "balance" -> balance(sender,args);
                case "eco" -> admin(sender,args);
                case "baltop" -> top(sender,args);
                default -> throw new IllegalArgumentException("unknown economy command");
            }
        }catch(IllegalArgumentException|ArithmeticException error){tell(sender,"Invalid arguments or amount. Use plain decimal amounts with at most two nonzero decimal places.");}
    }
    private void resolve(CommandSender sender,String name,java.util.function.Consumer<UUID> continuation){
        if(name==null||!name.matches("[A-Za-z0-9_]{1,16}")){tell(sender,refusal());return;}
        Player online=plugin.getServer().getPlayerExact(name);
        if(online!=null&&!PlayerNameVisibility.canSee(sender,online)){tell(sender,refusal());return;}
        plugin.getPlayerNames().resolve(name).whenComplete((entry,error)->plugin.getScheduler().runOnServer(()->{
            if(closed||error!=null||entry.isEmpty()){tell(sender,refusal());return;}
            continuation.accept(entry.get().uuid());
        }));
    }
    private void pay(CommandSender sender,String[] args){
        if(!(sender instanceof Player actor)||args.length!=2)throw new IllegalArgumentException("/pay <player> <amount>");
        Money amount=Money.parse(args[1],false).positive();UUID source=actor.getUniqueId();String actorName=actor.getName();
        if(amount.compareTo(engine.settings().minimumPay())<0){tell(sender,"Amount is below the minimum payment.");return;}
        if(plugin.getModuleManager().getModule("moderation") instanceof ModerationModule moderation && moderation.isEnabled()
                && (moderation.moderation().isMuted(source)||!moderation.moderation().isMuteStateReady(source))){tell(sender,refusal());return;}
        resolve(sender,args[0],recipient->{
            Player target=plugin.getServer().getPlayer(recipient);
            if(!actor.isOnline()||plugin.getServer().getPlayer(source)!=actor||!actor.hasPermission("allium.pay")){return;}
            if(source.equals(recipient)||target!=null&&!PlayerNameVisibility.canSee(actor,target)
                    ||target==null&&(!engine.settings().offlinePay()||!actor.hasPermission("allium.pay.offline"))){tell(sender,refusal());return;}
            var prefs=preferences();
            if(target!=null&&prefs!=null&&prefs.acceptance(recipient,PreferenceKey.ACCEPT_PAYMENTS)!=PreferenceSessions.Acceptance.ACCEPTS){tell(sender,refusal());return;}
            var messaging=messaging();
            if(target!=null&&messaging!=null&&engine.settings().excludesIgnore()
                    &&messaging.ignores().ignores(recipient,source)!=codes.castled.allium.messaging.MessagePolicy.Ignore.NO){tell(sender,refusal());return;}
            var request=engine.request(UUID.randomUUID(),"PAY",UUID.randomUUID().toString(),EconomyRequest.Kind.PAY,source.toString(),source,recipient,amount);
            submit(sender,()->{Mutation result=engine.execute(request);
                if(result instanceof Committed){tell(sender,"Paid "+amount+" to "+args[0]+". Operation "+request.id());if(target!=null)tell(target,"Received "+amount+" from "+actorName+".");}
                else receipt(sender,result,true);
            });
        });
    }
    private void receipt(CommandSender sender,Mutation result,boolean generic){
        if(result instanceof Committed done)tell(sender,"Economy operation committed: "+done.operationId());
        else if(result instanceof Unknown unknown)tell(sender,"OUTCOME_UNKNOWN:"+unknown.operationId());
        else if(result instanceof Rejected rejected)tell(sender,generic&&rejected.reason().equals("REFUSED")?refusal():"Economy refused: "+rejected.reason());
        else tell(sender,"Economy storage is unavailable.");
    }
    private void balance(CommandSender sender,String[] args){
        if(args.length>1)throw new IllegalArgumentException("balance");
        if(args.length==0){if(!(sender instanceof Player p))throw new IllegalArgumentException("player required");show(sender,p.getUniqueId());}
        else{if(!sender.hasPermission("allium.balance.others")){tell(sender,"You do not have permission.");return;}resolve(sender,args[0],id->show(sender,id));}
    }
    private void show(CommandSender sender,UUID id){submit(sender,()->{
        Balance result=engine.balance(id);
        if(result instanceof Found found)tell(sender,"Balance: "+found.available());else tell(sender,result instanceof Missing?"No economy account exists.":"Economy storage is unavailable.");
    });}
    private void toggle(CommandSender sender,String[] args){
        if(args.length>2)throw new IllegalArgumentException("paytoggle");
        Boolean parsed=args.length==0?null:args[0].equalsIgnoreCase("on")?true:args[0].equalsIgnoreCase("off")?false:null;
        String targetName=args.length==2?args[1]:args.length==1&&parsed==null?args[0]:null;
        if(args.length==2&&parsed==null)throw new IllegalArgumentException("on/off");
        final Boolean next=parsed;
        java.util.function.Consumer<UUID> change=id->{
            var prefs=preferences();if(prefs==null){tell(sender,"Payment preferences are unavailable.");return;}
            var effective=prefs.effective(id);if(next==null&&effective.isEmpty()){tell(sender,"Payment preferences are unavailable.");return;}
            boolean accept=next!=null?next:!effective.orElseThrow().acceptPayments();
            prefs.sessions().change(id,PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS,accept)).whenComplete((stored,error)->{
                if(error!=null)tell(sender,"Payment preference could not be saved.");else tell(sender,accept?"Payments enabled.":"Payments disabled.");
            });
        };
        if(targetName!=null){if(!sender.hasPermission("allium.paytoggle.others")){tell(sender,"You do not have permission.");return;}resolve(sender,targetName,change);}
        else{if(!(sender instanceof Player p))throw new IllegalArgumentException("target required");change.accept(p.getUniqueId());}
    }
    private void admin(CommandSender sender,String[] args){
        if(args.length>0&&(args[0].equalsIgnoreCase("reservations")||args[0].equalsIgnoreCase("resolve"))){
            reservationAdmin(sender,args);return;
        }
        if(args.length<2||args.length>3)throw new IllegalArgumentException("/eco <open|give|take|set> <player> [amount]");
        EconomyRequest.Kind kind=switch(args[0].toLowerCase(Locale.ROOT)){case "open"->EconomyRequest.Kind.OPEN;case "give"->EconomyRequest.Kind.DEPOSIT;case "take"->EconomyRequest.Kind.WITHDRAW;case "set"->EconomyRequest.Kind.ADMIN_SET;default->throw new IllegalArgumentException("admin action");};
        if(kind==EconomyRequest.Kind.OPEN&&args.length!=2||kind!=EconomyRequest.Kind.OPEN&&args.length!=3)throw new IllegalArgumentException("amount");
        Money amount=kind==EconomyRequest.Kind.OPEN?engine.settings().starting():Money.parse(args[2],kind==EconomyRequest.Kind.ADMIN_SET);
        if(kind!=EconomyRequest.Kind.OPEN&&kind!=EconomyRequest.Kind.ADMIN_SET)amount.positive();
        String actor=sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";
        resolve(sender,args[1],id->{var request=engine.request(UUID.randomUUID(),"ADMIN",UUID.randomUUID().toString(),kind,actor,id,null,amount);submit(sender,()->receipt(sender,engine.execute(request),false));});
    }
    private void reservationAdmin(CommandSender sender,String[] args){
        if(sender instanceof Player&&!sender.hasPermission("allium.eco.admin")){tell(sender,"You do not have permission.");return;}
        if(args[0].equalsIgnoreCase("reservations")){
            if(args.length>2)throw new IllegalArgumentException("reservations [player]");
            java.util.function.Consumer<UUID> list=id->submit(sender,()->{try{
                var rows=reservations().list(id);if(rows.isEmpty())tell(sender,"No reservations.");
                for(var row:rows)tell(sender,row.kind()+" "+row.id()+" payer="+row.account()+" amount="+row.amount()+" state="+row.state());
            }catch(SQLException error){tell(sender,"Reservation storage unavailable.");}});
            if(args.length==2)resolve(sender,args[1],list);else list.accept(null);return;
        }
        if(args.length<5)throw new IllegalArgumentException("resolve <KIT|MAIL> <workflow UUID> <capture|release> <reason>");
        var kind=EconomyReservations.Kind.valueOf(args[1].toUpperCase(Locale.ROOT));var id=UUID.fromString(args[2]);
        boolean capture=args[3].equalsIgnoreCase("capture");if(!capture&&!args[3].equalsIgnoreCase("release"))throw new IllegalArgumentException("capture or release required");
        String reason=String.join(" ",Arrays.copyOfRange(args,4,args.length));String actor=sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";
        try{var row=reservations().find(kind,id);if(row.isPresent()&&plugin.getServer().getPlayer(row.get().account())!=null){tell(sender,"Disconnect the payer before resolving a reservation; preserve their saved inventory first.");return;}}
        catch(SQLException error){tell(sender,"Reservation storage unavailable.");return;}
        submit(sender,()->{try{receipt(sender,reservations().resolve(kind,id,capture,actor,reason),false);tell(sender,"Native marker and workflow retained; inventory reconciliation remains an operator task.");}
            catch(SQLException error){tell(sender,"Reservation resolution unavailable.");}});
    }
    private void top(CommandSender sender,String[] args){
        if(args.length!=0)throw new IllegalArgumentException("baltop");
        submit(sender,()->{try(var c=plugin.getDatabaseManager().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT account_uuid,balance_minor FROM allium_economy_accounts ORDER BY balance_minor DESC,account_uuid LIMIT 10")){
            var lines=new ArrayList<String>();while(r.next())lines.add(r.getString(1)+": "+new Money(r.getLong(2)));tell(sender,"Top balances: "+String.join(", ",lines));
        }catch(SQLException error){tell(sender,"Economy storage is unavailable.");}});
    }
    public boolean reload(){
        EconomySettings next;
        try{next=readSettings(plugin);}catch(RuntimeException error){plugin.logger().warning("Invalid economy settings: "+error.getMessage());return false;}
        reloading=true;
        try{
            // No accepted work is dropped; its commit finishes before the settings installation barrier.
            CompletableFuture<Void> barrier=new CompletableFuture<>();
            worker.execute(()->{try{engine.installSettings(next);barrier.complete(null);}catch(Exception error){barrier.completeExceptionally(error);}});
            barrier.get(10,TimeUnit.SECONDS);return true;
        }catch(Exception error){plugin.logger().warning("Economy reload refused; prior settings retained: "+error.getMessage());return false;}
        finally{reloading=false;}
    }
    @Override public void close(){
        int phase;
        synchronized(admission){if(closed)return;closed=true;phase=accepted.arriveAndDeregister();}
        HandlerList.unregisterAll(this);
        try{accepted.awaitAdvanceInterruptibly(phase,10,TimeUnit.SECONDS);}
        catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(TimeoutException error){plugin.logger().warning("Economy shutdown has unresolved accepted work; journal recovery remains authoritative.");}
        var prefs=preferences();if(prefs!=null)prefs.repository().coordinateWritesWith(null);
        var messages=messaging();if(messages!=null)messages.ignores().repository().coordinateWritesWith(null);
    }
}
