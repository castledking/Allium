package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.worth.SellCommand;
import codes.castled.allium.command.worth.SetWorthCommand;
import codes.castled.allium.command.worth.WorthCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.worth.SellService;
import codes.castled.allium.worth.WorthService;

/** Item prices and selling: {@code /worth} ({@code /price}), {@code /sell}, {@code /setworth} and {@code worth.yml}. */
public final class WorthModule extends AbstractModule {

    private WorthService worth;
    private SellService sell;

    public WorthModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "worth";
    }

    @Override
    public void onEnable() {
        worth = new WorthService(plugin);
        try {
            worth.enable();
        } catch (Exception invalid) {
            worth = null;
            throw new IllegalStateException("worth.yml could not be read", invalid);
        }
        sell = new SellService(plugin, worth);
        sell.enable();
        plugin.getCommandManager().register(new WorthCommand(plugin, worth, sell));
        plugin.getCommandManager().register(new SellCommand(plugin, worth, sell));
        plugin.getCommandManager().register(new SetWorthCommand(plugin, worth));
        plugin.logger().info("Worth enabled: " + worth.settings().prices().size() + " item prices, "
                + worth.settings().multipliers().size() + " multiplier(s), bulk sales "
                + (worth.settings().confirmBulkSeconds() > 0 ? "confirmed within " + worth.settings().confirmBulkSeconds() + "s." : "sold at once.")
                + (codes.castled.allium.economy.PaidWorkflowSupport.ready(plugin) ? "" : " The economy is not running, so /sell is unavailable."));
    }

    @Override
    public void onDisable() {
        if (sell != null) {
            sell.close();
            sell = null;
        }
        if (worth != null) {
            worth.disable();
            worth = null;
        }
    }

    public WorthService worth() {
        return worth;
    }

    public SellService sell() {
        return sell;
    }

    /** Re-reads {@code worth.yml}; false keeps the previous prices. */
    public boolean reloadSettings() {
        return worth == null || worth.reload();
    }
}
