package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.economy.EconomyResult.*;
import codes.castled.allium.module.*;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.text.SpigotComponents;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/** Async command admission, frozen identities, ordered policy writes and post-commit messages. */
public final class EconomyRuntime implements Listener, AutoCloseable {
    private final PluginStart plugin;
    private final EconomyEngine engine;
    private final Executor worker;
    private final ChequeService cheques;
    private final TaxService taxes;
    private final Phaser accepted=new Phaser(1);
    private final Object admission=new Object();
    private volatile boolean closed,reloading;
    private volatile EconomyDisplay display;
    private record LogSettings(boolean enabled,boolean uuids,boolean vault){}
    private record PendingPay(UUID recipient,Money amount,long expiresAt){}
    private static final long PAY_CONFIRM_WINDOW_MILLIS=30_000L;
    private final Map<UUID,PendingPay> pendingPayments=new ConcurrentHashMap<>();
    private volatile LogSettings logging;
    private void readPresentation(){
        var config=plugin.getConfig();
        display=EconomyDisplay.read(config);
        logging=new LogSettings(config!=null&&config.getBoolean("economy-log-enabled"),
                config!=null&&config.getBoolean("economy-log-uuids"),
                config!=null&&config.getBoolean("economy-log-update-enabled"));
    }
    EconomyDisplay display(){return display;}
    public TaxSettings taxSettings(){return engine.settings().tax();}
    public ChequeService cheques(){return cheques;}
    public EconomyRuntime(PluginStart plugin)throws SQLException {
        this.plugin=plugin;
        readPresentation();
        EconomySettings initialSettings=readSettings(plugin);
        engine=new EconomyEngine(plugin.getDatabaseManager().connectionSource(),initialSettings,this::permits,(c,id)->{
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
        openSystemAccount(initialSettings.tax().accountUuid());
        EconomyRecovery.sweep(plugin.getDatabaseManager().connectionSource(),engine.reservations(),
                codes.castled.allium.mail.PlayerDataFiles.directory(plugin.getServer()),
                id->plugin.getServer().getPlayer(id)!=null,initialSettings.tax().accountUuid(),message->plugin.logger().warning(message));
        worker=plugin.getScheduler().boundedPool("Allium-Economy",1,256);
        var source=plugin.getDatabaseManager().connectionSource();
        ChequeService chequeService=null;
        try{chequeService=new ChequeService(plugin,this,new ChequeRepository(source),plugin.getScheduler().boundedPool("Allium-Cheques",1,128));}
        catch(IllegalStateException unavailable){plugin.logger().warning("Cheques are unavailable: "+unavailable.getMessage());}
        this.cheques=chequeService;
        this.taxes=new TaxService(plugin,this,new TaxHistoryRepository(source));
        var preferences=preferences();if(preferences!=null)preferences.repository().coordinateWritesWith(engine.coordinator());
        var messaging=messaging();if(messaging!=null)messaging.ignores().repository().coordinateWritesWith(engine.coordinator());
    }
    EconomyRuntime(PluginStart plugin,EconomyEngine engine,Executor worker){this.plugin=plugin;this.engine=engine;this.worker=worker;this.cheques=null;this.taxes=null;readPresentation();}
    public EconomyEngine engine(){return engine;}
    public EconomyReservations reservations(){return engine.reservations(this::synchronousWorkflow);}
    Mutation synchronousWorkflow(EconomyRequest request,EconomyEngine.WorkflowTransition transition){
        if(!admit())return new Unavailable("economy admission is closed");
        try{
            Mutation result=engine.execute(request,transition);
            if(result instanceof Committed done&&(request.kind()==EconomyRequest.Kind.CAPTURE
                    ||request.kind()==EconomyRequest.Kind.SPEND))log(request,done,"SYSTEM",null);
            return result;
        }finally{accepted.arriveAndDeregister();}
    }
    void logExternal(EconomyRequest request,Committed done,String actorName,String targetName){log(request,done,actorName,targetName);}
    boolean submitWork(CommandSender sender,Runnable task){return submit(sender,task);}
    void tellSender(CommandSender sender,Messages message,String... args){tell(sender,message,args);}
    void resolveAccount(CommandSender sender,String name,java.util.function.Consumer<UUID> continuation){resolve(sender,name,continuation);}
    private void openSystemAccount(UUID id){
        if(engine.balance(id) instanceof Missing){
            UUID operation=UUID.nameUUIDFromBytes(("Allium-OPEN_SYSTEM:"+id).getBytes(StandardCharsets.US_ASCII));
            engine.execute(engine.request(operation,"OPEN_SYSTEM",id.toString(),EconomyRequest.Kind.OPEN_SYSTEM,"SYSTEM",id,null,new Money(0)));
        }
    }
    public boolean ready(){return !closed&&engine.ready();}
    public Mutation synchronous(EconomyRequest request){
        if(!admit())return new Unavailable("economy admission is closed");
        try{return engine.execute(request);}finally{accepted.arriveAndDeregister();}
    }
    Mutation vaultMutation(EconomyRequest request){
        Mutation result=synchronous(request);
        if(result instanceof Committed done)log(request,done,null,null);
        return result;
    }
    /** Records a committed operation started outside this runtime (such as /sell) in trade.log. */
    public void logTrade(EconomyRequest request,Committed done,String actorName,String targetName){log(request,done,actorName,targetName);}
    private void log(EconomyRequest request,Committed done,String actorName,String targetName){
        LogSettings settings=logging;
        if(done.replay()||!(request.origin().equals("VAULT")?settings.vault():settings.enabled()))return;
        UUID target=request.recipient()==null?request.source():request.recipient();
        String actor=actorName!=null&&actorName.equals("SYSTEM")?"SYSTEM":settings.uuids()||actorName==null?request.actor():actorName;
        String subject=settings.uuids()?target.toString():targetName;
        if(subject==null&&plugin.getPlayerNames()!=null)
            subject=plugin.getPlayerNames().cache().byUuid(target).map(entry->entry.name()).orElse(null);
        if(subject==null)subject=target.toString();
        String line=java.util.stream.Stream.of(java.time.Instant.now().toString(),request.origin(),
                request.kind().name(),actor,subject,
                request.amount().toString(),done.operationId().toString())
                .map(value -> "\"" + value.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","))+System.lineSeparator();
        if(!submit(null,()->{
            try{java.nio.file.Files.writeString(plugin.getDataFolder().toPath().resolve("trade.log"),line,
                    java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}
            catch(java.io.IOException error){plugin.logger().warning("Could not append trade.log: "+error.getMessage());}
        }))plugin.logger().warning("Could not queue trade.log entry for operation "+done.operationId());
    }
    public Balance synchronousBalance(UUID id){
        if(!admit())return new Unavailable("economy admission is closed");
        try{return engine.balance(id);}finally{accepted.arriveAndDeregister();}
    }

    public static EconomySettings readSettings(PluginStart plugin){
        var c=plugin.getConfig();
        TaxSettings tax=readTaxSettings(plugin);
        return new EconomySettings(decimal(plugin,"economy.starting-balance","100.00"),decimal(plugin,"economy.min-money","0.00"),
                decimal(plugin,"economy.max-money","10000000000000.00"),decimal(plugin,"economy.minimum-pay-amount","0.01"),
                c.getBoolean("economy.offline-pay-enabled",true),c.getBoolean("economy.pay-excludes-ignore-list",true),
                c.getBoolean("preferences.defaults.accept-payments",true),tax);
    }
    public static TaxSettings readTaxSettings(PluginStart plugin){
        var c=plugin.getConfig();
        String base=c.isConfigurationSection("economy.tax")?"economy.tax":c.isConfigurationSection("tax")?"tax":"economy.tax";
        boolean enabled=c.getBoolean(base+".enabled",true);
        java.math.BigDecimal percent=percent(c.get(base+".percent","5"),base+".percent");
        String accountName=c.getString(base+".account-name","Tax");
        boolean accountInBaltop=c.getBoolean(base+".account-in-baltop",true);
        boolean accountPayable=c.getBoolean(base+".account-payable",false);
        boolean applyPay=c.getBoolean(base+".apply-to.pay",true);
        boolean applyCheques=c.getBoolean(base+".apply-to.cheques",false);
        java.math.RoundingMode roundMode=TaxSettings.parseRoundMode(c.getString(base+".round-mode","nearest"));
        Money minimumTax=decimal(plugin,base+".minimum-tax","0.00");
        return new TaxSettings(enabled,percent,accountName,applyPay,applyCheques,roundMode,minimumTax,accountInBaltop,accountPayable);
    }
    private static java.math.BigDecimal percent(Object value,String path){
        if(value==null)return java.math.BigDecimal.valueOf(5);
        if(value instanceof String text){
            String t=text.trim();
            if(!t.matches("[0-9]+(\\.[0-9]+)?"))throw new IllegalArgumentException(path+" must be a plain percentage 0..100");
            return new java.math.BigDecimal(t);
        }
        if(value instanceof Integer||value instanceof Long||value instanceof Double||value instanceof Float||value instanceof java.math.BigDecimal){
            return new java.math.BigDecimal(value.toString());
        }
        throw new IllegalArgumentException(path+" must be a percentage between 0 and 100");
    }
    /**
     * Amounts are decimal text ({@code '100.00'}). A plain YAML number (legacy configs wrote
     * {@code starting-balance: 100.0}) is read by its written digits instead of disabling the whole
     * economy, with a warning to quote it.
     */
    private static Money decimal(PluginStart plugin,String path,String fallback){
        Object value=plugin.getConfig().get(path,fallback);
        if(value instanceof String text)return Money.parse(text.trim(),true);
        if(value instanceof Integer||value instanceof Long||value instanceof Double||value instanceof Float||value instanceof java.math.BigDecimal){
            String text=new java.math.BigDecimal(value.toString()).toPlainString();
            plugin.logger().warning(path+" is a number ("+value+"); read as "+text+". Quote it ('"+text+"') so it stays exact.");
            return Money.parse(text,true);
        }
        throw new IllegalArgumentException(path+" must be a decimal amount such as '100.00'");
    }
    private PreferencesModule preferences(){return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule p&&p.isEnabled()?p:null;}
    private MessagingModule messaging(){return plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m&&m.isEnabled()?m:null;}
    private boolean permits(Connection c,UUID sender,UUID recipient)throws SQLException {
        return EconomyPaymentPolicy.permits(c,sender,recipient,engine.settings());
    }
    public void enable(){
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        if(cheques!=null)cheques.enable();
        for(Player p:plugin.getServer().getOnlinePlayers())open(p.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void joined(PlayerJoinEvent e){open(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(org.bukkit.event.player.PlayerQuitEvent e){pendingPayments.remove(e.getPlayer().getUniqueId());}
    private void open(UUID id){submit(null,()->{
        if(engine.balance(id) instanceof Missing){
            UUID operation=UUID.nameUUIDFromBytes(("Allium-OPEN:"+id).getBytes(StandardCharsets.US_ASCII));
            engine.execute(engine.request(operation,"OPEN",id.toString(),EconomyRequest.Kind.OPEN,"SYSTEM",id,null,engine.settings().starting()));
        }
    });}
    private boolean admit(){synchronized(admission){if(closed||reloading)return false;accepted.register();return true;}}
    private boolean submit(CommandSender sender,Runnable task){
        if(!admit()){if(sender!=null)tell(sender,Messages.ECO_UNAVAILABLE);return false;}
        try{worker.execute(()->{try{task.run();}finally{accepted.arriveAndDeregister();}});return true;}
        catch(RejectedExecutionException error){accepted.arriveAndDeregister();if(sender!=null)tell(sender,Messages.ECO_BUSY);return false;}
    }
    private void tell(CommandSender sender,Messages message,String... args){plugin.getScheduler().runOnServer(()->{if(!closed&&(!(sender instanceof Player p)||p.isOnline()))plugin.getMessageManager().send(sender,message,args);});}
    private static Messages refusal(){return Messages.ECO_REFUSED;}
    public void dispatch(CommandSender sender,String action,String[] args){
        if(closed||reloading){tell(sender,Messages.ECO_UNAVAILABLE);return;}
        if(!sender.hasPermission("allium."+action)&&!(action.equals("eco")&&(!(sender instanceof Player)||sender.hasPermission("allium.eco.admin")))){tell(sender,Messages.NO_PERMISSION);return;}
        try{
            switch(action){
                case "pay" -> pay(sender,args);
                case "paytoggle" -> toggle(sender,args);
                case "payconfirmtoggle" -> toggleConfirm(sender,args);
                case "balance" -> balance(sender,args);
                case "eco" -> admin(sender,args);
                case "baltop" -> top(sender,args);
                case "cheque" -> {if(cheques==null)tell(sender,Messages.ECO_UNAVAILABLE);else cheques.dispatch(sender,args);}
                case "tax" -> {if(taxes==null)tell(sender,Messages.ECO_UNAVAILABLE);else taxes.dispatch(sender,args);}
                default -> throw new IllegalArgumentException("unknown economy command");
            }
        }catch(IllegalArgumentException|ArithmeticException error){tell(sender,Messages.ECO_INVALID);}
    }
    private void resolve(CommandSender sender,String name,java.util.function.Consumer<UUID> continuation){
        if(!codes.castled.allium.naming.NameCache.isValidName(name)){tell(sender,refusal());return;}
        TaxSettings tax=taxSettings();
        if(name.equalsIgnoreCase(tax.accountName())){continuation.accept(tax.accountUuid());return;}
        Player online=plugin.getServer().getPlayerExact(name);
        if(online!=null&&!PlayerNameVisibility.canSee(sender,online)){tell(sender,refusal());return;}
        plugin.getPlayerNames().resolve(name).whenComplete((entry,error)->plugin.getScheduler().runOnServer(()->{
            if(closed||error!=null||entry.isEmpty()){tell(sender,refusal());return;}
            continuation.accept(entry.get().uuid());
        }));
    }
    private void pay(CommandSender sender,String[] args){
        if(!(sender instanceof Player actor)||args.length!=2)throw new IllegalArgumentException("/pay <player> <amount>");
        Money amount=Money.parseCommand(args[1],false).positive();UUID source=actor.getUniqueId();String actorName=actor.getName();
        if(amount.compareTo(engine.settings().minimumPay())<0){tell(sender,Messages.ECO_MINIMUM);return;}
        if(plugin.getModuleManager().getModule("moderation") instanceof ModerationModule moderation && moderation.isEnabled()
                && (moderation.moderation().isMuted(source)||!moderation.moderation().isMuteStateReady(source))){tell(sender,refusal());return;}
        resolve(sender,args[0],recipient->{
            Player target=plugin.getServer().getPlayer(recipient);
            if(!actor.isOnline()||plugin.getServer().getPlayer(source)!=actor||!actor.hasPermission("allium.pay")){return;}
            TaxSettings tax=taxSettings();
            boolean isTaxAccount=recipient.equals(tax.accountUuid());
            if(source.equals(recipient)){tell(sender,Messages.ECO_PAY_SELF);return;}
            if(isTaxAccount&&!tax.accountPayable()){tell(sender,refusal());return;}
            if(target!=null&&!PlayerNameVisibility.canSee(actor,target)
                    ||!isTaxAccount&&target==null&&(!engine.settings().offlinePay()||!actor.hasPermission("allium.pay.offline"))){tell(sender,refusal());return;}
            var prefs=preferences();
            if(target!=null&&prefs!=null&&prefs.acceptance(recipient,PreferenceKey.ACCEPT_PAYMENTS)!=PreferenceSessions.Acceptance.ACCEPTS){tell(sender,refusal());return;}
            var messaging=messaging();
            if(target!=null&&messaging!=null&&engine.settings().excludesIgnore()
                    &&messaging.ignores().ignores(recipient,source)!=codes.castled.allium.messaging.MessagePolicy.Ignore.NO){tell(sender,refusal());return;}
            boolean taxable=tax.appliesToPay()&&!isTaxAccount&&!actor.hasPermission("allium.tax.exempt");
            Money taxAmount=taxable?tax.calculateTax(amount):new Money(0);
            Money netAmount=amount.minus(taxAmount);
            if(taxAmount.minor()>0&&netAmount.minor()<=0){tell(sender,Messages.ECO_MINIMUM);return;}
            String recipientLabel=isTaxAccount?tax.accountName():(target!=null?target.getName():args[0]);
            if(prefs!=null){
                var senderEffective=prefs.effective(source);
                if(senderEffective.isPresent()&&senderEffective.get().payConfirm()){
                    long now=System.currentTimeMillis();
                    PendingPay pending=pendingPayments.get(source);
                    if(pending!=null&&pending.expiresAt()>now&&pending.recipient().equals(recipient)&&pending.amount().equals(amount)){
                        pendingPayments.remove(source,pending);
                    }else{
                        pendingPayments.put(source,new PendingPay(recipient,amount,now+PAY_CONFIRM_WINDOW_MILLIS));
                        tell(sender,Messages.ECO_PAY_CONFIRM,"{amount}",display.format(amount),"{player}",recipientLabel,"{raw_amount}",args[1]);
                        return;
                    }
                }
            }
            UUID taxAccount=taxAmount.minor()>0?tax.accountUuid():null;
            var request=engine.request(UUID.randomUUID(),"PAY",UUID.randomUUID().toString(),EconomyRequest.Kind.PAY,source.toString(),source,recipient,amount,taxAccount,taxAmount);
            submit(sender,()->{Mutation result=engine.execute(request);
                if(result instanceof Committed done){
                    log(request,done,actorName,recipientLabel);
                    if(taxAmount.minor()>0){
                        tell(sender,Messages.ECO_PAYMENT_SENT_TAXED,"{net}",display.format(netAmount),"{player}",recipientLabel,
                                "{tax}",display.format(taxAmount),"{account}",tax.accountName(),"{operation}",request.id().toString());
                        if(target!=null)tell(target,Messages.ECO_PAYMENT_RECEIVED,"{amount}",display.format(netAmount),"{player}",actorName);
                    }else{
                        tell(sender,Messages.ECO_PAYMENT_SENT,"{amount}",display.format(amount),"{player}",recipientLabel,"{operation}",request.id().toString());
                        if(target!=null)tell(target,Messages.ECO_PAYMENT_RECEIVED,"{amount}",display.format(amount),"{player}",actorName);
                    }
                }
                else receipt(sender,result,true);
            });
        });
    }
    private void receipt(CommandSender sender,Mutation result,boolean generic){
        if(result instanceof Committed done)tell(sender,Messages.ECO_COMMITTED,"{operation}",done.operationId().toString());
        else if(result instanceof Unknown unknown)tell(sender,Messages.ECO_UNKNOWN,"{operation}",unknown.operationId().toString());
        else if(result instanceof Rejected rejected){
            Messages msg=refusalMessage(rejected.reason());
            if(msg==Messages.ECO_OPERATION_REFUSED)tell(sender,msg,"{reason}",rejected.reason());
            else tell(sender,msg);
        }
        else tell(sender,Messages.ECO_STORAGE);
    }
    /** Maps an internal economy engine rejection code to its localized lang message. */
    public static Messages refusalMessage(String reason){
        if(reason==null)return Messages.ECO_OPERATION_REFUSED;
        return switch(reason){
            case "INSUFFICIENT_FUNDS" -> Messages.ECO_REFUSED_INSUFFICIENT_FUNDS;
            case "CAP" -> Messages.ECO_REFUSED_BALANCE_CAP;
            case "MISSING_ACCOUNT" -> Messages.ECO_REFUSED_MISSING_ACCOUNT;
            case "REFUSED" -> Messages.ECO_REFUSED;
            case "SETTINGS_CHANGED" -> Messages.ECO_REFUSED_SETTINGS_CHANGED;
            case "IDEMPOTENCY_CONFLICT" -> Messages.ECO_REFUSED_DUPLICATE_REQUEST;
            case "WORKFLOW_REQUIRED","WORKFLOW_REFUSED","WORKFLOW_EXISTS",
                    "WORKFLOW_CONFLICT","WORKFLOW_TERMINAL","MISSING_RESERVATION","NOT_HELD" ->
                    Messages.ECO_REFUSED_TRANSACTION_BUSY;
            default -> Messages.ECO_OPERATION_REFUSED;
        };
    }
    /** Sends the localized message for an economy rejection code on the current (server) thread. */
    public static void sendRefusal(PluginStart plugin,CommandSender sender,String reason){
        Messages msg=refusalMessage(reason);
        if(msg==Messages.ECO_OPERATION_REFUSED)plugin.getMessageManager().send(sender,msg,"{reason}",reason==null?"unknown":reason);
        else plugin.getMessageManager().send(sender,msg);
    }
    private void balance(CommandSender sender,String[] args){
        if(args.length>1)throw new IllegalArgumentException("balance");
        if(args.length==0){if(!(sender instanceof Player p))throw new IllegalArgumentException("player required");show(sender,p.getUniqueId());}
        else{
            TaxSettings tax=taxSettings();
            if(args[0].equalsIgnoreCase(tax.accountName())){
                if(!sender.hasPermission("allium.tax.balance")){tell(sender,Messages.NO_PERMISSION);return;}
                show(sender,tax.accountUuid());
                return;
            }
            if(!sender.hasPermission("allium.balance.others")){tell(sender,Messages.NO_PERMISSION);return;}
            resolve(sender,args[0],id->show(sender,id));
        }
    }
    private void show(CommandSender sender,UUID id){submit(sender,()->{
        Balance result=engine.balance(id);
        if(result instanceof Found found)tell(sender,Messages.BALANCE,"{amount}",display.format(found.available()));else tell(sender,result instanceof Missing?Messages.ECO_MISSING:Messages.ECO_STORAGE);
    });}
    private void toggle(CommandSender sender,String[] args){
        if(args.length>2)throw new IllegalArgumentException("paytoggle");
        Boolean parsed=args.length==0?null:args[0].equalsIgnoreCase("on")?true:args[0].equalsIgnoreCase("off")?false:null;
        String targetName=args.length==2?args[1]:args.length==1&&parsed==null?args[0]:null;
        if(args.length==2&&parsed==null)throw new IllegalArgumentException("on/off");
        final Boolean next=parsed;
        java.util.function.Consumer<UUID> change=id->{
            var prefs=preferences();if(prefs==null){tell(sender,Messages.ECO_PREFS_UNAVAILABLE);return;}
            var effective=prefs.effective(id);if(next==null&&effective.isEmpty()){tell(sender,Messages.ECO_PREFS_UNAVAILABLE);return;}
            boolean accept=next!=null?next:!effective.orElseThrow().acceptPayments();
            prefs.sessions().change(id,PreferenceChange.set(PreferenceKey.ACCEPT_PAYMENTS,accept)).whenComplete((stored,error)->{
                if(error!=null)tell(sender,Messages.ECO_PREFS_FAILED);else tell(sender,accept?Messages.ECO_PAY_ON:Messages.ECO_PAY_OFF);
            });
        };
        if(targetName!=null){if(!sender.hasPermission("allium.paytoggle.others")){tell(sender,Messages.NO_PERMISSION);return;}resolve(sender,targetName,change);}
        else{if(!(sender instanceof Player p))throw new IllegalArgumentException("target required");change.accept(p.getUniqueId());}
    }
    private void toggleConfirm(CommandSender sender,String[] args){
        if(args.length>2)throw new IllegalArgumentException("payconfirmtoggle");
        Boolean parsed=args.length==0?null:args[0].equalsIgnoreCase("on")?true:args[0].equalsIgnoreCase("off")?false:null;
        String targetName=args.length==2?args[1]:args.length==1&&parsed==null?args[0]:null;
        if(args.length==2&&parsed==null)throw new IllegalArgumentException("on/off");
        final Boolean next=parsed;
        java.util.function.Consumer<UUID> change=id->{
            var prefs=preferences();if(prefs==null){tell(sender,Messages.ECO_PREFS_UNAVAILABLE);return;}
            var effective=prefs.effective(id);if(next==null&&effective.isEmpty()){tell(sender,Messages.ECO_PREFS_UNAVAILABLE);return;}
            boolean confirm=next!=null?next:!effective.orElseThrow().payConfirm();
            if(!confirm)pendingPayments.remove(id);
            prefs.sessions().change(id,PreferenceChange.set(PreferenceKey.PAY_CONFIRM,confirm)).whenComplete((stored,error)->{
                if(error!=null)tell(sender,Messages.ECO_PREFS_FAILED);
                else tell(sender,confirm?Messages.ECO_PAY_CONFIRM_ON:Messages.ECO_PAY_CONFIRM_OFF);
            });
        };
        if(targetName!=null){if(!sender.hasPermission("allium.payconfirmtoggle.others")){tell(sender,Messages.NO_PERMISSION);return;}resolve(sender,targetName,change);}
        else{if(!(sender instanceof Player p))throw new IllegalArgumentException("target required");change.accept(p.getUniqueId());}
    }
    private void admin(CommandSender sender,String[] args){
        if(args.length>0&&(args[0].equalsIgnoreCase("reservations")||args[0].equalsIgnoreCase("resolve"))){
            reservationAdmin(sender,args);return;
        }
        if(args.length<2||args.length>3)throw new IllegalArgumentException("/eco <open|give|take|set> <player> [amount]");
        EconomyRequest.Kind kind=switch(args[0].toLowerCase(Locale.ROOT)){case "open"->EconomyRequest.Kind.OPEN;case "give"->EconomyRequest.Kind.DEPOSIT;case "take"->EconomyRequest.Kind.WITHDRAW;case "set"->EconomyRequest.Kind.ADMIN_SET;default->throw new IllegalArgumentException("admin action");};
        if(kind==EconomyRequest.Kind.OPEN&&args.length!=2||kind!=EconomyRequest.Kind.OPEN&&args.length!=3)throw new IllegalArgumentException("amount");
        Money amount=kind==EconomyRequest.Kind.OPEN?engine.settings().starting():Money.parseCommand(args[2],kind==EconomyRequest.Kind.ADMIN_SET);
        if(kind!=EconomyRequest.Kind.OPEN&&kind!=EconomyRequest.Kind.ADMIN_SET)amount.positive();
        String actor=sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";
        String actorName=sender.getName();
        resolve(sender,args[1],id->{
            if(sender instanceof Player p&&(!p.isOnline()||plugin.getServer().getPlayer(p.getUniqueId())!=p
                    ||!p.hasPermission("allium.eco")&&!p.hasPermission("allium.eco.admin")))return;
            Player target=plugin.getServer().getPlayer(id);
            String targetName=target==null?args[1]:target.getName();
            var request=engine.request(UUID.randomUUID(),"ADMIN",UUID.randomUUID().toString(),kind,actor,id,null,amount);
            submit(sender,()->{
                Mutation result=engine.execute(request);
                if(!(result instanceof Committed done)){receipt(sender,result,false);return;}
                log(request,done,actorName,targetName);
                Messages adminMessage=switch(kind){
                    case DEPOSIT->Messages.ECO_ADMIN_GIVE;
                    case WITHDRAW->Messages.ECO_ADMIN_TAKE;
                    default->Messages.ECO_ADMIN_SET;
                };
                tell(sender,adminMessage,"{player}",targetName,"{amount}",display.format(amount),
                        "{balance}",display.format(done.source().available()),"{operation}",done.operationId().toString());
                if(target!=null&&target!=sender)tell(target,switch(kind){
                    case DEPOSIT->Messages.ECO_ADMIN_RECEIVED;
                    case WITHDRAW->Messages.ECO_ADMIN_TAKEN;
                    default->Messages.ECO_ADMIN_UPDATED;
                },"{amount}",display.format(amount),"{balance}",display.format(done.source().available()));
            });
        });
    }
    private void reservationAdmin(CommandSender sender,String[] args){
        if(sender instanceof Player&&!sender.hasPermission("allium.eco.admin")){tell(sender,Messages.NO_PERMISSION);return;}
        if(args[0].equalsIgnoreCase("reservations")){
            if(args.length>2)throw new IllegalArgumentException("reservations [player]");
            java.util.function.Consumer<UUID> list=id->submit(sender,()->{try{
                var rows=reservations().list(id);if(rows.isEmpty())tell(sender,Messages.ECO_RES_NONE);
                for(var row:rows)tell(sender,Messages.ECO_RES_ROW,"{kind}",row.kind().toString(),"{id}",row.id().toString(),"{payer}",row.account().toString(),"{amount}",display.format(row.amount()),"{state}",row.state().toString());
            }catch(SQLException error){tell(sender,Messages.ECO_RES_STORAGE);}});
            if(args.length==2)resolve(sender,args[1],list);else list.accept(null);return;
        }
        if(args.length<5)throw new IllegalArgumentException("resolve <KIT|MAIL|REPAIR|CHEQUE> <workflow UUID> <capture|release> <reason>");
        var kind=EconomyReservations.Kind.valueOf(args[1].toUpperCase(Locale.ROOT));var id=UUID.fromString(args[2]);
        boolean capture=args[3].equalsIgnoreCase("capture");if(!capture&&!args[3].equalsIgnoreCase("release"))throw new IllegalArgumentException("capture or release required");
        String reason=String.join(" ",Arrays.copyOfRange(args,4,args.length));String actor=sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";
        try{var row=reservations().find(kind,id);if(row.isPresent()&&plugin.getServer().getPlayer(row.get().account())!=null){tell(sender,Messages.ECO_RES_DISCONNECT);return;}}
        catch(SQLException error){tell(sender,Messages.ECO_RES_STORAGE);return;}
        submit(sender,()->{try{receipt(sender,reservations().resolve(kind,id,capture,actor,reason),false);tell(sender,Messages.ECO_RES_RETAINED);}
            catch(SQLException error){tell(sender,Messages.ECO_RES_FAILED);}});
    }
    String resolveDisplayName(UUID id){
        return resolveDisplayName(null,id);
    }
    String resolveDisplayName(java.sql.Connection existing,UUID id){
        TaxSettings tax=taxSettings();
        if(id.equals(tax.accountUuid()))return tax.accountName();
        if(plugin.getPlayerNames()!=null){
            var cached=plugin.getPlayerNames().cache().byUuid(id);
            if(cached.isPresent())return cached.get().name();
        }
        if(existing!=null){
            try(var s=existing.prepareStatement("SELECT name FROM allium_player_names WHERE uuid=?")){
                s.setString(1,id.toString());
                try(var r=s.executeQuery()){if(r.next())return r.getString(1);}
            }catch(SQLException ignored){}
            return id.toString();
        }
        try(var c=plugin.getDatabaseManager().getConnection();var s=c.prepareStatement("SELECT name FROM allium_player_names WHERE uuid=?")){
            s.setString(1,id.toString());
            try(var r=s.executeQuery()){if(r.next())return r.getString(1);}
        }catch(SQLException ignored){}
        return id.toString();
    }
    /** What PlaceholderAPI reads: online players' available balances and the top accounts, refreshed off the server thread. */
    public record PlaceholderSnapshot(Map<UUID,BigDecimal> balances,List<TopEntry> top){
        public static final PlaceholderSnapshot EMPTY=new PlaceholderSnapshot(Map.of(),List.of());
    }
    public record TopEntry(UUID id,String name,BigDecimal balance){}
    public static final int PLACEHOLDER_TOP=100;
    private volatile PlaceholderSnapshot placeholders=PlaceholderSnapshot.EMPTY;
    public PlaceholderSnapshot placeholders(){return placeholders;}
    /** Reads the balances and the top {@value #PLACEHOLDER_TOP} on the economy worker; skipped while it is closed or full. */
    public void refreshPlaceholders(Collection<UUID> online){
        List<UUID> ids=List.copyOf(online);
        submit(null,()->{
            Map<UUID,BigDecimal> balances=new HashMap<>();
            for(UUID id:ids)if(engine.balance(id) instanceof Found found)balances.put(id,found.available().decimal());
            List<TopEntry> top=new ArrayList<>();
            TaxSettings tax=taxSettings();
            boolean excludeTax=!tax.accountInBaltop();
            String sql=excludeTax
                    ?"SELECT account_uuid,balance_minor FROM allium_economy_accounts WHERE account_uuid<>? ORDER BY balance_minor DESC,account_uuid LIMIT ?"
                    :"SELECT account_uuid,balance_minor FROM allium_economy_accounts ORDER BY balance_minor DESC,account_uuid LIMIT ?";
            try(var c=plugin.getDatabaseManager().getConnection();
                var ps=c.prepareStatement(sql)){
                if(excludeTax){ps.setString(1,tax.accountUuid().toString());ps.setInt(2,PLACEHOLDER_TOP);}
                else{ps.setInt(1,PLACEHOLDER_TOP);}
                try(var r=ps.executeQuery()){
                    while(r.next()){UUID id=UUID.fromString(r.getString(1));top.add(new TopEntry(id,resolveDisplayName(c,id),new Money(r.getLong(2)).decimal()));}
                }
            }catch(SQLException|RuntimeException error){top=placeholders.top();}
            placeholders=new PlaceholderSnapshot(Map.copyOf(balances),List.copyOf(top));
        });
    }
    /** How /balance shows an amount ({@code $1,234.50}). */
    public String formatAmount(BigDecimal amount){return display.format(amount);}
    private record TopRow(int rank,String name,String balance){}
    private static final int BALTOP_PAGE_SIZE=8;
    private void top(CommandSender sender,String[] args){
        if(args.length>1)throw new IllegalArgumentException("/baltop [page]");
        int requestedPage=1;
        if(args.length==1){
            try{requestedPage=Integer.parseInt(args[0]);}
            catch(NumberFormatException invalid){throw new IllegalArgumentException("/baltop [page]");}
            if(requestedPage<1)throw new IllegalArgumentException("/baltop [page]");
        }
        final int targetPage=requestedPage;
        submit(sender,()->{
            try(var c=plugin.getDatabaseManager().getConnection()){
                TaxSettings tax=taxSettings();
                boolean excludeTax=!tax.accountInBaltop();
                int totalAccounts=0;
                BigDecimal totalMinor=BigDecimal.ZERO;
                String countSql=excludeTax
                        ?"SELECT COUNT(*),COALESCE(SUM(balance_minor),0) FROM allium_economy_accounts WHERE account_uuid<>?"
                        :"SELECT COUNT(*),COALESCE(SUM(balance_minor),0) FROM allium_economy_accounts";
                try(var ps=c.prepareStatement(countSql)){
                    if(excludeTax)ps.setString(1,tax.accountUuid().toString());
                    try(var r=ps.executeQuery()){
                        if(r.next()){
                            totalAccounts=r.getInt(1);
                            BigDecimal sum=r.getBigDecimal(2);
                            if(sum!=null)totalMinor=sum;
                        }
                    }
                }
                int pages=Math.max(1,(totalAccounts+BALTOP_PAGE_SIZE-1)/BALTOP_PAGE_SIZE);
                int page=Math.max(1,Math.min(targetPage,pages));
                int offset=(page-1)*BALTOP_PAGE_SIZE;
                var rows=new ArrayList<TopRow>();
                String listSql=excludeTax
                        ?"SELECT account_uuid,balance_minor FROM allium_economy_accounts WHERE account_uuid<>? ORDER BY balance_minor DESC,account_uuid LIMIT ? OFFSET ?"
                        :"SELECT account_uuid,balance_minor FROM allium_economy_accounts ORDER BY balance_minor DESC,account_uuid LIMIT ? OFFSET ?";
                try(var ps=c.prepareStatement(listSql)){
                    if(excludeTax){
                        ps.setString(1,tax.accountUuid().toString());
                        ps.setInt(2,BALTOP_PAGE_SIZE);
                        ps.setInt(3,offset);
                    }else{
                        ps.setInt(1,BALTOP_PAGE_SIZE);
                        ps.setInt(2,offset);
                    }
                    try(var r=ps.executeQuery()){
                        int rank=offset+1;
                        while(r.next()){
                            UUID id=UUID.fromString(r.getString(1));
                            rows.add(new TopRow(rank++,resolveDisplayName(c,id),display.format(new Money(r.getLong(2)))));
                        }
                    }
                }
                String totalFormatted=display.format(totalMinor.movePointLeft(2));
                plugin.getScheduler().runOnServer(()->{
                    if(sender instanceof Player p&&!p.isOnline())return;
                    var messages=plugin.getMessageManager();
                    messages.send(sender,Messages.ECO_TOP,"{page}",Integer.toString(page),"{pages}",Integer.toString(pages));
                    messages.send(sender,Messages.ECO_TOP_TOTAL,"{total}",totalFormatted);
                    sender.sendMessage("");
                    if(rows.isEmpty()){
                        messages.send(sender,Messages.ECO_TOP_EMPTY);
                    }else{
                        for(TopRow row:rows){
                            messages.send(sender,Messages.ECO_TOP_ENTRY,
                                    "{rank}",Integer.toString(row.rank()),
                                    "{player}",row.name(),
                                    "{balance}",row.balance());
                        }
                    }
                    if(page>1||page<pages){
                        sender.sendMessage("");
                        sendBaltopNav(sender,page,pages);
                    }
                });
            }catch(SQLException error){tell(sender,Messages.ECO_STORAGE);}
        });
    }
    private void sendBaltopNav(CommandSender sender,int page,int pages){
        var messages=plugin.getMessageManager();
        String prevText=page>1?Objects.toString(messages.resolve(Messages.ECO_TOP_PREVIOUS),""):"";
        String nextText=page<pages?Objects.toString(messages.resolve(Messages.ECO_TOP_NEXT),""):"";
        if(sender instanceof Player player){
            List<BaseComponent> all=new ArrayList<>();
            all.add(new TextComponent(" "));
            if(!prevText.isEmpty()){
                BaseComponent[] parts=SpigotComponents.of(TextFormatter.parse(prevText));
                ClickEvent click=new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/baltop "+(page-1));
                HoverEvent hover=new HoverEvent(HoverEvent.Action.SHOW_TEXT,SpigotComponents.of(TextFormatter.parse("&7Go to page &e"+(page-1))));
                for(BaseComponent part:parts){part.setClickEvent(click);part.setHoverEvent(hover);}
                all.addAll(Arrays.asList(parts));
            }
            if(!prevText.isEmpty()&&!nextText.isEmpty()){
                all.add(new TextComponent("  "));
            }
            if(!nextText.isEmpty()){
                BaseComponent[] parts=SpigotComponents.of(TextFormatter.parse(nextText));
                ClickEvent click=new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/baltop "+(page+1));
                HoverEvent hover=new HoverEvent(HoverEvent.Action.SHOW_TEXT,SpigotComponents.of(TextFormatter.parse("&7Go to page &e"+(page+1))));
                for(BaseComponent part:parts){part.setClickEvent(click);part.setHoverEvent(hover);}
                all.addAll(Arrays.asList(parts));
            }
            player.spigot().sendMessage(all.toArray(BaseComponent[]::new));
        }else{
            String combined=prevText.isEmpty()?nextText:(nextText.isEmpty()?prevText:prevText+"  "+nextText);
            if(!combined.isEmpty())TextSender.send(sender," "+combined);
        }
    }
    public boolean reload(){
        EconomySettings next;
        try{next=readSettings(plugin);}catch(RuntimeException error){plugin.logger().warning("Invalid economy settings: "+error.getMessage());return false;}
        reloading=true;
        try{
            // No accepted work is dropped; its commit finishes before the settings installation barrier.
            CompletableFuture<Void> barrier=new CompletableFuture<>();
            worker.execute(()->{try{engine.installSettings(next);openSystemAccount(next.tax().accountUuid());barrier.complete(null);}catch(Exception error){barrier.completeExceptionally(error);}});
            barrier.get(10,TimeUnit.SECONDS);readPresentation();return true;
        }catch(Exception error){plugin.logger().warning("Economy reload refused; prior settings retained: "+error.getMessage());return false;}
        finally{reloading=false;}
    }
    @Override public void close(){
        int phase;
        synchronized(admission){if(closed)return;closed=true;phase=accepted.arriveAndDeregister();}
        if(cheques!=null)cheques.close();
        HandlerList.unregisterAll(this);
        try{accepted.awaitAdvanceInterruptibly(phase,10,TimeUnit.SECONDS);}
        catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(TimeoutException error){plugin.logger().warning("Economy shutdown has unresolved accepted work; journal recovery remains authoritative.");}
        var prefs=preferences();if(prefs!=null&&prefs.repository()!=null)prefs.repository().coordinateWritesWith(null);
        var messages=messaging();if(messages!=null&&messages.ignores()!=null&&messages.ignores().repository()!=null)messages.ignores().repository().coordinateWritesWith(null);
    }
}
