package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Map;
import java.util.StringJoiner;

public final class ItemDbCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public ItemDbCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ITEMDB;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length > 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }
        ItemStack held = target.getInventory().getItemInMainHand();
        if (UtilitySupport.isAir(held)) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_HOLD_ITEM);
            return;
        }

        String name = UtilitySupport.formatMaterialName(held.getType());
        String key = held.getType().getKey().toString();
        UtilitySupport.send(plugin,context.sender(),target==context.sender()?Messages.ITEMDB_SELF:Messages.ITEMDB_OTHER,
                "{item}",name,"{key}",key,"{amount}",Integer.toString(held.getAmount()),"{max}",Integer.toString(held.getMaxStackSize()),"{player}",target.getName());

        var module = plugin.getModuleManager().getModule("items");
        if (module instanceof codes.castled.allium.module.ItemsModule items && items.isEnabled()) {
            UtilitySupport.send(plugin, context.sender(), Messages.ITEMDB_SHORT_NAMES,
                    "{names}", String.join(", ", items.itemShortNames(held.getType())));
        }

        short maxDurability = held.getType().getMaxDurability();
        var meta = held.getItemMeta();
        if (maxDurability > 0 && meta instanceof Damageable damageable) {
            int used = damageable.getDamage();
            int remaining = Math.max(0, maxDurability - used);
            UtilitySupport.send(plugin,context.sender(),Messages.ITEMDB_DURABILITY,"{remaining}",Integer.toString(remaining),"{max}",Short.toString(maxDurability),"{damage}",Integer.toString(used));
        }

        Map<Enchantment, Integer> enchants = meta instanceof EnchantmentStorageMeta stored
                ? stored.getStoredEnchants()
                : (meta == null ? Map.of() : meta.getEnchants());
        if (!enchants.isEmpty()) {
            StringJoiner joiner = new StringJoiner(", ");
            for (var entry : enchants.entrySet()) {
                joiner.add(entry.getKey().getKey().getKey() + ":" + entry.getValue());
            }
            UtilitySupport.send(plugin,context.sender(),Messages.ITEMDB_ENCHANTS,"{enchantments}",joiner.toString());
        }

        if (plugin.getItemService() != null) {
            var identified = plugin.getItemService().identify(held);
            if (identified.isPresent()) {
                var ref = identified.get();
                if (!ref.appearance().isVanilla() || ref.behaviorId().isPresent()) {
                    UtilitySupport.send(plugin,context.sender(),ref.behaviorId().isPresent()?Messages.ITEMDB_BEHAVIOR:Messages.ITEMDB_ID,"{id}",ref.appearance().asString(),"{behavior}",ref.behaviorId().orElse(""));
                }
            }
        }
    }
}
