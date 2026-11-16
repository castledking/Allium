package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.essentials.EnchantArgParser;
import codes.castled.allium.item.library.EnchantmentAdapter;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Optional;
import java.util.function.Function;

public final class EnchantCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Function<String, Optional<Enchantment>> enchantFinder;

    public EnchantCommand(PluginStart plugin) {
        this(plugin, EnchantmentAdapter::find);
    }

    EnchantCommand(PluginStart plugin, Function<String, Optional<Enchantment>> enchantFinder) {
        this.plugin = plugin;
        this.enchantFinder = enchantFinder;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ENCHANT;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        var parsedOpt = EnchantArgParser.parse(
                args,
                tok -> enchantFinder.apply(tok).isPresent(),
                tok -> plugin.getServer() != null && isVisiblePlayer(context, tok)
        );
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(context.sender() instanceof Player
                    ? spec().selfUsage()
                    : spec().consoleUsage());
            return;
        }
        var parsed = parsedOpt.get();

        Player target;
        if (parsed.targetName() != null) {
            if (!context.sender().hasPermission("allium.enchant.others")) {
                UtilitySupport.send(plugin, context.sender(), Messages.NO_PERMISSION,
                        "{cmd}", "enchant for others");
                return;
            }
            target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, context.sender(), parsed.targetName());
            if (target == null) {
                return;
            }
        } else {
            if (!(context.sender() instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
        }

        Optional<Enchantment> enchantmentOpt = enchantFinder.apply(parsed.enchantmentToken());
        if (enchantmentOpt.isEmpty()) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_ENCHANT_UNKNOWN,
                    "{enchant}", parsed.enchantmentToken());
            return;
        }
        Enchantment enchantment = enchantmentOpt.get();

        if (!UtilitySupport.guardInventoryNotBusy(plugin, context.sender(), target)) {
            return;
        }

        ItemStack held = target.getInventory().getItemInMainHand();
        if (UtilitySupport.isAir(held) || held.getItemMeta() == null) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_HOLD_ITEM);
            return;
        }
        var meta = held.getItemMeta();
        String enchantName = enchantment.getKey().getKey();
        String itemName = UtilitySupport.formatMaterialName(held.getType());

        if (parsed.level() == 0) {
            if (meta instanceof EnchantmentStorageMeta book) {
                book.removeStoredEnchant(enchantment);
            } else {
                meta.removeEnchant(enchantment);
            }
            held.setItemMeta(meta);
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_ENCHANT_REMOVED,
                    "{enchant}", enchantName,
                    "{player}", target.getName(),
                    "{item}", itemName);
            if (target != context.sender()) {
                UtilitySupport.audit(plugin, context.sender(), "enchant_remove",
                        "target=" + target.getName() + " enchant=" + enchantName + " item=" + itemName);
            }
            return;
        }

        boolean incompatible = !(meta instanceof EnchantmentStorageMeta) && !enchantment.canEnchantItem(held);
        boolean aboveMax = parsed.level() > enchantment.getMaxLevel();
        boolean unsafe = incompatible || aboveMax;
        if (unsafe && !context.sender().hasPermission("allium.enchant.unsafe")) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_ENCHANT_UNSAFE_DENIED);
            return;
        }

        if (meta instanceof EnchantmentStorageMeta book) {
            book.addStoredEnchant(enchantment, parsed.level(), true);
        } else {
            meta.addEnchant(enchantment, parsed.level(), true);
        }
        held.setItemMeta(meta);

        UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_ENCHANT_APPLIED,
                "{enchant}", enchantName,
                "{level}", Integer.toString(parsed.level()),
                "{player}", target.getName(),
                "{item}", itemName);
        if (unsafe || target != context.sender()) {
            UtilitySupport.audit(plugin, context.sender(), "enchant",
                    "target=" + target.getName() + " enchant=" + enchantName
                            + " level=" + parsed.level() + " unsafe=" + unsafe + " item=" + itemName);
        }
    }

    private boolean isVisiblePlayer(BukkitCommandContext context, String name) {
        Player candidate = plugin.getServer().getPlayer(name);
        return candidate != null && PlayerNameVisibility.canSeeTarget(context.sender(), candidate);
    }
}
