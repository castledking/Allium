package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.MenuData;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.module.ToolsModule;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.module.WorthModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record CoreMenuData(PluginStart plugin) implements MenuData {
    @Override public Travel travel(Player player, boolean warps) {
        if (plugin.getModuleManager().getModule("travel") instanceof TravelModule module && module.isEnabled()) {
            return module.travel().menuEntries(player, warps);
        }
        return new Travel(List.of(), List.of(), 0, false, false, false);
    }

    @Override public List<Target> onlinePlayers(Player viewer) {
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> player != viewer && PlayerNameVisibility.canSee(viewer, player))
                .map(player -> new Target(player.getUniqueId(), player.getName()))
                .sorted(Comparator.comparing(Target::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override public List<Tool> powerTools(Player viewer) {
        if (!canRun(viewer, "powertool")) return List.of();
        if (plugin.getModuleManager().getModule("tools") instanceof ToolsModule module && module.isEnabled()
                && module.powerTools() != null) {
            var entries = new java.util.TreeMap<String, Boolean>();
            for (var tool : module.powerTools().listAllToolFiles()) entries.put(tool.localId(), tool.enabled());
            for (String id : module.powerTools().registryLocalIds()) entries.putIfAbsent(id, true);
            for (String id : module.enabledToolLocalIds()) entries.putIfAbsent(id, true);
            return entries.entrySet().stream().map(tool -> new Tool(tool.getKey(), tool.getValue())).toList();
        }
        return List.of();
    }

    @Override public boolean sellable(ItemStack stack) {
        return sellPrice(stack).isPresent();
    }

    @Override public Optional<Double> sellPrice(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Optional.empty();
        if (plugin.getModuleManager().getModule("worth") instanceof WorthModule module && module.isEnabled()
                && module.worth() != null && module.worth().refusal(stack) == null) {
            return module.worth().price(stack).filter(price -> price.signum() > 0).map(java.math.BigDecimal::doubleValue);
        }
        return Optional.empty();
    }

    @Override public void sell(Player player, ItemStack captured) {
        if (!canRun(player, "sell")) return;
        if (plugin.getModuleManager().getModule("worth") instanceof WorthModule module && module.isEnabled()
                && module.sell() != null) {
            module.sell().sell(player, codes.castled.allium.worth.SalePlan.Scope.ITEM,
                    captured, codes.castled.allium.worth.SaleQuantity.ALL, "sell menu", false);
        }
    }

    @Override public void sellQuoted(Player player, ItemStack sample, double price) {
        if (!canRun(player, "sell") || !Double.isFinite(price) || price <= 0 || price > 1_000_000) return;
        if (plugin.getModuleManager().getModule("worth") instanceof WorthModule module && module.isEnabled() && module.sell() != null) {
            module.sell().sellQuoted(player, sample, java.math.BigDecimal.valueOf(price));
        }
    }

    @Override public Optional<Boolean> toggle(Player player, String id) {
        if (plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule module && module.isEnabled()) {
            return module.effective(player.getUniqueId()).map(value -> switch (id) {
                case "tpa" -> value.acceptTeleportRequests();
                case "msg" -> value.acceptMessages();
                case "pay" -> value.acceptPayments();
                default -> null;
            });
        }
        return Optional.empty();
    }

    @Override public boolean canRun(Player player, String name) {
        var command = plugin.getServer().getPluginCommand("allium:" + name);
        return command != null && command.getPlugin() == plugin && command.testPermissionSilent(player)
                && !plugin.getCommandManager().getFamilyPolicy().isDisabled(name);
    }
}
