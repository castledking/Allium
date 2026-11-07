package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.tool.disarmer.MobDisarmerListener;
import codes.castled.allium.tool.disarmer.MobDisarmerService;
import codes.castled.allium.tool.disarmer.MobDisarmerSettings;
import org.bukkit.event.HandlerList;

import java.util.Random;

/**
 * Staple tools. S06a provides the Mob Disarmer behavior; its settings are validated at enable.
 */
public final class ToolsModule extends AbstractModule {

    private MobDisarmerListener disarmerListener;
    private MobDisarmerService disarmer;

    public ToolsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "tools";
    }

    @Override
    public void onEnable() {
        MobDisarmerSettings settings = MobDisarmerSettings.parse(plugin.getConfig().getConfigurationSection("tools.mob-disarmer"));
        settings.warnings().forEach(plugin.logger()::warning);
        if (!settings.enabled()) {
            plugin.logger().info("Mob Disarmer is disabled (tools.mob-disarmer.enabled: false).");
            return;
        }
        disarmer = new MobDisarmerService(
                plugin.getItemService(),
                plugin.getProtectionService(),
                plugin.getMessageManager()::send,
                plugin.logger()::warning,
                System::currentTimeMillis,
                System::nanoTime,
                new Random(),
                settings);
        disarmerListener = new MobDisarmerListener(disarmer);
        plugin.getServer().getPluginManager().registerEvents(disarmerListener, plugin);
        plugin.logger().info("Mob Disarmer enabled: appearance " + settings.appearance()
                + ", " + settings.rules().maxCharges() + " charges, recharge "
                + settings.rules().rechargeMillis() / 1000L + "s, protection hooks "
                + plugin.getProtectionService().hookNames() + " + check event.");
    }

    @Override
    public void onDisable() {
        if (disarmerListener != null) {
            HandlerList.unregisterAll(disarmerListener);
            disarmerListener = null;
        }
        disarmer = null;
    }

    public MobDisarmerService mobDisarmer() {
        return disarmer;
    }
}
