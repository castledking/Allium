package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.AddonPowerTools;
import codes.castled.allium.api.AddonText;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.ItemDefinition;
import codes.castled.allium.api.ItemIdentity;
import codes.castled.allium.api.ItemResult;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.module.ToolsModule;
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import codes.castled.allium.tool.powertool.PowerToolBehavior;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/** One connected addon: everything it registers or schedules is owned here and released on {@link #close}. */
final class AddonSession implements AddonConnection {

    private final PluginStart allium;
    private final Plugin addon;
    private final String namespace;
    private final AlliumScheduler scheduler;
    private final CommandFamilyPolicy commandPolicy = new CommandFamilyPolicy();
    private final List<String> definedItems = new ArrayList<>();
    private final List<PluginCommand> commands = new ArrayList<>();
    private final List<org.bukkit.NamespacedKey> recipes = new ArrayList<>();
    private final List<Runnable> powerToolCleanup = new ArrayList<>();
    private volatile boolean closed;
    private final codes.castled.allium.api.AddonEconomy economy;

    AddonSession(PluginStart allium, Plugin addon) {
        this.allium = allium;
        this.addon = addon;
        this.namespace = addon.getName().toLowerCase(Locale.ROOT);
        this.economy = codes.castled.allium.economy.AddonMoney.of(allium, addon.getName());
        this.scheduler = new AlliumScheduler(new BukkitServerThread(addon), message -> addon.getLogger().severe(message));
    }

    @Override
    public Plugin plugin() {
        return addon;
    }

    @Override
    public TaskScheduler scheduler() {
        return scheduler;
    }

    @Override
    public AddonItems items() {
        return items;
    }

    @Override
    public AddonCommands commands() {
        return commandApi;
    }

    @Override
    public AddonText text() {
        return text;
    }

    @Override
    public codes.castled.allium.api.AddonEconomy economy() {
        return economy;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(addon.getName() + "'s Allium connection is closed (the addon was disabled)");
        }
    }

    @Override
    public boolean canEditInventory(org.bukkit.entity.Player player) {
        requireOpen();
        return !codes.castled.allium.command.essentials.UtilitySupport.isInventoryBusy(allium, player);
    }

    @Override
    public boolean hiddenFrom(Player viewer, Player target) {
        requireOpen();
        if (allium.getModuleManager().getModule("moderation") instanceof codes.castled.allium.module.ModerationModule module
                && module.isEnabled() && module.moderation() != null) {
            return module.moderation().hiddenFrom(viewer, target);
        }
        return AddonConnection.super.hiddenFrom(viewer, target);
    }

    @Override
    public AddonPowerTools powerTools() {
        return powerTools;
    }

    private final AddonPowerTools powerTools = new AddonPowerTools() {
        private PowerToolBehavior runtime() {
            return allium.getModuleManager().getModule("tools") instanceof ToolsModule module
                    && module.isEnabled() ? module.powerTools() : null;
        }

        @Override
        public boolean available() {
            requireOpen();
            return runtime() != null;
        }

        @Override
        public void registerAction(String name, Action action) {
            requireOpen();
            if (!name.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid action name: " + name);
            var runtime = Objects.requireNonNull(runtime(), "Allium tools are disabled");
            Objects.requireNonNull(action, "action");
            String id = namespace + ":" + name;
            powerToolCleanup.add(runtime.actions().registerAddon(id, (ctx, spec) -> !closed
                    && action.run(new Context(ctx.player(), ctx.toolId(), ctx.item(), ctx.hand(), ctx.trigger().name(),
                    ctx.block(), ctx.targetEntity(), ctx.causeEvent()), spec.value(), spec.params())));
        }

        @Override
        public void registerTool(ConfigurationSection definition) {
            requireOpen();
            var runtime = Objects.requireNonNull(runtime(), "Allium tools are disabled");
            powerToolCleanup.add(runtime.registerAddonTool(addon.getName(), definition));
        }

        @Override
        public boolean mayModify(Player player, Block block, String action, @Nullable Event cause) {
            return AddonSession.this.mayModify(player, block, action, cause);
        }
    };

    @Override
    public boolean mayModify(Player player, Block block, String action, @Nullable Event cause) {
        requireOpen();
        return allium.getProtectionService().checkBlockBreak(player, block.getLocation(), namespace + ":" + action, cause).allowed();
    }

    private final AddonItems items = new AddonItems() {
        @Override
        public void define(ItemDefinition definition) {
            define(definition, false);
        }

        @Override
        public void defineStackableSigned(ItemDefinition definition) {
            define(definition, true);
        }

        @Override
        public void defineVariants(String id, java.util.Collection<String> names,
                                   java.util.function.BiConsumer<ItemStack, String> initialize) {
            requireOpen();
            if (!definedItems.contains(id)) throw new IllegalArgumentException("Define your item before its variants: " + id);
            allium.getItemService().registerVariants(id, names, initialize);
        }

        @Override
        public void decorate(String id, java.util.function.Consumer<ItemStack> decoration) {
            requireOwnedItem(id);
            allium.getItemService().decorate(id, decoration);
        }

        @Override
        public void registerRecipe(org.bukkit.inventory.Recipe recipe) {
            requireOpen();
            if (!(recipe instanceof org.bukkit.Keyed keyed) || !keyed.getKey().getNamespace().equals(namespace)) {
                throw new IllegalArgumentException("Addon recipes must be keyed in " + namespace);
            }
            if (!allium.getServer().addRecipe(recipe)) {
                throw new IllegalArgumentException("Recipe registration failed: " + keyed.getKey());
            }
            allium.getItemService().registerRecipe(keyed.getKey(), recipe);
            recipes.add(keyed.getKey());
        }

        @Override
        public void setWorth(String id, java.math.BigDecimal price) {
            requireOwnedItem(id);
            if (allium.getModuleManager().getModule("worth") instanceof codes.castled.allium.module.WorthModule module
                    && module.worth() != null) module.worth().setAddonWorth(namespace + ":" + id, price);
        }

        private void requireOwnedItem(String id) {
            requireOpen();
            if (!definedItems.contains(id)) throw new IllegalArgumentException("Define your item first: " + id);
        }

        private void define(ItemDefinition definition, boolean stackable) {
            requireOpen();
            ItemService service = allium.getItemService();
            if (service.getAlliumItem(definition.id()).isPresent()) {
                throw new IllegalArgumentException("allium:" + definition.id() + " is already defined (by Allium or another addon)");
            }
            String name = definition.displayName() == null ? null : TextFormatter.parse(definition.displayName()).toLegacy();
            var item = new AlliumItemDefinition(definition.id(), definition.carrier(), namespace + ":" + definition.id(),
                    stackable, definition.customModelData(), name, definition.defaultState(), definition.signed());
            if (stackable) service.registerStackableSignedItem(item);
            else service.registerAlliumItem(item);
            definedItems.add(definition.id());
            if (!stackable) {
                allium.logger().info(addon.getName() + " defined allium:" + definition.id() + " (behavior " + namespace + ":"
                        + definition.id() + (definition.signed() ? ", signed" : "") + ").");
            }
        }

        @Override
        public ItemResult create(String reference, int amount) {
            requireOpen();
            ItemService service = allium.getItemService();
            ItemRef ref;
            try {
                ref = service.parseRef(reference).withAmount(Math.max(1, amount));
            } catch (RuntimeException e) {
                return new ItemResult(ItemResult.Status.INVALID_REFERENCE, Optional.empty(), String.valueOf(e.getMessage()));
            }
            ResolutionResult result = service.create(ref);
            ItemResult.Status status = switch (result.status()) {
                case RESOLVED -> ItemResult.Status.CREATED;
                case UNKNOWN_ID -> ItemResult.Status.UNKNOWN_ID;
                case PROVIDER_ABSENT -> ItemResult.Status.PROVIDER_ABSENT;
                case PROVIDER_LOADING -> ItemResult.Status.PROVIDER_LOADING;
                case PROVIDER_FAILED -> ItemResult.Status.PROVIDER_FAILED;
                default -> ItemResult.Status.REJECTED;
            };
            Optional<ItemStack> stack = status == ItemResult.Status.CREATED ? result.itemStack() : Optional.empty();
            return new ItemResult(stack.isPresent() ? status : (status == ItemResult.Status.CREATED ? ItemResult.Status.REJECTED : status),
                    stack, String.valueOf(result.diagnosticMessage()));
        }

        @Override
        public boolean updateState(ItemStack stack, Map<String, String> changes) {
            requireOpen();
            ItemService service = allium.getItemService();
            return service.identifyBehavior(stack)
                    .filter(id -> id.startsWith(namespace + ":"))
                    .map(id -> definedItems.contains(id.substring(namespace.length() + 1)))
                    .orElse(false) && service.updateState(stack, changes);
        }

        @Override
        public Optional<ItemIdentity> identify(@Nullable ItemStack stack) {
            return allium.getItemService().identify(stack)
                    .map(ref -> new ItemIdentity(ref.appearance().toString(), ref.behaviorId(), Map.copyOf(ref.stateData())));
        }
    };

    private final AddonCommands commandApi = new AddonCommands() {
        @Override
        public void register(CommandSpec spec, Handler handler) {
            register(spec, handler, null);
        }

        @Override
        public void register(CommandSpec spec, Handler handler, @Nullable Completer completer) {
            requireOpen();
            if (!namespace.equals(spec.namespace())) {
                throw new IllegalArgumentException("command /" + spec.canonicalName() + " must use namespace '" + namespace
                        + "' (the prefix Bukkit gives " + addon.getName() + "'s commands), not '" + spec.namespace() + "'");
            }
            PluginCommand command = addon instanceof JavaPlugin java ? java.getCommand(spec.canonicalName()) : null;
            if (command == null || command.getPlugin() != addon) {
                throw new IllegalArgumentException("declare /" + spec.canonicalName() + " under commands: in " + addon.getName()
                        + "'s plugin.yml; Allium runs it, but Bukkit registers it");
            }
            commandPolicy.registerSpec(spec);
            BukkitCommandRenderer renderer = new BukkitCommandRenderer(allium, commandPolicy, new SpecCommandHandler() {
                @Override
                public CommandSpec spec() {
                    return spec;
                }

                @Override
                public void execute(BukkitCommandContext context) {
                    handler.execute(new Context() {
                        @Override public CommandSender sender() { return context.sender(); }
                        @Override public String label() { return context.label(); }
                        @Override public String[] args() { return context.args(); }
                        @Override public CommandSpec spec() { return spec; }
                    });
                }
            });
            command.setExecutor(renderer);
            if (completer == null) {
                command.setTabCompleter(renderer);
            } else {
                command.setTabCompleter((sender, cmd, alias, args) -> {
                    if (closed || commandPolicy.isDisabled(spec.canonicalName()) || commandPolicy.isDisabled(alias)) {
                        return List.of();
                    }
                    String basePerm = spec.basePermission();
                    if (basePerm != null && !basePerm.isEmpty() && !sender.hasPermission(basePerm)) {
                        return List.of();
                    }
                    List<String> out = completer.complete(new Context() {
                        @Override public CommandSender sender() { return sender; }
                        @Override public String label() { return alias; }
                        @Override public String[] args() { return args; }
                        @Override public CommandSpec spec() { return spec; }
                    });
                    return out == null ? List.of() : out;
                });
            }
            commands.add(command);
            allium.logger().info(addon.getName() + " registered /" + spec.canonicalName() + " through Allium's command spec.");
        }
    };

    private final AddonText text = new AddonText() {
        @Override
        public void send(CommandSender recipient, String message) {
            TextSender.send(recipient, message);
        }

        @Override
        public void send(CommandSender recipient, String message, Map<String, String> placeholders) {
            String out = message;
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
            TextSender.send(recipient, out);
        }

        @Override
        public String legacy(String message) {
            return TextFormatter.parse(message).toLegacy();
        }
    };

    /** Releases everything the addon registered: scheduled work, item definitions and command executors. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<String> busy = scheduler.close(2_000L);
        if (!busy.isEmpty()) {
            allium.logger().warning(addon.getName() + "'s worker pools were still busy when it disabled: " + busy);
        }
        for (int i = powerToolCleanup.size() - 1; i >= 0; i--) powerToolCleanup.get(i).run();
        powerToolCleanup.clear();
        if (allium.getModuleManager().getModule("worth") instanceof codes.castled.allium.module.WorthModule module
                && module.worth() != null) {
            definedItems.forEach(id -> module.worth().removeAddonWorth(namespace + ":" + id));
        }
        for (org.bukkit.NamespacedKey recipe : recipes) {
            allium.getItemService().removeRecipe(recipe);
            allium.getServer().removeRecipe(recipe);
        }
        recipes.clear();
        definedItems.forEach(allium.getItemService()::unregisterAlliumItem);
        for (PluginCommand command : commands) {
            command.setExecutor(null);
            command.setTabCompleter(null);
        }
        allium.logger().info("Released " + addon.getName() + "'s Allium connection (" + definedItems.size() + " item(s), "
                + commands.size() + " command(s)).");
    }
}
