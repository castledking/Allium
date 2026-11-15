package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.*;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.module.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.meta.Damageable;

/** Shared command/sign entry point for self-service inventory utilities. */
public record UtilityCommand(PluginStart plugin,CommandSpec spec) implements SpecCommandHandler {
    @Override public void execute(BukkitCommandContext context){
        if(!(context.sender() instanceof Player player)||context.args().length!=0)return;
        if(plugin.getModuleManager().getModule("kits") instanceof KitsModule kits&&kits.isEnabled()&&kits.kits().busy(player)
            ||plugin.getModuleManager().getModule("mail") instanceof MailModule mail&&mail.isEnabled()&&mail.mail().busy(player)){
            player.sendMessage("[Allium] Your inventory is busy with receipt recovery.");return;
        }
        switch(spec.canonicalName()){
            case "repair"->{
                var item=player.getInventory().getItemInMainHand();var meta=item.getItemMeta();
                if(meta instanceof Damageable damageable&&damageable.hasDamage()){damageable.setDamage(0);item.setItemMeta(meta);player.sendMessage("[Allium] Repaired the held item.");}
                else player.sendMessage("[Allium] Hold a damaged item to repair.");
            }
            case "disposal"->player.openInventory(Bukkit.createInventory(null,27,"Disposal"));
            case "workbench"->player.openWorkbench(null,true);
            case "anvil"->player.openInventory(Bukkit.createInventory(null,InventoryType.ANVIL,"Anvil"));
            case "info"->player.sendMessage("[Allium] "+plugin.getServer().getName()+" — "+plugin.getServer().getOnlinePlayers().stream().filter(target->codes.castled.allium.naming.PlayerNameVisibility.canSee(player,target)).count()+" player(s) online.");
            default->throw new IllegalArgumentException("unknown utility");
        }
    }
}
