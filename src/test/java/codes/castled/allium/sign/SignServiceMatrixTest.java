package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.*;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.*;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.travel.TravelService;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

/** Exercises the actual listener, delayed protection fence and command/service routing. */
class SignServiceMatrixTest {
    @org.junit.jupiter.api.Test void disabledSignCreationKeepsVanillaTextAndNotifies(){var f=new Fixture(SignIdentity.Type.BALANCE,false);f.config.set("enabledSigns",List.of());when(f.plugin.getMessageManager()).thenReturn(mock(codes.castled.allium.message.MessageManager.class));var event=f.edit();String[] before=event.getLines().clone();new SignService(f.plugin).changing(event);f.drain();assertFalse(event.isCancelled());assertArrayEquals(before,event.getLines());assertTrue(f.data.isEmpty());verify(f.plugin.getMessageManager()).send(eq(f.player),eq(codes.castled.allium.message.Messages.SIGN_DISABLED),any(String[].class));}
    @org.junit.jupiter.api.Test void activeUnwaxedFaceBlocksOnlyInteractionEditor(){var f=new Fixture(SignIdentity.Type.REPAIR,false);assertTrue(f.service.blocksEditor(f.sign,Side.FRONT,"INTERACT"));assertFalse(f.service.blocksEditor(f.sign,Side.FRONT,"PLUGIN"));assertFalse(f.service.blocksEditor(f.sign,Side.FRONT,"PLACE"));assertFalse(f.service.blocksEditor(f.sign,Side.BACK,"INTERACT"));f.config.set("enabledSigns",List.of());assertFalse(new SignService(f.plugin).blocksEditor(f.sign,Side.FRONT,"INTERACT"));}
    @org.junit.jupiter.api.Test void disabledHeaderCannotEraseAnExistingSignWithoutBreakPermission(){var f=new Fixture(SignIdentity.Type.REPAIR,false);f.config.set("enabledSigns",List.of());var event=f.edit();f.data.put(new NamespacedKey("allium","sign_front"),new SignIdentity(UUID.randomUUID(),SignIdentity.Type.REPAIR,"",false).encode());when(f.player.hasPermission("allium.signs.break.repair")).thenReturn(false);event.setLine(0,"[balance]");new SignService(f.plugin).changing(event);f.drain();assertTrue(event.isCancelled());assertFalse(f.data.isEmpty());}
    @org.junit.jupiter.api.Test void repairSignStoresModeAndExactPrice(){
        var f=new Fixture(SignIdentity.Type.REPAIR,false);
        var event=f.edit();
        event.setLine(1,"aLL");
        event.setLine(2,"$6,000.25");
        f.service.changing(event);
        f.drain();
        assertEquals("§1[Repair]",event.getLine(0));
        assertEquals("All",event.getLine(1));
        var stored=SignIdentity.decode(f.data.values().iterator().next()).orElseThrow();
        assertEquals("all",stored.argument());
        assertEquals(600025,stored.price().minor());
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"wheel,100,2","hand,1.001,3"})
    void invalidRepairLineIsVisibleAndDoesNotAuthorizeUse(String mode,String price,int line){
        var f=new Fixture(SignIdentity.Type.REPAIR,false);
        var event=f.edit();
        event.setLine(1,mode);
        event.setLine(2,price);
        f.service.changing(event);
        f.drain();
        assertEquals("§c[Repair]",event.getLine(0));
        assertTrue(event.getLine(line-1).startsWith("§c<"));
        assertTrue(f.data.isEmpty());
        verify(f.plugin.getMessageManager()).send(eq(f.player),eq(codes.castled.allium.message.Messages.SIGN_INVALID_LINE),
                eq("{line}"),eq(Integer.toString(line)),eq("{options}"),anyString());
    }
    // Baseline Spigot 1.20 lacks this accessor; model the newer platform capability.
    interface WaxedSign extends Sign { boolean isWaxed(); }
    static final class Fixture {
        final PluginStart plugin=mock(PluginStart.class);
        final Player player=mock(Player.class);
        final WaxedSign sign=mock(WaxedSign.class);
        final Block block=mock(Block.class);
        final PersistentDataContainer pdc=mock(PersistentDataContainer.class);
        final Map<NamespacedKey,String> data=new HashMap<>();
        final Deque<Runnable> callbacks=new ArrayDeque<>();
        final YamlConfiguration config=new YamlConfiguration();
        final BukkitCommandRenderer renderer=mock(BukkitCommandRenderer.class);
        final TravelService travel=mock(TravelService.class);
        final CommandManager commands=mock(CommandManager.class);
        final codes.castled.allium.command.spec.CommandFamilyPolicy policy=mock(codes.castled.allium.command.spec.CommandFamilyPolicy.class);
        final org.bukkit.inventory.Inventory freeInventory=mock(org.bukkit.inventory.Inventory.class);
        final org.bukkit.inventory.PlayerInventory playerInventory=mock(org.bukkit.inventory.PlayerInventory.class);
        final org.bukkit.inventory.ItemStack handItem=mock(org.bukkit.inventory.ItemStack.class);
        final World world=mock(World.class);
        final SignIdentity.Type type;
        final SignService service;
        Fixture(SignIdentity.Type type,boolean waxed){
            when(plugin.getMessageManager()).thenReturn(mock(codes.castled.allium.message.MessageManager.class));
            this.type=type;when(plugin.getName()).thenReturn("Allium");when(plugin.getConfig()).thenReturn(config);
            config.set("enabledSigns",Arrays.stream(SignIdentity.Type.values()).map(SignIdentity.Type::key).toList());
            config.set("sign-use-per-second",4);
            var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);
            when(server.createInventory(eq(player),eq(36),anyString())).thenReturn(freeInventory);
            when(player.getInventory()).thenReturn(playerInventory);
            when(handItem.getType()).thenReturn(Material.DIAMOND_SWORD);
            when(handItem.getAmount()).thenReturn(1);
            when(playerInventory.getItemInMainHand()).thenReturn(handItem);
            var scheduler=mock(TaskScheduler.class);when(plugin.getScheduler()).thenReturn(scheduler);
            doAnswer(call->{callbacks.add(call.getArgument(0));return codes.castled.allium.scheduler.TaskHandle.NONE;}).when(scheduler).runOnServerLater(any(Runnable.class),anyLong());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(player.hasPermission(anyString())).thenReturn(true);
            when(player.getEyeLocation()).thenReturn(new Location(world,.5,65,2.5));when(sign.getLocation()).thenReturn(new Location(world,0,64,0));
            when(player.getLocation()).thenReturn(new Location(world,.5,64,2.5));
            var spawnedMob=mock(org.bukkit.entity.Zombie.class);
            when(world.spawn(any(Location.class),eq(org.bukkit.entity.Zombie.class))).thenReturn(spawnedMob);
            when(world.spawnEntity(any(Location.class),any(org.bukkit.entity.EntityType.class))).thenReturn(spawnedMob);
            var rotation=mock(Rotatable.class);when(rotation.getRotation()).thenReturn(BlockFace.SOUTH);when(sign.getBlockData()).thenReturn(rotation);
            when(block.getState()).thenReturn(sign);when(sign.isWaxed()).thenReturn(waxed);when(sign.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(call->data.get(call.getArgument(0)));
            doAnswer(call->{data.put(call.getArgument(0),call.getArgument(2));return null;}).when(pdc).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
            doAnswer(call->{data.remove(call.getArgument(0));return null;}).when(pdc).remove(any());
            data.put(new NamespacedKey("allium","sign_front"),new SignIdentity(UUID.randomUUID(),type,defaultArg(),false).encode());
            when(plugin.getCommandManager()).thenReturn(commands);when(commands.getFamilyPolicy()).thenReturn(policy);
            when(commands.getRenderedCommands()).thenReturn(Map.of(command(),renderer));when(plugin.getCommand(command())).thenReturn(mock(PluginCommand.class));
            var modules=mock(ModuleManager.class);when(plugin.getModuleManager()).thenReturn(modules);
            var essentials=mock(EssentialsModule.class);when(essentials.isEnabled()).thenReturn(true);when(modules.getModule("essentials")).thenReturn(essentials);
            var kits=mock(KitsModule.class);when(kits.isEnabled()).thenReturn(true);when(modules.getModule("kits")).thenReturn(kits);
            var mail=mock(MailModule.class);when(mail.isEnabled()).thenReturn(true);when(modules.getModule("mail")).thenReturn(mail);
            var economy=mock(EconomyModule.class);when(economy.isEnabled()).thenReturn(true);when(modules.getModule("economy")).thenReturn(economy);
            var warps=mock(TravelModule.class);when(warps.isEnabled()).thenReturn(true);when(warps.travel()).thenReturn(travel);when(modules.getModule("travel")).thenReturn(warps);
            var rtp=mock(RtpModule.class);when(rtp.isEnabled()).thenReturn(true);when(modules.getModule("rtp")).thenReturn(rtp);
            service=new SignService(plugin);
        }
        String defaultArg(){return switch(type){
            case REPAIR->"hand";case GAMEMODE->"creative";case TIME->"day";case WEATHER->"sun";
            case FREE->"stone";case ENCHANT->"*|sharpness:1";case SPAWNMOB->"2|zombie";case RANDOMTELEPORT->"wild";
            default->"paid";
        };}
        String command(){return switch(type){
            case COLOR->"color";case CARTOGRAPHY->"cartographytable";case RANDOMTELEPORT->"rtp";default->type.key();
        };}
        String module(){return switch(type){case WARP->"travel";case KIT->"kits";case MAIL->"mail";case BALANCE->"economy";case RANDOMTELEPORT->"rtp";default->"essentials";};}
        PlayerInteractEvent click(){return new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,null,block,BlockFace.SOUTH,EquipmentSlot.HAND);}
        SignChangeEvent edit(){
            data.clear();
            String[] lines=switch(type){
                case ENCHANT->new String[]{"[enchant]","*","sharpness:1",""};
                case SPAWNMOB->new String[]{"[spawnmob]","2","zombie",""};
                default->new String[]{"["+type.key()+"]",defaultArg(),"",""};
            };
            var event=new SignChangeEvent(block,player,lines,Side.FRONT);
            var face=mock(SignSide.class);when(face.getLines()).thenAnswer(call->event.getLines());when(sign.getSide(Side.FRONT)).thenReturn(face);return event;
        }
        void drain(){while(!callbacks.isEmpty())callbacks.remove().run();}
        void noAction(){verifyNoInteractions(renderer,travel);verify(player,never()).openInventory(any(org.bukkit.inventory.Inventory.class));}
        void action(){
            if(type==SignIdentity.Type.COLOR){noAction();return;}
            if(type==SignIdentity.Type.FREE){verify(player).openInventory(freeInventory);return;}
            if(type==SignIdentity.Type.ENCHANT){verify(plugin.getMessageManager(),never()).send(eq(player),eq(codes.castled.allium.message.Messages.UTILITY_HOLD_ITEM));return;}
            if(type==SignIdentity.Type.SPAWNMOB){verify(plugin.getMessageManager()).send(eq(player),eq(codes.castled.allium.message.Messages.SPAWNMOB_SPAWNED),eq("{count}"),eq("2"),eq("{mob}"),eq("zombie"));return;}
            if(type==SignIdentity.Type.WARP)verify(travel).useSign(player,"paid",false);
            else verify(renderer).onCommand(eq(player),any(),eq(command()),aryEq(switch(type){
                case KIT->new String[]{"paid"};
                case MAIL->new String[]{"read"};
                case REPAIR->new String[]{"hand"};
                case GAMEMODE->new String[]{"creative"};
                case TIME->new String[]{"set","day"};
                case WEATHER->new String[]{"sun"};
                case RANDOMTELEPORT->new String[]{"wild"};
                default->new String[0];
            }));
        }
    }
    @ParameterizedTest @EnumSource(SignIdentity.Type.class)
    void permissionsFenceCreateUseAndBreak(SignIdentity.Type type){
        var f=new Fixture(type,false);when(f.player.hasPermission(anyString())).thenReturn(false);
        f.service.using(f.click());assertTrue(f.callbacks.isEmpty());f.noAction();
        var breaking=new BlockBreakEvent(f.block,f.player);f.service.breaking(breaking);assertTrue(breaking.isCancelled());
        var edit=f.edit();f.service.changing(edit);f.drain();assertTrue(edit.isCancelled());assertTrue(f.data.isEmpty());
    }
    @ParameterizedTest @EnumSource(SignIdentity.Type.class)
    void protectionCanCancelAfterAdmissionBeforeAnyEffect(SignIdentity.Type type){
        var f=new Fixture(type,true);var click=f.click();f.service.using(click);assertEquals(1,f.callbacks.size());click.setCancelled(true);f.drain();f.noAction();
        var edit=f.edit();f.service.changing(edit);edit.setCancelled(true);f.drain();assertTrue(f.data.isEmpty());
    }
    @ParameterizedTest @EnumSource(SignIdentity.Type.class)
    void waxedSignsRouteToTheSameService(SignIdentity.Type type){exercise(type,true);}
    @ParameterizedTest @EnumSource(SignIdentity.Type.class)
    void unwaxedSignsRouteToTheSameService(SignIdentity.Type type){exercise(type,false);}
    private void exercise(SignIdentity.Type type,boolean waxed){
        var f=new Fixture(type,waxed);f.service.using(f.click());assertEquals(1,f.callbacks.size());f.drain();f.action();
        var breaking=new BlockBreakEvent(f.block,f.player);f.service.breaking(breaking);assertFalse(breaking.isCancelled());
    }
    @ParameterizedTest @EnumSource(SignIdentity.Type.class)
    void disablingTypeCommandOrModulePreventsEffects(SignIdentity.Type type){
        var typeOff=new Fixture(type,true);typeOff.config.set("enabledSigns",List.of());new SignService(typeOff.plugin).using(typeOff.click());assertTrue(typeOff.callbacks.isEmpty());typeOff.noAction();
        var commandOff=new Fixture(type,true);when(commandOff.policy.isDisabled(commandOff.command())).thenReturn(true);commandOff.service.using(commandOff.click());commandOff.drain();commandOff.noAction();
        var moduleOff=new Fixture(type,true);moduleOff.config.set("modules."+moduleOff.module(),false);moduleOff.service.using(moduleOff.click());moduleOff.drain();moduleOff.noAction();
    }
    @org.junit.jupiter.api.Test void pricedKitSignStoresPriceOverrideAndRoutesToClaimFromSign(){
        var f=new Fixture(SignIdentity.Type.KIT,false);
        var kitsModule=(KitsModule)f.plugin.getModuleManager().getModule("kits");
        var kitService=mock(codes.castled.allium.kit.KitService.class);
        when(kitsModule.kits()).thenReturn(kitService);
        when(kitService.definitions()).thenReturn(Map.of("starter",
                new codes.castled.allium.kit.KitDefinition("starter",true,0,false,"100.00",List.of("diamond 1"),"starter.yml")));
        var edit=f.edit();
        edit.setLine(1,"starter");
        edit.setLine(2,"$400");
        f.service.changing(edit);
        f.drain();
        assertEquals("§1[Kit]",edit.getLine(0));
        assertEquals("$400",edit.getLine(2));
        var stored=SignIdentity.decode(f.data.values().iterator().next()).orElseThrow();
        assertTrue(stored.priceOverride());
        assertEquals(40_000,stored.price().minor());
        f.service.using(f.click());
        f.drain();
        verify(kitService).claimFromSign(f.player,"starter",new codes.castled.allium.economy.Money(40_000));
    }
    @org.junit.jupiter.api.Test void unsafeSignPricesRefusesDiscountedKitByDefaultAndAllowsWhenEnabled(){
        var f=new Fixture(SignIdentity.Type.KIT,false);
        var kitsModule=(KitsModule)f.plugin.getModuleManager().getModule("kits");
        var kitService=mock(codes.castled.allium.kit.KitService.class);
        when(kitsModule.kits()).thenReturn(kitService);
        when(kitService.definitions()).thenReturn(Map.of("vip",
                new codes.castled.allium.kit.KitDefinition("vip",true,0,false,"500.00",List.of("diamond 1"),"vip.yml")));
        var blocked=f.edit();
        blocked.setLine(1,"vip");
        blocked.setLine(2,"$250");
        f.service.changing(blocked);
        f.drain();
        assertEquals("§c[Kit]",blocked.getLine(0));
        assertEquals("§c<price>",blocked.getLine(2));
        assertTrue(f.data.isEmpty());
        verify(f.plugin.getMessageManager()).send(eq(f.player),eq(codes.castled.allium.message.Messages.SIGN_UNSAFE_PRICE),
                eq("{price}"),eq("$250"),eq("{cost}"),eq("$500"));

        f.config.set("unsafe-sign-prices",true);
        var allowedService=new SignService(f.plugin);
        var allowed=f.edit();
        allowed.setLine(1,"vip");
        allowed.setLine(2,"$0");
        allowedService.changing(allowed);
        f.drain();
        assertEquals("§1[Kit]",allowed.getLine(0));
        var stored=SignIdentity.decode(f.data.values().iterator().next()).orElseThrow();
        assertTrue(stored.priceOverride());
        assertEquals(0,stored.price().minor());
    }
    @org.junit.jupiter.api.Test void pricedPublicWarpAndHealSignsAcceptShorthandAndRouteThroughPaidSignService(){
        var f=new Fixture(SignIdentity.Type.WARP,false);
        var paidSigns=mock(PaidSignService.class);
        var service=new SignService(f.plugin,null,paidSigns);
        var edit=f.edit();
        edit.setLine(1,"market");
        edit.setLine(2,"public");
        edit.setLine(3,"$1k");
        service.changing(edit);
        f.drain();
        assertEquals("§1[Warp]",edit.getLine(0));
        assertEquals("$1K",edit.getLine(3));
        var stored=SignIdentity.decode(f.data.values().iterator().next()).orElseThrow();
        assertTrue(stored.publicWarp());
        assertEquals(100_000,stored.price().minor());
        when(f.travel.canUseSign(f.player,"market",true)).thenReturn(true);
        service.using(f.click());
        f.drain();
        verify(paidSigns).start(eq(f.player),eq(stored),any(Runnable.class));
    }

    @org.junit.jupiter.api.Test void spawnmobSignSpawnsTamedWolfOwnedByPlayerAndSupportsShortPrices(){
        var paidSigns=mock(PaidSignService.class);
        doAnswer(call->{call.<Runnable>getArgument(2).run();return null;}).when(paidSigns).start(any(),any(),any());
        var f=new Fixture(SignIdentity.Type.SPAWNMOB,false);
        var svc=new SignService(f.plugin,null,paidSigns);
        var edit=f.edit();
        edit.setLine(0,"[Spawnmob]");
        edit.setLine(1,"1");
        edit.setLine(2,"wolf:tamed");
        edit.setLine(3,"$1100");
        svc.changing(edit);
        f.drain();
        assertEquals("§1[Spawnmob]",edit.getLine(0));
        assertEquals("$1.1K",edit.getLine(3));
        var stored=SignIdentity.decode(f.data.values().iterator().next()).orElseThrow();
        assertEquals("1|wolf:tamed",stored.argument());
        assertEquals(110_000,stored.price().minor());

        // Survival player only needs allium.signs.use.spawnmob, NOT allium.spawnmob.wolf.
        when(f.player.hasPermission(anyString())).thenAnswer(inv->"allium.signs.use.spawnmob".equals(inv.getArgument(0)));
        var wolf=mock(org.bukkit.entity.Wolf.class);
        when(f.world.spawn(any(Location.class),eq(org.bukkit.entity.Wolf.class))).thenReturn(wolf);
        svc.using(f.click());
        f.drain();
        verify(paidSigns).start(eq(f.player),eq(stored),any());
        verify(wolf).setTamed(true);
        verify(wolf).setOwner(f.player);
        verify(f.plugin.getMessageManager()).send(eq(f.player),eq(codes.castled.allium.message.Messages.SPAWNMOB_SPAWNED),
                eq("{count}"),eq("1"),eq("{mob}"),eq("wolf"));
    }

    @org.junit.jupiter.api.Test void shortPriceFormattingRespectsConfigFlag(){
        var d=codes.castled.allium.economy.EconomyDisplay.DEFAULT;
        assertEquals("$100",d.formatSign(new codes.castled.allium.economy.Money(10_000),true));
        assertEquals("$999",d.formatSign(new codes.castled.allium.economy.Money(99_900),true));
        assertEquals("$1K",d.formatSign(new codes.castled.allium.economy.Money(100_000),true));
        assertEquals("$1.1K",d.formatSign(new codes.castled.allium.economy.Money(110_000),true));
        assertEquals("$1.25K",d.formatSign(new codes.castled.allium.economy.Money(125_000),true));
        assertEquals("$1M",d.formatSign(new codes.castled.allium.economy.Money(100_000_000),true));
        assertEquals("$1.5M",d.formatSign(new codes.castled.allium.economy.Money(150_000_000),true));
        assertEquals("$1,000",d.formatSign(new codes.castled.allium.economy.Money(100_000),false));
        assertEquals("$1,100",d.formatSign(new codes.castled.allium.economy.Money(110_000),false));
    }

    @org.junit.jupiter.api.Test void legacyEssentialsDarkBlueSignsAdoptIntoAlliumPdc(){
        var f=new Fixture(SignIdentity.Type.SPAWNMOB,false);
        f.data.clear();
        f.config.set("adopt-essentials-signs",true);
        var svc=new SignService(f.plugin);
        var front=mock(SignSide.class);
        when(front.getLines()).thenReturn(new String[]{"§1[Spawnmob]","1","wolf:tamed","$100"});
        when(f.sign.getSide(Side.FRONT)).thenReturn(front);
        assertTrue(svc.adoptEssentialsSign(f.sign,Side.FRONT));
        var adopted=SignIdentity.decode(f.data.get(new NamespacedKey("allium","sign_front"))).orElseThrow();
        assertEquals(SignIdentity.Type.SPAWNMOB,adopted.type());
        assertEquals("1|wolf:tamed",adopted.argument());
        assertEquals(10_000,adopted.price().minor());

        // Plain uncolored [Spawnmob] written by a player is never adopted.
        f.data.clear();
        when(front.getLines()).thenReturn(new String[]{"[Spawnmob]","1","wolf:tamed","$100"});
        assertFalse(svc.adoptEssentialsSign(f.sign,Side.FRONT));
        assertTrue(f.data.isEmpty());
    }
}
