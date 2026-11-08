package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonItems;
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
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** One connected addon: everything it registers or schedules is owned here and released on {@link #close}. */
final class AddonSession implements AddonConnection {

    private final PluginStart allium;
    private final Plugin addon;
    private final String namespace;
    private final AlliumScheduler scheduler;
    private final CommandFamilyPolicy commandPolicy = new CommandFamilyPolicy();
    private final List<String> definedItems = new ArrayList<>();
    private final List<PluginCommand> commands = new ArrayList<>();
    private volatile boolean closed;

    AddonSession(PluginStart allium, Plugin addon) {
        this.allium = allium;
        this.addon = addon;
        this.namespace = addon.getName().toLowerCase(Locale.ROOT);
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

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(addon.getName() + "'s Allium connection is closed (the addon was disabled)");
        }
    }

    private final AddonItems items = new AddonItems() {
        @Override
        public void define(ItemDefinition definition) {
            requireOpen();
            ItemService service = allium.getItemService();
            if (service.getAlliumItem(definition.id()).isPresent()) {
                throw new IllegalArgumentException("allium:" + definition.id() + " is already defined (by Allium or another addon)");
            }
            String name = definition.displayName() == null ? null : TextFormatter.parse(definition.displayName()).toLegacy();
            service.registerAlliumItem(new AlliumItemDefinition(definition.id(), definition.carrier(), namespace + ":" + definition.id(),
                    false, definition.customModelData(), name, definition.defaultState(), definition.signed()));
            definedItems.add(definition.id());
            allium.logger().info(addon.getName() + " defined allium:" + definition.id() + " (behavior " + namespace + ":"
                    + definition.id() + (definition.signed() ? ", signed" : "") + ").");
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
        public Optional<ItemIdentity> identify(@Nullable ItemStack stack) {
            return allium.getItemService().identify(stack)
                    .map(ref -> new ItemIdentity(ref.appearance().toString(), ref.behaviorId(), Map.copyOf(ref.stateData())));
        }
    };

    private final AddonCommands commandApi = new AddonCommands() {
        @Override
        public void register(CommandSpec spec, Handler handler) {
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
            command.setTabCompleter(renderer);
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
        definedItems.forEach(allium.getItemService()::unregisterAlliumItem);
        for (PluginCommand command : commands) {
            command.setExecutor(null);
            command.setTabCompleter(null);
        }
        allium.logger().info("Released " + addon.getName() + "'s Allium connection (" + definedItems.size() + " item(s), "
                + commands.size() + " command(s)).");
    }
}
