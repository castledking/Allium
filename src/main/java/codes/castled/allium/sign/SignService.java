package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.mail.MailCommand;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.module.*;
import codes.castled.allium.economy.*;
import codes.castled.allium.message.Messages;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Face PDC is the sole action authority, with no location cache to survive destruction. */
public final class SignService implements Listener,AutoCloseable {
    public static final String ESSENTIALS_SIGN_MARKER = "import/essentials-signs-adopted";
    private final PluginStart plugin;
    private final Set<SignIdentity.Type> enabled=EnumSet.noneOf(SignIdentity.Type.class);
    private final Map<UUID, Object> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final SignClickBudget clicks=new SignClickBudget();
    private final int limit;
    private final boolean shortSignPrices;
    private final boolean unsafeSignPrices;
    private volatile boolean closed;
    private SignEditorGuard editorGuard;
    private final PaidRepairService paidRepairs;
    private final PaidSignService paidSigns;
    public SignService(PluginStart plugin){this(plugin,null,null);}
    public SignService(PluginStart plugin,PaidRepairService paidRepairs){this(plugin,paidRepairs,null);}
    public SignService(PluginStart plugin,PaidRepairService paidRepairs,PaidSignService paidSigns){
        this.plugin=plugin;this.paidRepairs=paidRepairs;this.paidSigns=paidSigns;
        for(String raw:plugin.getConfig().getStringList("enabledSigns"))enabled.add(SignIdentity.Type.parse(raw));
        limit=plugin.getConfig().getInt("sign-use-per-second",4);if(limit<1||limit>100)throw new IllegalArgumentException("sign-use-per-second must be 1..100");
        shortSignPrices=plugin.getConfig().getBoolean("sign-short-prices",true);
        unsafeSignPrices=plugin.getConfig().getBoolean("unsafe-sign-prices",false);
    }
    public void enable(){plugin.getServer().getPluginManager().registerEvents(this,plugin);editorGuard=new SignEditorGuard(plugin,this);}
    public void close(){closed=true;if(editorGuard!=null)editorGuard.close();HandlerList.unregisterAll(this);}
    private NamespacedKey key(Side side){return new NamespacedKey(plugin,"sign_"+side.name().toLowerCase(Locale.ROOT));}
    private Optional<SignIdentity> identity(Sign sign,Side side){return SignIdentity.decode(sign.getPersistentDataContainer().get(key(side),PersistentDataType.STRING));}
    private boolean permission(Player p,String action,SignIdentity.Type type){return p.hasPermission("allium.signs."+action+"."+type.key());}
    /**
     * The type named by a header line. Color codes are ignored: re-editing a face keeps each line's
     * existing style on Paper, so an Allium header arrives as {@code §1[repair]}.
     */
    static SignIdentity.Type header(String line){
        if(line==null)return null;
        line=plain(ChatColor.translateAlternateColorCodes('&',line));
        if(line==null||!line.startsWith("[")||!line.endsWith("]"))return null;
        try{return SignIdentity.Type.parse(line.substring(1,line.length()-1));}catch(IllegalArgumentException error){return null;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void changing(SignChangeEvent event){
        if(!(event.getBlock().getState() instanceof Sign sign))return;
        Side side=event.getSide();var old=identity(sign,side);var type=header(event.getLine(0));
        if(old.isPresent()&&!permission(event.getPlayer(),"break",old.get().type())){event.setCancelled(true);return;}
        if (type == null || !enabled.contains(type)) {
            if (old.isPresent() || type != null) afterEdit(event, () -> {
                if (closed || event.isCancelled()) return;
                if (event.getBlock().getState() instanceof Sign current
                        && Arrays.equals(current.getSide(side).getLines(), event.getLines())
                        && old.isPresent() && identity(current, side).filter(old.get()::equals).isPresent()) {
                    current.getPersistentDataContainer().remove(key(side));
                    current.update(false, false);
                }
                if (type != null) disabled(event.getPlayer(), type);
            });
            return;
        }
        boolean publicWarp=type==SignIdentity.Type.WARP&&"public".equalsIgnoreCase(plain(event.getLine(2)));
        if(!permission(event.getPlayer(),"create",type)||publicWarp&&!event.getPlayer().hasPermission("allium.signs.create.warp.public")){event.setCancelled(true);return;}
        String argument=Objects.toString(plain(event.getLine(1)),"");
        Money price=new Money(0);
        boolean priceOverride=false;
        if(type==SignIdentity.Type.REPAIR){
            argument=argument.isEmpty()?"hand":argument.toLowerCase(Locale.ROOT);
            if(!Set.of("hand","all").contains(argument)){
                invalid(event,type,2,"<hand|all>","Use hand or all.");
                return;
            }
        } else if(type==SignIdentity.Type.GAMEMODE){
            try{argument=SignIdentity.normalizeGameMode(argument);}
            catch(IllegalArgumentException bad){
                invalid(event,type,2,"<gamemode>","Use survival, creative, adventure or spectator.");
                return;
            }
        } else if(type==SignIdentity.Type.TIME){
            try{argument=SignIdentity.normalizeTime(argument);}
            catch(IllegalArgumentException bad){
                invalid(event,type,2,"<day|night>","Use day, night, or leave blank to check time.");
                return;
            }
        } else if(type==SignIdentity.Type.WEATHER){
            try{argument=SignIdentity.normalizeWeather(argument);}
            catch(IllegalArgumentException bad){
                invalid(event,type,2,"<sun|storm>","Use sun, storm, or leave blank to check weather.");
                return;
            }
        } else if(type==SignIdentity.Type.RANDOMTELEPORT){
            if(!argument.isEmpty()&&!argument.matches("[a-zA-Z0-9_-]{1,32}")){
                invalid(event,type,2,"<zone>","Enter a zone name or leave blank.");
                return;
            }
        } else if(type==SignIdentity.Type.FREE){
            if(resolveFreeItem(argument)==null){
                invalid(event,type,2,"<item>","Enter a valid item name.");
                return;
            }
        } else if(type==SignIdentity.Type.ENCHANT){
            String itemToken=argument.isEmpty()?"*":argument;
            if(!itemToken.equals("*")&&!itemToken.equalsIgnoreCase("any")&&Material.matchMaterial(itemToken)==null){
                invalid(event,type,2,"<item|any>","Enter a material name, '*' or 'any'.");
                return;
            }
            String rawEnchant=Objects.toString(plain(event.getLine(2)),"");
            String[] parts=rawEnchant.split(":",2);
            var ench=findEnchantment(parts[0]);
            if(ench==null){
                invalid(event,type,3,"<enchant>","Enter a valid enchantment name (optional :level).");
                return;
            }
            int level=1;
            if(parts.length==2&&!parts[1].isEmpty()){
                try{level=Integer.parseInt(parts[1].trim());}
                catch(NumberFormatException bad){
                    invalid(event,type,3,"<enchant:level>","Enchantment level must be a whole number.");
                    return;
                }
            }
            boolean allowUnsafe=plugin.getConfig().getBoolean("allow-unsafe-enchants",false)
                    &&event.getPlayer().hasPermission("allium.enchant.unsafe")
                    &&event.getPlayer().hasPermission("allium.signs.enchant.allowunsafe");
            if(level<0||(!allowUnsafe&&level>ench.getMaxLevel())){
                level=Math.max(1,ench.getMaxLevel());
                event.setLine(2,parts[0].trim()+":"+level);
            }
            argument=itemToken+"|"+parts[0].trim()+":"+level;
        } else if(type==SignIdentity.Type.SPAWNMOB){
            int amount;
            try{amount=Integer.parseInt(argument);}
            catch(NumberFormatException bad){
                invalid(event,type,2,"<amount>","Enter an amount from 1 to 256.");
                return;
            }
            if(amount<1||amount>256){
                invalid(event,type,2,"<amount>","Enter an amount from 1 to 256.");
                return;
            }
            String mob=Objects.toString(plain(event.getLine(2)),"");
            if(!validMobSpec(mob)){
                invalid(event,type,3,"<mob>","Enter a valid mob name (e.g. wolf:tamed).");
                return;
            }
            argument=amount+"|"+mob;
        }
        int priceLine=switch(type){
            case COLOR->-1;
            case WARP->publicWarp?3:2;
            case ENCHANT,SPAWNMOB->3;
            case TIME,WEATHER->argument.isEmpty()?-1:2;
            default->2;
        };
        EconomyDisplay display=EconomyDisplay.read(plugin.getConfig());
        if(priceLine>=0){
            String rawPrice=Objects.toString(plain(event.getLine(priceLine)),"");
            if(!rawPrice.isEmpty()){
                priceOverride=true;
                try{price=display.parsePrice(rawPrice);}
                catch(IllegalArgumentException|ArithmeticException error){
                    invalid(event,type,priceLine+1,"<price>","Enter a non-negative amount with up to two decimal places.");
                    return;
                }
                Money configuredCost=configuredCost(type,argument);
                if(!unsafeSignPrices&&price.compareTo(configuredCost)<0){
                    markInvalid(event,type,priceLine+1,"<price>");
                    plugin.getMessageManager().send(event.getPlayer(),Messages.SIGN_UNSAFE_PRICE,
                            "{price}",display.format(price),"{cost}",display.format(configuredCost));
                    return;
                }
            }
        }
        SignIdentity created;
        try{created=new SignIdentity(UUID.randomUUID(),type,argument,publicWarp,price,priceOverride);}
        catch(IllegalArgumentException invalid){
            invalid(event,type,2,"<name>","Enter a name with 1–32 letters, digits, underscores or hyphens.");
            return;
        }
        for(int i=0;i<4;i++)event.setLine(i,ChatColor.translateAlternateColorCodes('&',Objects.toString(event.getLine(i),"")));
        event.setLine(0,ChatColor.DARK_BLUE+displayHeader(type));
        if(type==SignIdentity.Type.REPAIR){
            event.setLine(1,argument.equals("all")?"All":"Hand");
        } else if(type==SignIdentity.Type.GAMEMODE){
            event.setLine(1,Character.toUpperCase(argument.charAt(0))+argument.substring(1));
        } else if((type==SignIdentity.Type.TIME||type==SignIdentity.Type.WEATHER)&&!argument.isEmpty()){
            event.setLine(1,ChatColor.DARK_GREEN+""+Character.toUpperCase(argument.charAt(0))+argument.substring(1));
        }
        if(priceOverride&&priceLine>=0){
            event.setLine(priceLine,display.formatSign(price,shortSignPrices));
        }
        // Bukkit applies text after the event. Re-read then update PDC without overwriting either face.
        afterEdit(event, () -> {
            if(closed||event.isCancelled()||!(event.getBlock().getState() instanceof Sign current))return;
            if(!Arrays.equals(current.getSide(side).getLines(),event.getLines()))return;
            current.getPersistentDataContainer().set(key(side),PersistentDataType.STRING,created.encode());current.update(false,false);
        });
    }

    private void afterEdit(SignChangeEvent event, Runnable edit) {
        Block block = event.getBlock();
        var scheduler = plugin.getScheduler();
        long delay = TaskScheduler.MILLIS_PER_TICK;
        if (BukkitServerThread.isFolia()) {
            int chunkX = block.getX() >> 4;
            int chunkZ = block.getZ() >> 4;
            World world = block.getWorld();
            scheduler.runOn(BukkitServerThread.region(plugin, world, chunkX, chunkZ), () -> {
                if (!closed && world.isChunkLoaded(chunkX, chunkZ)) edit.run();
            }, () -> {}, delay);
        } else {
            scheduler.runOnServerLater(edit, delay);
        }
    }

    /** A sign line without color codes (null stays null). */
    static String plain(String line){return line==null?null:ChatColor.stripColor(line).trim();}
    private static String displayHeader(SignIdentity.Type type){
        return switch(type){
            case GAMEMODE->"[GameMode]";
            case RANDOMTELEPORT->"[RandomTeleport]";
            default->{String name=type.key();yield "["+Character.toUpperCase(name.charAt(0))+name.substring(1)+"]";}
        };
    }
    /** Configured cost of the underlying kit/command, used to enforce {@code unsafe-sign-prices}. */
    private Money configuredCost(SignIdentity.Type type,String argument){
        if(type==SignIdentity.Type.KIT&&plugin.getModuleManager()!=null
                &&plugin.getModuleManager().getModule("kits") instanceof KitsModule kits
                &&kits.isEnabled()&&kits.kits()!=null&&argument!=null&&!argument.isBlank()){
            var def=kits.kits().definitions().get(argument.toLowerCase(Locale.ROOT));
            if(def!=null)return Money.parse(def.cost(),false);
        }
        return new Money(0);
    }
    private void invalid(SignChangeEvent event,SignIdentity.Type type,int line,String hint,String options){
        markInvalid(event,type,line,hint);
        plugin.getMessageManager().send(event.getPlayer(),Messages.SIGN_INVALID_LINE,
                "{line}",Integer.toString(line),"{options}",options);
    }
    private void markInvalid(SignChangeEvent event,SignIdentity.Type type,int line,String hint){
        event.setLine(0,ChatColor.RED+displayHeader(type));
        event.setLine(line-1,ChatColor.RED+hint);
        Side side=event.getSide();
        afterEdit(event, () -> {
            if(closed||event.isCancelled()||!(event.getBlock().getState() instanceof Sign current))return;
            if(!Arrays.equals(current.getSide(side).getLines(),event.getLines()))return;
            current.getPersistentDataContainer().remove(key(side));
            current.update(false,false);
        });
    }
    public boolean blocksEditor(Sign sign,Side side,String cause){return !closed&&"INTERACT".equals(cause)&&identity(sign,side).filter(id->id.type()!=SignIdentity.Type.COLOR&&enabled.contains(id.type())).isPresent();}
    private void disabled(Player player,SignIdentity.Type type){UtilitySupport.send(plugin,player,Messages.SIGN_DISABLED,"{type}",type.key());}
    private Side clickedSide(Sign sign,Player player){
        org.bukkit.util.Vector normal;
        var data=sign.getBlockData();
        if(data instanceof Directional directional)normal=directional.getFacing().getDirection();
        else if(data instanceof Rotatable rotatable)normal=rotatable.getRotation().getDirection();
        else return Side.FRONT;
        var toward=player.getEyeLocation().toVector().subtract(sign.getLocation().add(.5,.5,.5).toVector());
        return toward.dot(normal)>=0?Side.FRONT:Side.BACK;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event){
        if(closed||!adoptEssentialsSignsActive())return;
        for(BlockState state:event.getChunk().getTileEntities()){
            if(state instanceof Sign sign){
                for(Side side:Side.values())adoptEssentialsSign(sign,side);
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void using(PlayerInteractEvent event){
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getHand()!=EquipmentSlot.HAND||event.useInteractedBlock()==Event.Result.DENY||event.getClickedBlock()==null||!(event.getClickedBlock().getState() instanceof Sign sign))return;
        Side side=clickedSide(sign,event.getPlayer());
        var found=identity(sign,side);
        if(found.isEmpty()&&adoptEssentialsSignsActive()&&adoptEssentialsSign(sign,side)){
            found=identity(sign,side);
        }
        if(found.isEmpty())return;
        var identity=found.get();Player player=event.getPlayer();
        if(!enabled.contains(identity.type())){if(clicks.admit(player.getUniqueId(),System.nanoTime(),limit))disabled(player,identity.type());return;}
        UUID playerId = player.getUniqueId();
        if (pending.containsKey(playerId) || !permission(player, "use", identity.type())
                || !clicks.admit(playerId, System.nanoTime(), limit)) return;
        Object activation = new Object();
        if (pending.putIfAbsent(playerId, activation) != null) return;
        Block clicked = event.getClickedBlock();
        World world = clicked.getWorld();
        int chunkX = clicked.getX() >> 4;
        int chunkZ = clicked.getZ() >> 4;
        var region = BukkitServerThread.isFolia() ? BukkitServerThread.region(plugin, world, chunkX, chunkZ) : null;
        Runnable cleanup = () -> pending.remove(playerId, activation);

        // Let all protection handlers finish before any service can mutate state.
        TaskHandle task = BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            try {
                if (closed || pending.get(playerId) != activation || event.isCancelled()
                        || event.useInteractedBlock() == Event.Result.DENY || !player.isOnline()
                        || region != null && (!region.isServerThread() || !world.isChunkLoaded(chunkX, chunkZ))
                        || !(clicked.getState() instanceof Sign current)
                        || identity(current, side).filter(identity::equals).isEmpty()
                        || !permission(player, "use", identity.type())) return;
                execute(player, identity, () -> !closed && !event.isCancelled()
                        && event.useInteractedBlock() != Event.Result.DENY
                        && (region == null || region.isServerThread())
                        && world.isChunkLoaded(chunkX, chunkZ)
                        && clicked.getState() instanceof Sign live && identity(live, side).filter(identity::equals).isPresent()
                        && permission(player, "use", identity.type()));
            } finally {
                cleanup.run();
            }
        }, cleanup, TaskScheduler.MILLIS_PER_TICK);
        if (region != null && task == TaskHandle.NONE) cleanup.run();
    }
    private void execute(Player p,SignIdentity identity,BooleanSupplier faceValid){
        String command=switch(identity.type()){
            case COLOR->null;case WARP->"warp";case KIT->"kit";case BALANCE->"balance";case MAIL->"mail";case INFO->"info";
            case CARTOGRAPHY->"cartographytable";case RANDOMTELEPORT->"rtp";case FREE->"free";
            default->identity.type().key();
        };
        if(!plugin.getConfig().getBoolean("modules.signs",true)||command==null
                ||plugin.getCommandManager().getFamilyPolicy().isDisabled(command))return;
        String module=switch(identity.type()){
            case WARP->"travel";case KIT->"kits";case BALANCE->"economy";case MAIL->"mail";case RANDOMTELEPORT->"rtp";
            default->"essentials";
        };
        var owner=plugin.getModuleManager().getModule(module);if(owner==null||!owner.isEnabled()||!plugin.getConfig().getBoolean("modules."+module,true)||identity.type()==SignIdentity.Type.WARP&&!plugin.getConfig().getBoolean("modules.warps",true))return;
        if (BukkitServerThread.isFolia() && !supportsPaidFolia(identity.type()) && (identity.price().minor() > 0
                || identity.type() == SignIdentity.Type.KIT && !identity.priceOverride()
                    && configuredCost(identity.type(), identity.argument()).minor() > 0)) {
            // Paid work revalidates the sign after reserving funds and needs its own region handoff.
            plugin.getMessageManager().send(p, Messages.SIGN_PAID_FOLIA_UNAVAILABLE);
            return;
        }
        BooleanSupplier valid = () -> faceValid.getAsBoolean()
                && p.isOnline() && plugin.getServer().getPlayer(p.getUniqueId()) == p
                && owner.isEnabled() && plugin.getConfig().getBoolean("modules.signs", true)
                && plugin.getConfig().getBoolean("modules." + module, true)
                && (identity.type() != SignIdentity.Type.WARP || plugin.getConfig().getBoolean("modules.warps", true))
                && !plugin.getCommandManager().getFamilyPolicy().isDisabled(command);
        if(identity.type()==SignIdentity.Type.FREE){
            runFree(p,identity,valid);
            return;
        }
        if(identity.type()==SignIdentity.Type.ENCHANT){
            runEnchant(p,identity,valid);
            return;
        }
        if(identity.type()==SignIdentity.Type.SPAWNMOB){
            runSpawnMob(p,identity,valid);
            return;
        }
        if(identity.type()==SignIdentity.Type.WARP){
            var travel=((TravelModule)owner).travel();
            if(identity.price().minor()>0){
                if(!travel.canUseSign(p,identity.argument(),identity.publicWarp()))return;
                charge(p, identity, valid, (active, done) ->
                        travel.useSign(p, identity.argument(), identity.publicWarp(), active, done));
                return;
            }
            travel.useSign(p,identity.argument(),identity.publicWarp());return;
        }
        if(identity.type()==SignIdentity.Type.KIT){
            if(identity.priceOverride()){
                ((KitsModule)owner).kits().claimFromSign(p,identity.argument(),identity.price(),valid);
                return;
            }
            if(configuredCost(identity.type(),identity.argument()).minor()>0){
                ((KitsModule)owner).kits().claimFromSign(p,identity.argument(),null,valid);
                return;
            }
        }
        if(identity.type()==SignIdentity.Type.RANDOMTELEPORT&&identity.price().minor()>0){
            ((RtpModule) owner).rtp().useSign(p, identity.argument().isEmpty() ? null : identity.argument(),
                    identity.price(), valid);
            return;
        }
        var renderer=plugin.getCommandManager().getRenderedCommands().get(command);var registered=plugin.getCommand(command);
        if(renderer==null||registered==null)return;
        if(identity.type()==SignIdentity.Type.REPAIR&&identity.price().minor()>0){
            // Reserve, repair and save with a receipt, then capture: see PaidRepairService.
            if(paidRepairs==null)plugin.getMessageManager().send(p,Messages.SIGN_PAID_REPAIR_DISABLED);
            else paidRepairs.start(p,identity.argument(),identity.price(),valid);
            return;
        }
        String[] args=switch(identity.type()){
            case KIT,REPAIR,GAMEMODE->new String[]{identity.argument()};
            case MAIL->new String[]{"read"};
            case TIME->identity.argument().isEmpty()?new String[0]:new String[]{"set",identity.argument()};
            case WEATHER,RANDOMTELEPORT->identity.argument().isEmpty()?new String[0]:new String[]{identity.argument()};
            default->new String[0];
        };
        if(identity.price().minor()>0){
            var handler = renderer.handler();
            var spec = handler.spec();
            BooleanSupplier allowed = () -> plugin.getCommandManager().getFamilyPolicy()
                    .admission(spec, command, p::hasPermission) == CommandFamilyPolicy.Admission.ALLOWED;
            if(!allowed.getAsBoolean()){
                plugin.getMessageManager().send(p,Messages.NO_PERMISSION,"{cmd}",command);
                return;
            }
            BooleanSupplier canDeliver = () -> valid.getAsBoolean() && allowed.getAsBoolean();
            if (handler instanceof codes.castled.allium.command.essentials.TimeCommand time) {
                var context = new BukkitCommandContext(plugin, spec, p, command, args);
                charge(p, identity, canDeliver, (active, done) -> time.executeForSign(context, done));
            } else if (handler instanceof codes.castled.allium.command.essentials.WeatherCommand weather) {
                var context = new BukkitCommandContext(plugin, spec, p, command, args);
                charge(p, identity, canDeliver, (active, done) -> weather.executeForSign(context, done));
            } else if (handler instanceof SpecCommandHandler.Immediate immediate) {
                var context = new BukkitCommandContext(plugin, spec, p, command, args);
                charge(p, identity, canDeliver, (active, done) -> done.accept(immediate.executeWithResult(context)));
            } else if (handler instanceof MailCommand mail) {
                charge(p, identity, canDeliver, (active, done) -> mail.readSign(p, active, done));
            } else if (identity.type() == SignIdentity.Type.BALANCE) {
                var economy = ((EconomyModule) owner).runtime();
                charge(p, identity, canDeliver, (active, done) -> economy.showSignBalance(p, active, done));
            } else plugin.getMessageManager().send(p, Messages.SIGN_PAID_UNAVAILABLE);
            return;
        }
        renderer.onCommand(p,registered,command,args);
    }

    private static boolean supportsPaidFolia(SignIdentity.Type type) {
        return switch (type) {
            case ANVIL, BALANCE, CARTOGRAPHY, DISPOSAL, ENCHANT, FREE, GAMEMODE, GRINDSTONE, HEAL,
                    INFO, KIT, LOOM, MAIL, RANDOMTELEPORT, REPAIR, SMITHING, SPAWNMOB, TIME, WARP,
                    WEATHER, WORKBENCH -> true;
            default -> false;
        };
    }

    private void charge(Player player, SignIdentity identity, BooleanSupplier valid, PaidSignService.Action action) {
        if (paidSigns == null) plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
        else paidSigns.start(player, identity, valid, action);
    }

    private void runFree(Player p,SignIdentity identity,BooleanSupplier valid){
        if(codes.castled.allium.command.essentials.UtilitySupport.isInventoryBusy(plugin,p))return;
        org.bukkit.inventory.ItemStack resolved=resolveFreeItem(identity.argument());
        if(resolved==null||resolved.getType().isAir())return;
        BooleanSupplier open=()->{
            if(codes.castled.allium.command.essentials.UtilitySupport.isInventoryBusy(plugin,p))return false;
            org.bukkit.inventory.ItemStack stack=resolved.clone();
            stack.setAmount(Math.max(1,stack.getMaxStackSize()));
            String title=stack.getType().name();
            if(Bukkit.getServer()!=null&&stack.hasItemMeta()){
                var meta=stack.getItemMeta();
                if(meta!=null&&meta.hasDisplayName())title=meta.getDisplayName();
            }
            org.bukkit.inventory.Inventory inv=plugin.getServer().createInventory(p,36,title);
            for(int i=0;i<36;i++)inv.setItem(i,stack.clone());
            return p.openInventory(inv) != null;
        };
        if(identity.price().minor()>0){
            charge(p, identity, valid, (active, done) -> done.accept(open.getAsBoolean()));
            return;
        }
        open.getAsBoolean();
    }

    private void runEnchant(Player p,SignIdentity identity,BooleanSupplier valid){
        int sep=identity.argument().indexOf('|');
        if(sep<=0||sep>=identity.argument().length()-1)return;
        String itemToken=identity.argument().substring(0,sep);
        String[] enchParts=identity.argument().substring(sep+1).split(":",2);
        var ench=findEnchantment(enchParts[0]);
        if(ench==null)return;
        int level=enchParts.length==2?Integer.parseInt(enchParts[1]):1;
        org.bukkit.inventory.ItemStack hand=p.getInventory().getItemInMainHand();
        if(hand==null||hand.getType().isAir()||hand.getAmount()!=1){
            plugin.getMessageManager().send(p,Messages.UTILITY_HOLD_ITEM);
            return;
        }
        String itemLabel=hand.getType().name().toLowerCase(Locale.ROOT);
        if(!itemToken.equals("*")&&!itemToken.equalsIgnoreCase("any")){
            Material required=Material.matchMaterial(itemToken);
            if(required==null||hand.getType()!=required){
                plugin.getMessageManager().send(p,Messages.ENCHANT_INCOMPATIBLE,"{enchant}",enchParts[0],"{item}",itemLabel);
                return;
            }
        }
        if((level==0&&!hand.containsEnchantment(ench))||(level>0&&hand.getEnchantmentLevel(ench)==level))return;
        boolean allowUnsafe=plugin.getConfig().getBoolean("allow-unsafe-enchants",false)
                &&p.hasPermission("allium.signs.enchant.allowunsafe");
        if(level>0&&!allowUnsafe&&(level>ench.getMaxLevel()||!ench.canEnchantItem(hand))){
            plugin.getMessageManager().send(p,Messages.ENCHANT_INCOMPATIBLE,"{enchant}",enchParts[0],"{item}",itemLabel);
            return;
        }
        var expected = hand.clone();
        int slot = p.getInventory().getHeldItemSlot();
        BooleanSupplier apply=()->{
            org.bukkit.inventory.ItemStack current=p.getInventory().getItemInMainHand();
            if(current==null||p.getInventory().getHeldItemSlot()!=slot||!expected.equals(current)
                    ||codes.castled.allium.command.essentials.UtilitySupport.isInventoryBusy(plugin,p)){
                plugin.getMessageManager().send(p,Messages.SIGN_ENCHANT_CHANGED);
                return false;
            }
            boolean unsafeNow = allowUnsafe && plugin.getConfig().getBoolean("allow-unsafe-enchants",false)
                    && p.hasPermission("allium.signs.enchant.allowunsafe");
            if(level>0&&!unsafeNow&&(level>ench.getMaxLevel()||!ench.canEnchantItem(current)))return false;
            String currentLabel=current.getType().name().toLowerCase(Locale.ROOT);
            if(level==0){
                current.removeEnchantment(ench);
                plugin.getMessageManager().send(p,Messages.UTILITY_ENCHANT_REMOVED,"{enchant}",enchParts[0],"{player}",p.getName(),"{item}",currentLabel);
            } else {
                if(unsafeNow)current.addUnsafeEnchantment(ench,level);
                else current.addEnchantment(ench,level);
                plugin.getMessageManager().send(p,Messages.UTILITY_ENCHANT_APPLIED,"{enchant}",enchParts[0],"{level}",Integer.toString(level),"{player}",p.getName(),"{item}",currentLabel);
            }
            p.updateInventory();
            return true;
        };
        if(identity.price().minor()>0){
            charge(p, identity, valid, (active, done) -> done.accept(apply.getAsBoolean()));
            return;
        }
        apply.getAsBoolean();
    }

    private void runSpawnMob(Player p,SignIdentity identity,BooleanSupplier valid){
        int sep=identity.argument().indexOf('|');
        if(sep<=0||sep>=identity.argument().length()-1)return;
        int requested;
        try{requested=Integer.parseInt(identity.argument().substring(0,sep));}
        catch(NumberFormatException bad){return;}
        String mobSpec=identity.argument().substring(sep+1);
        var parts=codes.castled.allium.command.essentials.MobSpawner.parseSpec(mobSpec);
        if(parts.isEmpty())return;
        List<codes.castled.allium.command.essentials.MobSpawner.MobEntry> entries=new ArrayList<>(parts.size());
        List<String> datas=new ArrayList<>(parts.size());
        for(var part:parts){
            var found=codes.castled.allium.command.essentials.MobSpawner.lookup(part.mobToken());
            if(found.isEmpty()){
                plugin.getMessageManager().send(p,Messages.SPAWNMOB_INVALID_MOB,"{mob}",part.mobToken());
                return;
            }
            entries.add(found.get());
            datas.add(part.rawData());
        }
        int serverLimit=Math.max(1,Math.min(256,plugin.getConfig().getInt("spawnmob.limit",10)));
        if(entries.size()>serverLimit){
            entries=new ArrayList<>(entries.subList(0,serverLimit));
            datas=new ArrayList<>(datas.subList(0,serverLimit));
        }
        int count=Math.min(requested,Math.max(1,serverLimit/entries.size()));
        List<codes.castled.allium.command.essentials.MobSpawner.MobEntry> finalEntries=List.copyOf(entries);
        List<String> finalDatas=Collections.unmodifiableList(new ArrayList<>(datas));
        BooleanSupplier spawn=()->{
            Location loc=p.getLocation();
            boolean paid = identity.price().minor() > 0;
            List<org.bukkit.entity.Entity> spawned = paid ? new ArrayList<>() : null;
            int spawnedStacks = 0;
            try {
                for(;spawnedStacks<count;spawnedStacks++){
                    if(codes.castled.allium.command.essentials.MobSpawner.spawnStack(loc,finalEntries,finalDatas,p,spawned)==null){
                        plugin.getMessageManager().send(p,Messages.SPAWNMOB_UNABLE);
                        return false;
                    }
                }
            } finally {
                if(paid && spawnedStacks<count)spawned.forEach(org.bukkit.entity.Entity::remove);
            }
            int total=spawnedStacks*finalEntries.size();
            String label=finalEntries.size()==1?finalEntries.get(0).canonicalName()
                    :finalEntries.stream().map(codes.castled.allium.command.essentials.MobSpawner.MobEntry::canonicalName)
                    .collect(java.util.stream.Collectors.joining(","));
            plugin.getMessageManager().send(p,Messages.SPAWNMOB_SPAWNED,"{count}",Integer.toString(total),"{mob}",label);
            return true;
        };
        if(identity.price().minor()>0){
            charge(p, identity, valid, (active, done) -> done.accept(spawn.getAsBoolean()));
            return;
        }
        spawn.getAsBoolean();
    }

    private static boolean validMobSpec(String spec){
        if(spec==null||spec.isBlank())return false;
        var parts=codes.castled.allium.command.essentials.MobSpawner.parseSpec(spec);
        if(parts.isEmpty())return false;
        for(var part:parts){
            if(codes.castled.allium.command.essentials.MobSpawner.lookup(part.mobToken()).isEmpty())return false;
        }
        return true;
    }

    /** Whether legacy Essentials dark-blue signs should be adopted into Allium PDC automatically. */
    public boolean adoptEssentialsSignsActive(){
        if(plugin.getConfig().getBoolean("adopt-essentials-signs",false))return true;
        var folder=plugin.getDataFolder();
        return folder!=null&&java.nio.file.Files.isRegularFile(folder.toPath().resolve(ESSENTIALS_SIGN_MARKER));
    }

    /** Scans all currently loaded chunks and adopts any legacy Essentials dark-blue signs. */
    public int adoptLoadedChunks(){
        int adopted=0;
        var server=plugin.getServer();
        if(server==null)return 0;
        boolean folia = BukkitServerThread.isFolia();
        for(World world:server.getWorlds()){
            for(Chunk chunk:world.getLoadedChunks()){
                int cx = chunk.getX(), cz = chunk.getZ();
                if (folia && !BukkitServerThread.region(plugin, world, cx, cz).isServerThread()) {
                    UtilitySupport.onRegion(plugin, world, cx, cz, () -> {
                        if (closed || !world.isChunkLoaded(cx, cz)) return;
                        adoptChunk(world.getChunkAt(cx, cz));
                    });
                    continue;
                }
                adopted += adoptChunk(chunk);
            }
        }
        return adopted;
    }

    private int adoptChunk(Chunk chunk) {
        int adopted = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Sign sign) {
                for (Side side : Side.values()) {
                    if (adoptEssentialsSign(sign, side)) adopted++;
                }
            }
        }
        return adopted;
    }

    /** Counts adoptable legacy Essentials signs in currently loaded chunks (read-only, for dry runs). */
    public int countLoadedEssentialsSigns(){
        int count=0;
        var server=plugin.getServer();
        if(server==null)return 0;
        boolean folia = BukkitServerThread.isFolia();
        for(World world:server.getWorlds()){
            for(Chunk chunk:world.getLoadedChunks()){
                if (folia && !BukkitServerThread.region(plugin, world, chunk.getX(), chunk.getZ()).isServerThread()) {
                    continue;
                }
                for(BlockState state:chunk.getTileEntities()){
                    if(state instanceof Sign sign){
                        for(Side side:Side.values()){
                            if(identity(sign,side).isEmpty()&&parseEssentialsFace(sign.getSide(side).getLines()).isPresent())count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Converts one un-migrated Essentials dark-blue sign face into an Allium sign PDC identity. */
    public boolean adoptEssentialsSign(Sign sign,Side side){
        if(identity(sign,side).isPresent())return false;
        var parsed=parseEssentialsFace(sign.getSide(side).getLines());
        if(parsed.isEmpty())return false;
        sign.getPersistentDataContainer().set(key(side),PersistentDataType.STRING,parsed.get().encode());
        sign.update(false,false);
        return true;
    }

    /** Parses a legacy Essentials dark-blue sign face (line 0 starts with §1) into a {@link SignIdentity}. */
    Optional<SignIdentity> parseEssentialsFace(String[] lines){
        if(lines==null||lines.length<4||lines[0]==null||!lines[0].startsWith(ChatColor.DARK_BLUE.toString()))return Optional.empty();
        var type=header(lines[0]);
        if(type==null||type==SignIdentity.Type.COLOR)return Optional.empty();
        boolean publicWarp=type==SignIdentity.Type.WARP&&"public".equalsIgnoreCase(plain(lines[2]));
        String argument=Objects.toString(plain(lines[1]),"");
        if(type==SignIdentity.Type.REPAIR){
            argument=argument.isEmpty()?"hand":argument.toLowerCase(Locale.ROOT);
            if(!Set.of("hand","all").contains(argument))return Optional.empty();
        } else if(type==SignIdentity.Type.GAMEMODE){
            try{argument=SignIdentity.normalizeGameMode(argument);}catch(IllegalArgumentException bad){return Optional.empty();}
        } else if(type==SignIdentity.Type.TIME){
            try{argument=SignIdentity.normalizeTime(argument);}catch(IllegalArgumentException bad){return Optional.empty();}
        } else if(type==SignIdentity.Type.WEATHER){
            try{argument=SignIdentity.normalizeWeather(argument);}catch(IllegalArgumentException bad){return Optional.empty();}
        } else if(type==SignIdentity.Type.RANDOMTELEPORT){
            if(!argument.isEmpty()&&!argument.matches("[a-zA-Z0-9_-]{1,32}"))return Optional.empty();
        } else if(type==SignIdentity.Type.FREE){
            if(resolveFreeItem(argument)==null)return Optional.empty();
        } else if(type==SignIdentity.Type.ENCHANT){
            String itemToken=argument.isEmpty()?"*":argument;
            if(!itemToken.equals("*")&&!itemToken.equalsIgnoreCase("any")&&Material.matchMaterial(itemToken)==null)return Optional.empty();
            String rawEnchant=Objects.toString(plain(lines[2]),"");
            String[] parts=rawEnchant.split(":",2);
            if(findEnchantment(parts[0])==null)return Optional.empty();
            int level=1;
            if(parts.length==2&&!parts[1].isEmpty()){
                try{level=Integer.parseInt(parts[1].trim());}catch(NumberFormatException bad){return Optional.empty();}
            }
            if(level<0)return Optional.empty();
            argument=itemToken+"|"+parts[0].trim()+":"+level;
        } else if(type==SignIdentity.Type.SPAWNMOB){
            int amount;
            try{amount=Integer.parseInt(argument);}catch(NumberFormatException bad){return Optional.empty();}
            String mob=Objects.toString(plain(lines[2]),"");
            if(amount<1||amount>256||!validMobSpec(mob))return Optional.empty();
            argument=amount+"|"+mob;
        }
        int priceLine=switch(type){
            case WARP->publicWarp?3:2;
            case ENCHANT,SPAWNMOB->3;
            case TIME,WEATHER->argument.isEmpty()?-1:2;
            default->2;
        };
        Money price=new Money(0);
        boolean priceOverride=false;
        if(priceLine>=0){
            String rawPrice=Objects.toString(plain(lines[priceLine]),"");
            if(!rawPrice.isEmpty()){
                try{
                    price=EconomyDisplay.read(plugin.getConfig()).parsePrice(rawPrice);
                    priceOverride=true;
                }catch(IllegalArgumentException|ArithmeticException bad){return Optional.empty();}
            }
        }
        try{return Optional.of(new SignIdentity(UUID.randomUUID(),type,argument,publicWarp,price,priceOverride));}
        catch(IllegalArgumentException bad){return Optional.empty();}
    }

    private org.bukkit.inventory.ItemStack resolveFreeItem(String spec){
        if(spec==null||spec.isBlank())return null;
        String trimmed=spec.trim();
        var itemService=plugin.getItemService();
        if(itemService!=null){
            try{
                var ref=itemService.parseRef(trimmed);
                var result=itemService.create(ref);
                if(result.itemStack().isPresent()&&!result.itemStack().get().getType().isAir()){
                    return result.itemStack().get();
                }
            }catch(RuntimeException ignored){}
        }
        Material mat=Material.matchMaterial(trimmed);
        return mat==null||mat.isAir()||!mat.isItem()?null:new org.bukkit.inventory.ItemStack(mat,1);
    }

    private static org.bukkit.enchantments.Enchantment findEnchantment(String raw){
        if(raw==null||raw.isBlank())return null;
        try{
            var found=codes.castled.allium.item.library.EnchantmentAdapter.find(raw.trim());
            if(found.isPresent())return found.get();
        }catch(RuntimeException|LinkageError ignored){}
        try{
            var byKey=org.bukkit.enchantments.Enchantment.getByKey(NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT)));
            if(byKey!=null)return byKey;
        }catch(RuntimeException|LinkageError ignored){}
        return org.bukkit.enchantments.Enchantment.getByName(raw.trim().toUpperCase(Locale.ROOT));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breaking(BlockBreakEvent event){
        if(!(event.getBlock().getState() instanceof Sign sign))return;
        for(Side side:Side.values())if(identity(sign,side).filter(id->!permission(event.getPlayer(),"break",id.type())).isPresent()){event.setCancelled(true);return;}
    }
    @EventHandler public void quit(PlayerQuitEvent event){clicks.forget(event.getPlayer().getUniqueId());pending.remove(event.getPlayer().getUniqueId());}
}
