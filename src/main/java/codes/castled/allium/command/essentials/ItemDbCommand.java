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
        context.sender().sendMessage("[Allium] Item: " + name + " (" + key + "), amount "
                + held.getAmount() + "/" + held.getMaxStackSize()
                + (target == context.sender() ? "" : " (held by " + target.getName() + ")"));

        short maxDurability = held.getType().getMaxDurability();
        var meta = held.getItemMeta();
        if (maxDurability > 0 && meta instanceof Damageable damageable) {
            int used = damageable.getDamage();
            int remaining = Math.max(0, maxDurability - used);
            context.sender().sendMessage("[Allium] Durability: " + remaining + "/" + maxDurability
                    + " (" + used + " damage)");
        }

        Map<Enchantment, Integer> enchants = meta instanceof EnchantmentStorageMeta stored
                ? stored.getStoredEnchants()
                : (meta == null ? Map.of() : meta.getEnchants());
        if (!enchants.isEmpty()) {
            StringJoiner joiner = new StringJoiner(", ");
            for (var entry : enchants.entrySet()) {
                joiner.add(entry.getKey().getKey().getKey() + ":" + entry.getValue());
            }
            context.sender().sendMessage("[Allium] Enchantments: " + joiner);
        }

        if (plugin.getItemService() != null) {
            var identified = plugin.getItemService().identify(held);
            if (identified.isPresent()) {
                var ref = identified.get();
                if (!ref.appearance().isVanilla() || ref.behaviorId().isPresent()) {
                    String behaviorSuffix = ref.behaviorId()
                            .map(behavior -> " (behavior=" + behavior + ")")
                            .orElse("");
                    context.sender().sendMessage("[Allium] Allium ID: " + ref.appearance().asString() + behaviorSuffix);
                }
            }
        }
    }
}
