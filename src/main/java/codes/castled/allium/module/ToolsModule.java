package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.pack.AssetManifest;
import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerVisualState;
import codes.castled.allium.tool.disarmer.MobDisarmerListener;
import codes.castled.allium.tool.disarmer.MobDisarmerService;
import codes.castled.allium.tool.disarmer.MobDisarmerSettings;
import org.bukkit.event.HandlerList;

import java.util.HashMap;
import java.util.Map;
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
        installDisarmerVisuals(settings.rules().maxCharges());
        disarmerListener = new MobDisarmerListener(disarmer);
        plugin.getServer().getPluginManager().registerEvents(disarmerListener, plugin);
        plugin.logger().info("Mob Disarmer enabled: appearance " + settings.appearance()
                + ", " + settings.rules().maxCharges() + " charges, recharge "
                + settings.rules().rechargeMillis() / 1000L + "s, protection hooks "
                + plugin.getProtectionService().hookNames() + " + check event.");
    }

    /** The built-in disarmer shows its charge state by model selection from the core pack manifest. */
    private void installDisarmerVisuals(int maxCharges) {
        AssetManifest.Tool tool = plugin.getPackManifest().tool("mob_disarmer").orElse(null);
        if (tool == null) {
            plugin.logger().warning("The core pack manifest has no mob_disarmer visuals; built-in disarmers keep one model.");
            return;
        }
        Map<String, Integer> byState = new HashMap<>();
        tool.states().forEach(state -> byState.put(state.key(), state.customModelData()));
        plugin.getItemService().setModelSelector("mob_disarmer",
                state -> byState.get(DisarmerVisualState.of(DisarmerCharges.fromState(state, maxCharges), maxCharges).key()));
    }

    @Override
    public void onDisable() {
        plugin.getItemService().setModelSelector("mob_disarmer", null);
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
