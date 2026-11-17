package codes.castled.allium.command;
import codes.castled.allium.PluginStart;
import codes.castled.allium.command.items.*;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.ItemsModule;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ItemCompletionTest {
 @Test void selfCanonicalIAndItemAliasUseTheItemIndex(){var plugin=mock(PluginStart.class);var modules=mock(ModuleManager.class);var items=mock(ItemsModule.class);var sender=mock(Player.class);when(plugin.getModuleManager()).thenReturn(modules);when(modules.getModule("items")).thenReturn(items);when(items.isEnabled()).thenReturn(true);when(items.complete("dsw")).thenReturn(List.of("dsword"));when(sender.hasPermission("allium.give")).thenReturn(true);var policy=new CommandFamilyPolicy();policy.registerSpec(CoreCommandSpecs.ITEM);var renderer=new BukkitCommandRenderer(plugin,policy,new ItemSelfCommand(plugin,mock(GiveCommand.class)));for(String alias:List.of("i","item","allium:i"))assertEquals(List.of("dsword"),renderer.onTabComplete(sender,mock(Command.class),alias,new String[]{"dsw"}));}
}
