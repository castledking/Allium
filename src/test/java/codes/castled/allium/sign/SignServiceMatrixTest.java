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
        final SignIdentity.Type type;
        final SignService service;
        Fixture(SignIdentity.Type type,boolean waxed){
            when(plugin.getMessageManager()).thenReturn(mock(codes.castled.allium.message.MessageManager.class));
            this.type=type;when(plugin.getName()).thenReturn("Allium");when(plugin.getConfig()).thenReturn(config);
            config.set("enabledSigns",Arrays.stream(SignIdentity.Type.values()).map(SignIdentity.Type::key).toList());
            config.set("sign-use-per-second",4);
            var scheduler=mock(TaskScheduler.class);when(plugin.getScheduler()).thenReturn(scheduler);
            doAnswer(call->{callbacks.add(call.getArgument(0));return codes.castled.allium.scheduler.TaskHandle.NONE;}).when(scheduler).runOnServerLater(any(Runnable.class),anyLong());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(player.hasPermission(anyString())).thenReturn(true);
            var world=mock(World.class);when(player.getEyeLocation()).thenReturn(new Location(world,.5,65,2.5));when(sign.getLocation()).thenReturn(new Location(world,0,64,0));
            var rotation=mock(Rotatable.class);when(rotation.getRotation()).thenReturn(BlockFace.SOUTH);when(sign.getBlockData()).thenReturn(rotation);
            when(block.getState()).thenReturn(sign);when(sign.isWaxed()).thenReturn(waxed);when(sign.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(call->data.get(call.getArgument(0)));
            doAnswer(call->{data.put(call.getArgument(0),call.getArgument(2));return null;}).when(pdc).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
            doAnswer(call->{data.remove(call.getArgument(0));return null;}).when(pdc).remove(any());
            data.put(new NamespacedKey("allium","sign_front"),new SignIdentity(UUID.randomUUID(),type,"paid",false).encode());
            when(plugin.getCommandManager()).thenReturn(commands);when(commands.getFamilyPolicy()).thenReturn(policy);
            when(commands.getRenderedCommands()).thenReturn(Map.of(command(),renderer));when(plugin.getCommand(command())).thenReturn(mock(PluginCommand.class));
            var modules=mock(ModuleManager.class);when(plugin.getModuleManager()).thenReturn(modules);
            var essentials=mock(EssentialsModule.class);when(essentials.isEnabled()).thenReturn(true);when(modules.getModule("essentials")).thenReturn(essentials);
            var kits=mock(KitsModule.class);when(kits.isEnabled()).thenReturn(true);when(modules.getModule("kits")).thenReturn(kits);
            var mail=mock(MailModule.class);when(mail.isEnabled()).thenReturn(true);when(modules.getModule("mail")).thenReturn(mail);
            var economy=mock(EconomyModule.class);when(economy.isEnabled()).thenReturn(true);when(modules.getModule("economy")).thenReturn(economy);
            var warps=mock(TravelModule.class);when(warps.isEnabled()).thenReturn(true);when(warps.travel()).thenReturn(travel);when(modules.getModule("travel")).thenReturn(warps);
            service=new SignService(plugin);
        }
        String command(){return type==SignIdentity.Type.COLOR?"color":type.key();}
        String module(){return switch(type){case WARP->"travel";case KIT->"kits";case MAIL->"mail";case BALANCE->"economy";default->"essentials";};}
        PlayerInteractEvent click(){return new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,null,block,BlockFace.SOUTH,EquipmentSlot.HAND);}
        SignChangeEvent edit(){data.clear();var event=new SignChangeEvent(block,player,new String[]{"["+type.key()+"]","paid","",""},Side.FRONT);var face=mock(SignSide.class);when(face.getLines()).thenAnswer(call->event.getLines());when(sign.getSide(Side.FRONT)).thenReturn(face);return event;}
        void drain(){while(!callbacks.isEmpty())callbacks.remove().run();}
        void noAction(){verifyNoInteractions(renderer,travel);}
        void action(){
            if(type==SignIdentity.Type.COLOR){noAction();return;}
            if(type==SignIdentity.Type.WARP)verify(travel).useSign(player,"paid",false);
            else verify(renderer).onCommand(eq(player),any(),eq(command()),aryEq(type==SignIdentity.Type.KIT?new String[]{"paid"}:type==SignIdentity.Type.MAIL?new String[]{"read"}:new String[0]));
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
}
