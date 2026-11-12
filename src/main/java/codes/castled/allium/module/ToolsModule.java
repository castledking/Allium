package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.pack.AssetManifest;
import codes.castled.allium.pack.CoreItems;
import codes.castled.allium.tool.BukkitToolEnvironment;
import codes.castled.allium.tool.ToolDefinitionLoader;
import codes.castled.allium.tool.definition.BuiltInItemSpec;
import codes.castled.allium.tool.definition.PermissionDefault;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolDefinitionParser;
import codes.castled.allium.tool.disarmer.DisarmBehavior;
import codes.castled.allium.tool.disarmer.DisarmBehaviorType;
import codes.castled.allium.tool.disarmer.DisarmListener;
import codes.castled.allium.tool.disarmer.DisarmParameters;
import codes.castled.allium.tool.disarmer.DisarmTool;
import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerVisualState;
import codes.castled.allium.tool.obliterator.ObliterateBehavior;
import codes.castled.allium.tool.obliterator.ObliterateBehaviorType;
import codes.castled.allium.tool.obliterator.ObliterateListener;
import codes.castled.allium.tool.obliterator.ObliterateParameters;
import codes.castled.allium.tool.obliterator.ObliterateTool;
import codes.castled.allium.tool.ToolCarrierSuppressionListener;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staple tools, defined by {@code plugins/Allium/tools/*.yml}. Each enabled definition gets its
 * identity's bindings, its built-in item if it declares one, its permission nodes and, for core
 * pack items, its charge-state models; then one listener per behavior serves every definition
 * bound to it. Definitions load at enable only, so changes need a restart.
 */
public final class ToolsModule extends AbstractModule {

    private static final Map<String, ToolBehaviorType<?>> BEHAVIORS = Map.of(
            DisarmBehaviorType.ID, new DisarmBehaviorType(),
            ObliterateBehaviorType.ID, new ObliterateBehaviorType());

    /** The default definitions the jar ships, each installed once (tools/.defaults). */
    static final List<String> BUNDLED_DEFAULTS = List.of("mob_disarmer.yml", "phantom_obliterator.yml");

    private final List<String> registeredItems = new ArrayList<>();
    private final List<String> registeredPermissions = new ArrayList<>();
    private final List<String> modelSelectors = new ArrayList<>();
    private DisarmListener disarmListener;
    private DisarmBehavior disarm;
    private ObliterateListener obliterateListener;
    private ObliterateBehavior obliterate;
    private ToolCarrierSuppressionListener carrierSuppression;
    private ToolDefinitionLoader.Loaded loaded;

    public ToolsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "tools";
    }

    @Override
    public void onEnable() {
        ToolDefinitionLoader loader = new ToolDefinitionLoader(plugin.getDataFolder(), plugin::getResource,
                BUNDLED_DEFAULTS, plugin.logger()::info, plugin.logger()::warning);
        try {
            loader.install(plugin.getConfig().getConfigurationSection("tools.mob-disarmer"));
        } catch (IOException e) {
            plugin.logger().warning("Could not create " + ToolDefinitionLoader.DIRECTORY + "/: " + e.getMessage());
        }
        ItemService items = plugin.getItemService();
        carrierSuppression = new ToolCarrierSuppressionListener(plugin, items);
        carrierSuppression.enable();
        Set<String> coreItems = CoreItems.definitions(plugin.getPackManifest()).stream()
                .map(AlliumItemDefinition::id).collect(Collectors.toSet());
        BukkitToolEnvironment environment = new BukkitToolEnvironment(coreItems, plugin.getLanguageManager()::hasKey);
        loaded = loader.load(BEHAVIORS, environment, items::resolveBoundBehaviorId);

        for (ToolDefinitionParser.Result rejected : loaded.rejected()) {
            plugin.logger().warning("Tool definition " + rejected.file() + " is invalid and was not loaded:");
            rejected.diagnostics().forEach(line -> plugin.logger().warning("  " + line));
        }
        for (ToolDefinition definition : loaded.definitions()) {
            if (!definition.enabled()) {
                plugin.logger().info(definition.name() + " is disabled (" + definition.file() + ": enabled: false).");
            }
        }

        List<DisarmTool> disarmTools = new ArrayList<>();
        List<ObliterateTool> obliterateTools = new ArrayList<>();
        for (ToolDefinition definition : loaded.enabled()) {
            register(items, definition);
            if (DisarmBehaviorType.ID.equals(definition.behavior())) {
                disarmTools.add(new DisarmTool(definition, System::nanoTime));
            } else if (ObliterateBehaviorType.ID.equals(definition.behavior())) {
                obliterateTools.add(new ObliterateTool(definition, System::nanoTime));
            }
        }
        enableObliterate(items, obliterateTools);
        if (disarmTools.isEmpty()) {
            plugin.logger().info("No " + DisarmBehaviorType.ID + " tool is enabled.");
            return;
        }
        disarm = new DisarmBehavior(items, plugin.getProtectionService(), plugin.getMessageManager()::send,
                plugin.logger()::warning, System::currentTimeMillis, new Random(), disarmTools);
        disarmListener = new DisarmListener(disarm);
        plugin.getServer().getPluginManager().registerEvents(disarmListener, plugin);
        for (DisarmTool tool : disarmTools) {
            DisarmParameters parameters = tool.parameters();
            plugin.logger().info(tool.name() + " enabled (" + tool.definition().file() + ", " + tool.id() + " -> "
                    + DisarmBehaviorType.ID + "): appearance " + tool.definition().appearance() + ", "
                    + parameters.rules().maxCharges() + " charges, recharge " + parameters.rules().rechargeMillis() / 1000L
                    + "s, radius " + parameters.radius() + ", protection hooks " + plugin.getProtectionService().hookNames()
                    + " + check event.");
        }
    }

    private void enableObliterate(ItemService items, List<ObliterateTool> tools) {
        if (tools.isEmpty()) {
            return;
        }
        obliterate = new ObliterateBehavior(items, plugin.getProtectionService(), plugin.getMessageManager()::send,
                plugin.logger()::warning, System::currentTimeMillis, new Random(), tools);
        obliterateListener = new ObliterateListener(obliterate);
        plugin.getServer().getPluginManager().registerEvents(obliterateListener, plugin);
        for (ObliterateTool tool : tools) {
            ObliterateParameters parameters = tool.parameters();
            plugin.logger().info(tool.name() + " enabled (" + tool.definition().file() + ", " + tool.id() + " -> "
                    + ObliterateBehaviorType.ID + "): appearance " + tool.definition().appearance() + ", "
                    + parameters.rules().maxCharges() + " charges, recharge " + parameters.rules().rechargeMillis() / 1000L
                    + "s, radius " + parameters.radius() + ", targets " + parameters.entityTypes()
                    + (parameters.attributedKill() ? ", kills as the player" : ", silent kills") + ".");
        }
    }

    private void register(ItemService items, ToolDefinition definition) {
        BuiltInItemSpec item = definition.item();
        if (item != null) {
            Material material = Material.matchMaterial(item.material());
            items.registerAlliumItem(new AlliumItemDefinition(definition.localId(), material, definition.id(), false,
                    item.customModelData(), item.name(), Map.of(), true));
            registeredItems.add(definition.localId());
        }
        items.registerSignedBehavior(definition.id());
        items.setPreferredAppearanceForBehavior(definition.id(), definition.appearance());
        for (ProviderKey key : definition.boundAppearances()) {
            items.bindAppearanceToBehavior(key, definition.id());
        }
        registerPermissions(definition);
        if (DisarmBehaviorType.ID.equals(definition.behavior())) {
            installDisarmVisuals(items, definition);
        }
    }

    /** Declares the definition's nodes that no plugin.yml declares, with the slot's default holders. */
    private void registerPermissions(ToolDefinition definition) {
        PluginManager manager = plugin.getServer().getPluginManager();
        ToolBehaviorType<?> type = BEHAVIORS.get(definition.behavior());
        for (Map.Entry<String, PermissionDefault> slot : type.permissionSlots().entrySet()) {
            String node = definition.permission(slot.getKey());
            if (manager.getPermission(node) != null) {
                continue;
            }
            manager.addPermission(new Permission(node, definition.name() + " (" + slot.getKey() + ")",
                    slot.getValue() == PermissionDefault.EVERYONE
                            ? org.bukkit.permissions.PermissionDefault.TRUE
                            : org.bukkit.permissions.PermissionDefault.OP));
            registeredPermissions.add(node);
        }
    }

    /**
     * A core pack tool (the built-in Mob Disarmer) shows its charge state by model selection from
     * the pack manifest, scaled to its own definition's charges.
     */
    private void installDisarmVisuals(ItemService items, ToolDefinition definition) {
        if (definition.item() != null) {
            return;
        }
        Optional<AssetManifest.Tool> tool = plugin.getPackManifest().tool(definition.localId());
        if (tool.isEmpty()) {
            return; // not a core pack tool: a provider-only definition has no built-in item to restyle
        }
        if (tool.get().states().isEmpty()) {
            plugin.logger().warning("The core pack manifest has no " + definition.localId() + " visuals; built-in "
                    + definition.name() + "s keep one model.");
            return;
        }
        Map<String, Integer> byState = new HashMap<>();
        tool.get().states().forEach(state -> byState.put(state.key(), state.customModelData()));
        int max = definition.parameters(DisarmParameters.class).rules().maxCharges();
        items.setModelSelector(definition.localId(),
                state -> byState.get(DisarmerVisualState.of(DisarmerCharges.fromState(state, max), max).key()));
        modelSelectors.add(definition.localId());
    }

    @Override
    public void onDisable() {
        ItemService items = plugin.getItemService();
        modelSelectors.forEach(id -> items.setModelSelector(id, null));
        modelSelectors.clear();
        registeredItems.forEach(items::unregisterAlliumItem);
        registeredItems.clear();
        PluginManager manager = plugin.getServer().getPluginManager();
        registeredPermissions.forEach(manager::removePermission);
        registeredPermissions.clear();
        if (carrierSuppression != null) {
            carrierSuppression.disable();
            carrierSuppression = null;
        }
        if (disarmListener != null) {
            HandlerList.unregisterAll(disarmListener);
            disarmListener = null;
        }
        if (obliterateListener != null) {
            HandlerList.unregisterAll(obliterateListener);
            obliterateListener = null;
        }
        disarm = null;
        obliterate = null;
        loaded = null;
    }

    /** The obliterate behavior, or {@code null} when no Phantom Obliterator is enabled. */
    public ObliterateBehavior obliterate() {
        return obliterate;
    }

    /** The disarm behavior, or {@code null} when no disarm tool is enabled. */
    public DisarmBehavior disarm() {
        return disarm;
    }

    /** What the last enable loaded, for diagnostics. */
    public Map<String, List<String>> loadReport() {
        Map<String, List<String>> report = new LinkedHashMap<>();
        if (loaded != null) {
            loaded.definitions().forEach(d -> report.put(d.file(), List.of(d.enabled() ? "enabled " + d.id() : "disabled " + d.id())));
            loaded.rejected().forEach(r -> report.put(r.file(), r.diagnostics()));
        }
        return report;
    }
}
