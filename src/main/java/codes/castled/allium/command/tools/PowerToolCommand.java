package codes.castled.allium.command.tools;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ToolsModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolBehavior;
import codes.castled.allium.tool.powertool.PowerToolBehaviorType;
import codes.castled.allium.tool.powertool.PowerToolBinding;
import codes.castled.allium.tool.powertool.PowerToolCodec;
import codes.castled.allium.tool.powertool.PowerToolCooldownScope;
import codes.castled.allium.tool.powertool.PowerToolDurations;
import codes.castled.allium.tool.powertool.PowerToolParameters;
import codes.castled.allium.tool.powertool.PowerToolSecurity;
import codes.castled.allium.tool.powertool.PowerToolSettings;
import codes.castled.allium.tool.powertool.PowerToolTrigger;
import codes.castled.allium.tool.powertool.PowerToolUses;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * {@code /pt} ({@code /powertool}): creates, edits, inspects, saves ({@code tools/<name>.yml}),
 * binds, and gives interactive Allium powertools.
 */
public final class PowerToolCommand implements SpecCommandHandler {

    public static final String PERM_BASE = "allium.powertool";
    public static final String PERM_EDIT = "allium.powertool.edit";
    public static final String PERM_CONSOLE = "allium.powertool.console";
    public static final String PERM_SAVE = "allium.powertool.save";
    public static final String PERM_BIND = "allium.powertool.bind";
    public static final String PERM_GIVE = "allium.powertool.give";
    public static final String PERM_TOGGLE = "allium.powertool.toggle";
    public static final String PERM_RELOAD = "allium.powertool.reload";

    private static final List<String> SUBCOMMANDS = List.of(
            "add", "set", "remove", "clear", "cooldown", "perm", "uses", "cancel", "sneak", "hand",
            "info", "save", "delete", "bind", "unbind", "enable", "disable", "get", "give", "list", "toggle", "reload", "help"
    );

    private static final List<String> FLAGS = List.of(
            "--t", "--trigger", "--as", "--cd", "--cooldown", "--scope", "--cdid",
            "--delay", "--perm", "--sneak", "--hand", "--cancel", "--visualcd", "--param"
    );

    private final PluginStart plugin;
    private final Supplier<PowerToolBehavior> behaviorSupplier;
    private final Supplier<ToolsModule> toolsModuleSupplier;

    public PowerToolCommand(
            PluginStart plugin,
            Supplier<PowerToolBehavior> behaviorSupplier,
            Supplier<ToolsModule> toolsModuleSupplier
    ) {
        this.plugin = plugin;
        this.behaviorSupplier = behaviorSupplier;
        this.toolsModuleSupplier = toolsModuleSupplier;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.POWERTOOL;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        PowerToolBehavior behavior = behaviorSupplier.get();
        if (behavior == null) {
            send(sender, Messages.POWERTOOL_UNAVAILABLE);
            return;
        }
        String rawLabel = context.label().toLowerCase(Locale.ROOT);
        String cmdLabel = rawLabel.contains(":") ? rawLabel.substring(rawLabel.indexOf(':') + 1) : rawLabel;
        if (cmdLabel.equals("ptt") || cmdLabel.equals("powertooltoggle")) {
            handleToggle(sender, behavior);
            return;
        }
        if (cmdLabel.equals("tools")) {
            handleList(sender, behavior, args);
            return;
        }
        if (args.length == 0) {
            handleHelp(sender, behavior, new String[0]);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.startsWith("/")) {
            // Essentials shorthand: /pt /<command...> binds a player command on right-click.
            String[] synthetic = new String[args.length + 1];
            synthetic[0] = "command";
            System.arraycopy(args, 0, synthetic, 1, args.length);
            handleAddOrSet(sender, behavior, synthetic, -1);
            return;
        }
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "add" -> handleAddOrSet(sender, behavior, subArgs, -1);
            case "set" -> handleSet(sender, behavior, subArgs);
            case "remove", "rm" -> handleRemove(sender, behavior, subArgs);
            case "clear", "unbind" -> handleClear(sender, behavior);
            case "cooldown", "cd" -> handleCooldown(sender, behavior, subArgs);
            case "perm", "permission" -> handlePermission(sender, behavior, subArgs);
            case "uses" -> handleUses(sender, behavior, subArgs);
            case "cancel" -> handleCancel(sender, behavior, subArgs);
            case "sneak" -> handleSneak(sender, behavior, subArgs);
            case "hand" -> handleHand(sender, behavior, subArgs);
            case "info" -> handleInfo(sender, behavior, subArgs);
            case "save" -> handleSave(sender, behavior, subArgs);
            case "delete", "del" -> handleDelete(sender, behavior, subArgs);
            case "bind" -> handleBind(sender, behavior, subArgs);
            case "enable" -> handleSetEnabled(sender, behavior, subArgs, true);
            case "disable" -> handleSetEnabled(sender, behavior, subArgs, false);
            case "get" -> handleGet(sender, behavior, subArgs);
            case "give" -> handleGive(sender, behavior, subArgs);
            case "list" -> handleList(sender, behavior, subArgs);
            case "toggle" -> handleToggle(sender, behavior);
            case "reload" -> handleReload(sender, behavior);
            case "help" -> handleHelp(sender, behavior, subArgs);
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
    }

    // ---- Editing held item ----

    private void handleSet(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (args.length < 2) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt set <index> [flags...] <action> [args...]");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[0]) - 1;
        } catch (NumberFormatException e) {
            send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "index must be a number starting at 1");
            return;
        }
        if (index < 0) {
            send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "index must be >= 1");
            return;
        }
        handleAddOrSet(sender, behavior, Arrays.copyOfRange(args, 1, args.length), index);
    }

    private void handleAddOrSet(CommandSender sender, PowerToolBehavior behavior, String[] args, int replaceIndex) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger explicitTrigger = null;
        boolean asConsole = false;
        long cooldownMillis = 0L;
        PowerToolCooldownScope scope = PowerToolCooldownScope.PLAYER;
        String cooldownId = null;
        long delayTicks = 0L;
        String actionPerm = null;
        String sneak = null;
        String hand = null;
        Boolean cancel = null;
        Boolean visualCd = null;
        Map<String, Object> params = new LinkedHashMap<>();

        int i = 0;
        while (i < args.length && args[i].startsWith("--")) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            if (i + 1 >= args.length) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "missing value for " + flag);
                return;
            }
            String val = args[++i];
            switch (flag) {
                case "--t", "--trigger" -> {
                    explicitTrigger = PowerToolTrigger.parse(val);
                    if (explicitTrigger == null) {
                        send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", val,
                                "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
                        return;
                    }
                }
                case "--as" -> {
                    if ("console".equalsIgnoreCase(val) || "c".equalsIgnoreCase(val)) {
                        asConsole = true;
                    } else if ("player".equalsIgnoreCase(val) || "p".equalsIgnoreCase(val)) {
                        asConsole = false;
                    } else {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "--as must be player or console");
                        return;
                    }
                }
                case "--cd", "--cooldown" -> {
                    try {
                        cooldownMillis = PowerToolDurations.parseMillis(val);
                    } catch (IllegalArgumentException e) {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", e.getMessage());
                        return;
                    }
                }
                case "--scope" -> {
                    scope = PowerToolCooldownScope.parse(val);
                    if (scope == null) {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "--scope must be player, item, or global");
                        return;
                    }
                }
                case "--cdid", "--cooldown-id" -> cooldownId = val;
                case "--delay" -> {
                    try {
                        delayTicks = PowerToolDurations.millisToTicks(PowerToolDurations.parseMillis(val));
                    } catch (IllegalArgumentException e) {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", e.getMessage());
                        return;
                    }
                }
                case "--perm", "--permission" -> actionPerm = val.toLowerCase(Locale.ROOT);
                case "--sneak" -> {
                    String s = val.toLowerCase(Locale.ROOT);
                    if (!Set.of("any", "require", "disallow").contains(s)) {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "--sneak must be any, require, or disallow");
                        return;
                    }
                    sneak = s;
                }
                case "--hand" -> {
                    String h = val.toLowerCase(Locale.ROOT);
                    if (!Set.of("main", "off", "both").contains(h)) {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "--hand must be main, off, or both");
                        return;
                    }
                    hand = h;
                }
                case "--cancel" -> cancel = Boolean.parseBoolean(val);
                case "--visualcd", "--visual-cooldown" -> visualCd = Boolean.parseBoolean(val);
                case "--param" -> {
                    int eq = val.indexOf('=');
                    if (eq <= 0 || eq == val.length() - 1) {
                        send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "--param must be key=value");
                        return;
                    }
                    params.put(val.substring(0, eq).trim(), parseParamValue(val.substring(eq + 1).trim()));
                }
                default -> {
                    send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "unknown flag " + flag);
                    return;
                }
            }
            i++;
        }

        if (i >= args.length) {
            send(sender, Messages.INVALID_ARGS, "{usage}",
                    "/pt add [--t <trigger>] [--cd <dur>] [--delay <dur>] [--perm <node>] <action> [args...]");
            return;
        }

        String rawType = args[i];
        String type;
        String value;
        if (rawType.startsWith("/")) {
            type = asConsole ? PowerToolAction.TYPE_CONSOLE : PowerToolAction.TYPE_COMMAND;
            value = String.join(" ", Arrays.copyOfRange(args, i, args.length));
        } else {
            type = PowerToolAction.normalizeType(rawType);
            if (behavior.actions().get(type) == null) {
                send(sender, Messages.POWERTOOL_INVALID_ACTION, "{action}", rawType,
                        "{actions}", String.join(", ", behavior.actions().actionIds()));
                return;
            }
            value = i + 1 < args.length ? String.join(" ", Arrays.copyOfRange(args, i + 1, args.length)) : null;
        }

        if (PowerToolAction.TYPE_CONSOLE.equals(type)) {
            asConsole = true;
        }
        if (asConsole && !sender.hasPermission(PERM_CONSOLE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }

        if ((PowerToolAction.TYPE_COMMAND.equals(type) || PowerToolAction.TYPE_CONSOLE.equals(type)
                || PowerToolAction.TYPE_MESSAGE.equals(type) || PowerToolAction.TYPE_ACTIONBAR.equals(type)
                || PowerToolAction.TYPE_SOUND.equals(type) || PowerToolAction.TYPE_PARTICLE.equals(type)
                || PowerToolAction.TYPE_POTION.equals(type)) && (value == null || value.isBlank())) {
            send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "action '" + type + "' requires an argument");
            return;
        }

        if (asConsole && value != null) {
            String cleaned = PowerToolSecurity.cleanCommand(value);
            if (!PowerToolSecurity.isConsoleCommandAllowed(cleaned, behavior.settings().consoleAllowlist())) {
                send(sender, Messages.POWERTOOL_CONSOLE_BLOCKED, "{command}", cleaned);
                return;
            }
            if (behavior.settings().blockSelectors() && PowerToolSecurity.containsSelectors(cleaned)) {
                send(sender, Messages.POWERTOOL_SELECTOR_BLOCKED);
                return;
            }
        }

        // Smart default trigger when --t was omitted:
        PowerToolTrigger trigger = explicitTrigger != null ? explicitTrigger : defaultTriggerFor(type);

        PowerToolAction action = new PowerToolAction(
                type, value, asConsole, cooldownMillis, scope, cooldownId, delayTicks, actionPerm, params);

        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        PowerToolBinding existing = current.parameters().binding(trigger);
        PowerToolBinding binding = existing != null ? existing : PowerToolBinding.empty(trigger);
        if (sneak != null) binding = binding.withSneak(sneak);
        if (hand != null) binding = binding.withHand(hand);
        if (cancel != null) binding = binding.withCancelInteraction(cancel);
        if (visualCd != null) {
            binding = binding.withCooldown(binding.cooldownMillis(), binding.cooldownScope(), binding.cooldownId(), visualCd);
        }

        if (replaceIndex >= 0) {
            if (replaceIndex >= binding.actions().size()) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}",
                        "trigger '" + trigger.id() + "' only has " + binding.actions().size() + " action(s)");
                return;
            }
            binding = binding.withActionReplaced(replaceIndex, action);
        } else {
            binding = binding.withActionAdded(action);
        }

        PowerToolParameters updated = current.parameters().withBinding(trigger, binding);
        behavior.writeInline(held, updated, current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, replaceIndex >= 0 ? Messages.POWERTOOL_ACTION_SET : Messages.POWERTOOL_ACTION_ADDED,
                "{trigger}", trigger.id(), "{action}", action.describe(),
                "{index}", Integer.toString(replaceIndex >= 0 ? replaceIndex + 1 : binding.actions().size()));
    }

    private static PowerToolTrigger defaultTriggerFor(String actionType) {
        return switch (actionType) {
            case PowerToolAction.TYPE_TREE_FELL -> PowerToolTrigger.ON_BREAK;
            case PowerToolAction.TYPE_DISARM -> PowerToolTrigger.ON_INTERACT_ENTITY;
            default -> PowerToolTrigger.RIGHT;
        };
    }

    private void handleRemove(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger trigger = args.length >= 1 ? PowerToolTrigger.parse(args[0]) : PowerToolTrigger.RIGHT;
        if (trigger == null) {
            send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", args[0],
                    "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
            return;
        }
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        PowerToolBinding binding = current.parameters().binding(trigger);
        if (binding == null || binding.actions().isEmpty()) {
            send(sender, Messages.POWERTOOL_NO_BINDING, "{trigger}", trigger.id());
            return;
        }
        PowerToolParameters updated;
        if (args.length >= 2) {
            int idx;
            try {
                idx = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException e) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "index must be a number");
                return;
            }
            if (idx < 0 || idx >= binding.actions().size()) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "index out of range (1-" + binding.actions().size() + ")");
                return;
            }
            PowerToolBinding next = binding.withActionRemoved(idx);
            updated = current.parameters().withBinding(trigger, next.actions().isEmpty() ? null : next);
        } else {
            updated = current.parameters().withBinding(trigger, null);
        }
        behavior.writeInline(held, updated, current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_REMOVED, "{trigger}", trigger.id());
    }

    private void handleClear(CommandSender sender, PowerToolBehavior behavior) {
        if (!sender.hasPermission(PERM_EDIT) && !sender.hasPermission(PERM_BIND)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;
        boolean cleared = behavior.clearHeld(held);
        player.getInventory().setItemInMainHand(held);
        send(sender, cleared ? Messages.POWERTOOL_CLEARED : Messages.POWERTOOL_NOT_A_POWERTOOL);
    }

    private void handleCooldown(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger trigger = PowerToolTrigger.RIGHT;
        PowerToolCooldownScope scope = PowerToolCooldownScope.PLAYER;
        String cdId = null;
        boolean visual = false;
        String durationRaw = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("--t".equalsIgnoreCase(a) || "--trigger".equalsIgnoreCase(a)) && i + 1 < args.length) {
                trigger = PowerToolTrigger.parse(args[++i]);
                if (trigger == null) {
                    send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", args[i],
                            "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
                    return;
                }
            } else if ("--scope".equalsIgnoreCase(a) && i + 1 < args.length) {
                scope = PowerToolCooldownScope.parse(args[++i]);
                if (scope == null) {
                    send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "--scope must be player, item, or global");
                    return;
                }
            } else if (("--id".equalsIgnoreCase(a) || "--cdid".equalsIgnoreCase(a)) && i + 1 < args.length) {
                cdId = args[++i];
            } else if ("--visual".equalsIgnoreCase(a) && i + 1 < args.length) {
                visual = Boolean.parseBoolean(args[++i]);
            } else if (durationRaw == null) {
                durationRaw = a;
            }
        }
        if (durationRaw == null) {
            send(sender, Messages.INVALID_ARGS, "{usage}",
                    "/pt cooldown [--t <trigger>] <duration> [--scope player|item|global] [--id <id>] [--visual true|false]");
            return;
        }
        long millis;
        try {
            millis = PowerToolDurations.parseMillis(durationRaw);
        } catch (IllegalArgumentException e) {
            send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", e.getMessage());
            return;
        }
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        PowerToolBinding binding = current.parameters().binding(trigger);
        if (binding == null) {
            send(sender, Messages.POWERTOOL_NO_BINDING, "{trigger}", trigger.id());
            return;
        }
        PowerToolBinding updatedBinding = binding.withCooldown(millis, scope, cdId, visual);
        behavior.writeInline(held, current.parameters().withBinding(trigger, updatedBinding), current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "cooldown (" + trigger.id() + ")",
                "{value}", millis <= 0L ? "none" : PowerToolDurations.formatMillis(millis) + " [" + scope.id() + "]");
    }

    private void handlePermission(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger trigger = null;
        String permRaw = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("--t".equalsIgnoreCase(a) || "--trigger".equalsIgnoreCase(a)) && i + 1 < args.length) {
                trigger = PowerToolTrigger.parse(args[++i]);
                if (trigger == null) {
                    send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", args[i],
                            "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
                    return;
                }
            } else if ("--tool".equalsIgnoreCase(a)) {
                trigger = null;
            } else if (permRaw == null) {
                permRaw = a;
            }
        }
        if (permRaw == null) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt perm [--t <trigger>] <permission|none>");
            return;
        }
        String node = ("none".equalsIgnoreCase(permRaw) || "clear".equalsIgnoreCase(permRaw))
                ? null : permRaw.trim().toLowerCase(Locale.ROOT);
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        if (trigger == null) {
            if (!current.parameters().hasBindings()) {
                send(sender, Messages.POWERTOOL_NOT_A_POWERTOOL);
                return;
            }
            behavior.writeInline(held, current.parameters(), node);
            player.getInventory().setItemInMainHand(held);
            send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "use-permission", "{value}", node == null ? "none" : node);
            return;
        }
        PowerToolBinding binding = current.parameters().binding(trigger);
        if (binding == null) {
            send(sender, Messages.POWERTOOL_NO_BINDING, "{trigger}", trigger.id());
            return;
        }
        behavior.writeInline(held, current.parameters().withBinding(trigger, binding.withPermission(node)), current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "permission (" + trigger.id() + ")", "{value}", node == null ? "none" : node);
    }

    private void handleUses(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        if (args.length == 0) {
            send(sender, Messages.INVALID_ARGS, "{usage}",
                    "/pt uses <max|unlimited> [--consume true|false] [--recharge <duration>]");
            return;
        }
        int max;
        if ("unlimited".equalsIgnoreCase(args[0]) || "-1".equals(args[0])) {
            max = -1;
        } else {
            try {
                max = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "uses must be a number or 'unlimited'");
                return;
            }
            if (max < 1 || max > 10_000) {
                send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "uses must be between 1 and 10000 (or unlimited)");
                return;
            }
        }
        boolean consume = false;
        int rechargeSec = 0;
        for (int i = 1; i < args.length; i++) {
            if ("--consume".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                consume = Boolean.parseBoolean(args[++i]);
            } else if ("--recharge".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    rechargeSec = (int) Math.min(86_400L, PowerToolDurations.parseMillis(args[++i]) / 1000L);
                } catch (IllegalArgumentException e) {
                    send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", e.getMessage());
                    return;
                }
            }
        }
        PowerToolUses uses = max < 0
                ? PowerToolUses.UNLIMITED
                : new PowerToolUses(max, consume, rechargeSec, !consume);
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        behavior.writeInline(held, current.parameters().withUses(uses), current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "uses",
                "{value}", uses.isLimited() ? (uses.max() + (consume ? " (consume)" : "") + (rechargeSec > 0 ? " (recharge " + rechargeSec + "s)" : "")) : "unlimited");
    }

    private void handleCancel(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger trigger = PowerToolTrigger.RIGHT;
        String val = null;
        for (int i = 0; i < args.length; i++) {
            if (("--t".equalsIgnoreCase(args[i]) || "--trigger".equalsIgnoreCase(args[i])) && i + 1 < args.length) {
                trigger = PowerToolTrigger.parse(args[++i]);
                if (trigger == null) {
                    send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", args[i],
                            "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
                    return;
                }
            } else if (val == null) {
                val = args[i];
            }
        }
        if (val == null) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt cancel [--t <trigger>] <true|false>");
            return;
        }
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        PowerToolBinding binding = current.parameters().binding(trigger);
        if (binding == null) {
            send(sender, Messages.POWERTOOL_NO_BINDING, "{trigger}", trigger.id());
            return;
        }
        boolean cancel = Boolean.parseBoolean(val);
        behavior.writeInline(held, current.parameters().withBinding(trigger, binding.withCancelInteraction(cancel)), current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "cancel (" + trigger.id() + ")", "{value}", Boolean.toString(cancel));
    }

    private void handleSneak(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger trigger = PowerToolTrigger.RIGHT;
        String val = null;
        for (int i = 0; i < args.length; i++) {
            if (("--t".equalsIgnoreCase(args[i]) || "--trigger".equalsIgnoreCase(args[i])) && i + 1 < args.length) {
                trigger = PowerToolTrigger.parse(args[++i]);
                if (trigger == null) {
                    send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", args[i],
                            "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
                    return;
                }
            } else if (val == null) {
                val = args[i].toLowerCase(Locale.ROOT);
            }
        }
        if (val == null || !Set.of("any", "require", "disallow").contains(val)) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt sneak [--t <trigger>] <any|require|disallow>");
            return;
        }
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        PowerToolBinding binding = current.parameters().binding(trigger);
        if (binding == null) {
            send(sender, Messages.POWERTOOL_NO_BINDING, "{trigger}", trigger.id());
            return;
        }
        behavior.writeInline(held, current.parameters().withBinding(trigger, binding.withSneak(val)), current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "sneak (" + trigger.id() + ")", "{value}", val);
    }

    private void handleHand(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_EDIT)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;

        PowerToolTrigger trigger = PowerToolTrigger.RIGHT;
        String val = null;
        for (int i = 0; i < args.length; i++) {
            if (("--t".equalsIgnoreCase(args[i]) || "--trigger".equalsIgnoreCase(args[i])) && i + 1 < args.length) {
                trigger = PowerToolTrigger.parse(args[++i]);
                if (trigger == null) {
                    send(sender, Messages.POWERTOOL_INVALID_TRIGGER, "{trigger}", args[i],
                            "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
                    return;
                }
            } else if (val == null) {
                val = args[i].toLowerCase(Locale.ROOT);
            }
        }
        if (val == null || !Set.of("main", "off", "both").contains(val)) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt hand [--t <trigger>] <main|off|both>");
            return;
        }
        PowerToolCodec.DecodedInline current = behavior.readEditable(held);
        PowerToolBinding binding = current.parameters().binding(trigger);
        if (binding == null) {
            send(sender, Messages.POWERTOOL_NO_BINDING, "{trigger}", trigger.id());
            return;
        }
        behavior.writeInline(held, current.parameters().withBinding(trigger, binding.withHand(val)), current.usePermission());
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_UPDATED, "{setting}", "hand (" + trigger.id() + ")", "{value}", val);
    }

    // ---- Save / Delete / Bind / Give / Get / Info / List / Toggle / Reload ----

    private void handleSave(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_SAVE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;
        if (args.length == 0) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt save <name> [--overwrite]");
            return;
        }
        String name = args[0];
        boolean overwrite = args.length >= 2 && ("--overwrite".equalsIgnoreCase(args[1]) || "-f".equalsIgnoreCase(args[1]));
        PowerToolBehavior.ResolvedTool resolved = behavior.resolve(held);
        if (resolved != null && resolved.parameters().hasConsoleActions() && !sender.hasPermission(PERM_CONSOLE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        PowerToolBehavior.SaveResult result = behavior.saveHeldAsTool(name, held, overwrite);
        if (!result.saved() || result.definition() == null) {
            send(sender, Messages.POWERTOOL_SAVE_FAILED, "{reason}", result.message());
            return;
        }
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_SAVED,
                "{name}", result.definition().localId(),
                "{file}", result.definition().file());
    }

    private void handleDelete(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_SAVE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        if (args.length != 1) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt delete <name>");
            return;
        }
        PowerToolBehavior.SaveResult result = behavior.deleteSavedTool(args[0]);
        if (!result.saved() || result.definition() == null) {
            send(sender, Messages.POWERTOOL_NOT_FOUND, "{name}", args[0]);
            return;
        }
        send(sender, Messages.POWERTOOL_DELETED, "{name}", result.definition().localId());
    }

    private void handleBind(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_BIND)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        ItemStack held = requireHeldItem(player);
        if (held == null) return;
        if (args.length != 1) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt bind <name>");
            return;
        }
        ToolDefinition def = behavior.registryTool(args[0]).orElse(null);
        if (def == null) {
            send(sender, Messages.POWERTOOL_NOT_FOUND, "{name}", args[0]);
            return;
        }
        behavior.bindRegistryToHeld(held, def);
        player.getInventory().setItemInMainHand(held);
        send(sender, Messages.POWERTOOL_BOUND, "{name}", def.localId());
    }

    private void handleGet(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_GIVE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 1 || args.length > 2) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt get <item> [amount]");
            return;
        }
        int amount = args.length == 2 ? parsePositiveAmount(args[1]) : 1;
        if (amount <= 0) {
            send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "amount must be between 1 and 64");
            return;
        }
        giveTool(sender, player, behavior, args[0], amount);
    }

    private void handleGive(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        if (!sender.hasPermission(PERM_GIVE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 2 || args.length > 3) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt give <player> <item> [amount]");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
            send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[0]);
            return;
        }
        int amount = args.length == 3 ? parsePositiveAmount(args[2]) : 1;
        if (amount <= 0) {
            send(sender, Messages.POWERTOOL_INVALID_VALUE, "{reason}", "amount must be between 1 and 64");
            return;
        }
        giveTool(sender, target, behavior, args[1], amount);
    }

    private void giveTool(CommandSender sender, Player target, PowerToolBehavior behavior, String rawTool, int amount) {
        ResolutionResult res = behavior.createToolItem(rawTool, amount);
        if (!res.isResolved() || res.itemStack().isEmpty()) {
            send(sender, Messages.POWERTOOL_NOT_FOUND, "{name}", rawTool);
            return;
        }
        ItemStack stack = res.itemStack().get();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(stack);
        for (ItemStack drop : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), drop);
        }
        String localName = rawTool.contains(":") ? rawTool.substring(rawTool.indexOf(':') + 1) : rawTool;
        if (sender == target) {
            send(sender, Messages.POWERTOOL_GIVEN_SELF, "{name}", localName, "{amount}", Integer.toString(amount));
        } else {
            send(sender, Messages.POWERTOOL_GIVEN_OTHER, "{player}", target.getName(), "{name}", localName, "{amount}", Integer.toString(amount));
            send(target, Messages.POWERTOOL_GIVEN_SELF, "{name}", localName, "{amount}", Integer.toString(amount));
        }
    }

    private void handleInfo(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        PowerToolBehavior.ResolvedTool resolved;
        if (args.length >= 1) {
            ToolDefinition def = behavior.registryTool(args[0]).orElse(null);
            if (def == null) {
                send(sender, Messages.POWERTOOL_NOT_FOUND, "{name}", args[0]);
                return;
            }
            resolved = new PowerToolBehavior.ResolvedTool(
                    def.id(), def.name(), false, def,
                    def.parameters(PowerToolParameters.class),
                    def.permission(PowerToolBehaviorType.PERM_USE),
                    def.permission(PowerToolBehaviorType.PERM_BYPASS)
            );
        } else {
            Player player = requirePlayer(sender);
            if (player == null) return;
            ItemStack held = requireHeldItem(player);
            if (held == null) return;
            resolved = behavior.resolve(held);
            if (resolved == null) {
                send(sender, Messages.POWERTOOL_NOT_A_POWERTOOL);
                return;
            }
        }
        send(sender, Messages.POWERTOOL_INFO_HEADER,
                "{name}", resolved.displayName(),
                "{id}", resolved.inline() ? "inline (unsaved)" : resolved.id());
        PowerToolParameters params = resolved.parameters();
        if (params.uses().isLimited()) {
            send(sender, Messages.POWERTOOL_INFO_USES,
                    "{max}", Integer.toString(params.uses().max()),
                    "{consume}", Boolean.toString(params.uses().consumeOnEmpty()),
                    "{recharge}", params.uses().rechargeSeconds() + "s");
        }
        if (resolved.usePermission() != null) {
            send(sender, Messages.POWERTOOL_INFO_PERM, "{permission}", resolved.usePermission());
        }
        for (Map.Entry<PowerToolTrigger, PowerToolBinding> entry : params.bindings().entrySet()) {
            PowerToolBinding b = entry.getValue();
            StringBuilder meta = new StringBuilder();
            meta.append("hand=").append(b.hand())
                    .append(", sneak=").append(b.sneak())
                    .append(", cancel=").append(b.cancelInteraction());
            if (b.hasCooldown()) {
                meta.append(", cd=").append(PowerToolDurations.formatMillis(b.cooldownMillis()))
                        .append("/").append(b.cooldownScope().id());
                if (b.visualCooldown()) meta.append("+visual");
            }
            if (b.hasPermission()) {
                meta.append(", perm=").append(b.requirePermission());
            }
            send(sender, Messages.POWERTOOL_INFO_BINDING, "{trigger}", entry.getKey().id(), "{meta}", meta.toString());
            List<PowerToolAction> acts = b.actions();
            for (int i = 0; i < acts.size(); i++) {
                send(sender, Messages.POWERTOOL_INFO_ACTION, "{index}", Integer.toString(i + 1), "{action}", acts.get(i).describe());
            }
        }
    }

    private void handleSetEnabled(CommandSender sender, PowerToolBehavior behavior, String[] args, boolean enabled) {
        if (!sender.hasPermission(PERM_SAVE) && !sender.hasPermission(PERM_RELOAD)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        if (args.length != 1) {
            send(sender, Messages.INVALID_ARGS, "{usage}", "/pt " + (enabled ? "enable" : "disable") + " <tool>");
            return;
        }
        ToolsModule module = toolsModuleSupplier.get();
        Runnable reload = module != null ? module::reloadAllTools : null;
        PowerToolBehavior.EnableResult result = behavior.setToolEnabled(args[0], enabled, reload);
        switch (result.outcome()) {
            case CHANGED -> send(sender, enabled ? Messages.POWERTOOL_ENABLED : Messages.POWERTOOL_DISABLED,
                    "{name}", result.localId());
            case ALREADY_IN_STATE -> send(sender, enabled ? Messages.POWERTOOL_ALREADY_ENABLED : Messages.POWERTOOL_ALREADY_DISABLED,
                    "{name}", result.localId());
            case NOT_FOUND -> send(sender, Messages.POWERTOOL_NOT_FOUND, "{name}", args[0]);
            case FAILED -> send(sender, Messages.POWERTOOL_ENABLE_FAILED, "{reason}", result.message());
        }
    }

    private void handleList(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        String filter = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        Map<String, Boolean> states = new java.util.TreeMap<>();
        for (PowerToolBehavior.ToolSummary summary : behavior.listAllToolFiles()) {
            states.put(summary.localId(), summary.enabled());
        }
        for (String id : allGiveableToolIds(behavior)) {
            states.putIfAbsent(id, true);
        }
        List<String> formatted = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            if (!filter.isEmpty() && !entry.getKey().contains(filter)) {
                continue;
            }
            formatted.add(entry.getValue() ? entry.getKey() : entry.getKey() + " &7(disabled)&f");
        }
        if (formatted.isEmpty()) {
            send(sender, Messages.POWERTOOL_LIST_EMPTY);
            return;
        }
        send(sender, Messages.POWERTOOL_LIST, "{count}", Integer.toString(formatted.size()),
                "{tools}", String.join(", ", formatted));
    }

    private void handleToggle(CommandSender sender, PowerToolBehavior behavior) {
        if (!sender.hasPermission(PERM_TOGGLE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) return;
        boolean enabled = behavior.togglePlayer(player.getUniqueId());
        send(sender, enabled ? Messages.POWERTOOL_TOGGLE_ON : Messages.POWERTOOL_TOGGLE_OFF);
    }

    private void handleReload(CommandSender sender, PowerToolBehavior behavior) {
        if (!sender.hasPermission(PERM_RELOAD)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        ToolsModule module = toolsModuleSupplier.get();
        if (module != null) {
            module.reloadAllTools();
            send(sender, Messages.POWERTOOL_RELOADED, "{count}", Integer.toString(module.enabledToolLocalIds().size()));
            return;
        }
        behavior.updateSettings(PowerToolSettings.fromConfig(plugin.getConfig().getConfigurationSection("powertools")));
        int loaded = behavior.reloadPowerTools();
        send(sender, Messages.POWERTOOL_RELOADED, "{count}", Integer.toString(loaded));
    }

    private void handleHelp(CommandSender sender, PowerToolBehavior behavior, String[] args) {
        String topic = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (topic) {
            case "triggers" -> send(sender, Messages.POWERTOOL_HELP_TRIGGERS,
                    "{triggers}", String.join(", ", PowerToolTrigger.canonicalIds()));
            case "actions" -> send(sender, Messages.POWERTOOL_HELP_ACTIONS,
                    "{actions}", String.join(", ", behavior.actions().actionIds()));
            case "placeholders" -> send(sender, Messages.POWERTOOL_HELP_PLACEHOLDERS,
                    "{placeholders}", String.join(", ", PowerToolSecurity.ALLOWED_PLACEHOLDERS.stream().map(p -> "{" + p + "}").sorted().toList()));
            default -> send(sender, Messages.POWERTOOL_HELP);
        }
    }

    // ---- Tab completion ----

    public List<String> complete(CommandSender sender, String[] args) {
        return complete(sender, "powertool", args);
    }

    public List<String> complete(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERM_BASE)) {
            return List.of();
        }
        PowerToolBehavior behavior = behaviorSupplier.get();
        if (behavior == null) {
            return List.of();
        }
        String cleanLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (cleanLabel.contains(":")) {
            cleanLabel = cleanLabel.substring(cleanLabel.indexOf(':') + 1);
        }
        if ("tools".equals(cleanLabel)) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return args.length <= 1
                    ? behavior.listAllToolFiles().stream().map(PowerToolBehavior.ToolSummary::localId)
                            .filter(id -> id.startsWith(prefix)).toList()
                    : List.of();
        }
        if ("ptt".equals(cleanLabel) || "powertooltoggle".equals(cleanLabel)) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        String prev = args[args.length - 2].toLowerCase(Locale.ROOT);

        if ("--t".equals(prev) || "--trigger".equals(prev)) {
            return PowerToolTrigger.canonicalIds().stream().filter(t -> t.startsWith(current)).toList();
        }
        if ("--as".equals(prev)) {
            return List.of("player", "console").stream().filter(v -> v.startsWith(current)).toList();
        }
        if ("--scope".equals(prev)) {
            return List.of("player", "item", "global").stream().filter(v -> v.startsWith(current)).toList();
        }
        if ("--sneak".equals(prev)) {
            return List.of("any", "require", "disallow").stream().filter(v -> v.startsWith(current)).toList();
        }
        if ("--hand".equals(prev)) {
            return List.of("main", "off", "both").stream().filter(v -> v.startsWith(current)).toList();
        }
        if ("--cancel".equals(prev) || "--visual".equals(prev) || "--visualcd".equals(prev) || "--consume".equals(prev)) {
            return List.of("true", "false").stream().filter(v -> v.startsWith(current)).toList();
        }
        if ("--cd".equals(prev) || "--cooldown".equals(prev) || "--delay".equals(prev) || "--recharge".equals(prev)) {
            return List.of("500ms", "1s", "5s", "30s", "2m", "20t").stream().filter(v -> v.startsWith(current)).toList();
        }

        return switch (sub) {
            case "add", "set" -> {
                if (current.startsWith("-")) {
                    yield FLAGS.stream().filter(f -> f.startsWith(current)).toList();
                }
                List<String> out = new ArrayList<>();
                for (String a : behavior.actions().actionIds()) {
                    if (a.startsWith(current)) out.add(a);
                }
                for (String f : FLAGS) {
                    if (f.startsWith(current)) out.add(f);
                }
                yield out;
            }
            case "remove", "rm" -> args.length == 2
                    ? PowerToolTrigger.canonicalIds().stream().filter(t -> t.startsWith(current)).toList()
                    : List.of("1", "2", "3").stream().filter(v -> v.startsWith(current)).toList();
            case "get" -> args.length == 2
                    ? allGiveableToolIds(behavior).stream().filter(id -> id.startsWith(current)).toList()
                    : List.of("1", "16", "64").stream().filter(v -> v.startsWith(current)).toList();
            case "give" -> {
                if (args.length == 2) {
                    yield plugin.getServer().getOnlinePlayers().stream()
                            .filter(p -> PlayerNameVisibility.canSeeTarget(sender, p))
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(current))
                            .sorted()
                            .toList();
                }
                if (args.length == 3) {
                    yield allGiveableToolIds(behavior).stream().filter(id -> id.startsWith(current)).toList();
                }
                yield List.of("1", "16", "64").stream().filter(v -> v.startsWith(current)).toList();
            }
            case "bind", "delete", "del", "info" -> args.length == 2
                    ? behavior.registryLocalIds().stream().filter(id -> id.startsWith(current)).toList()
                    : List.of();
            case "enable" -> args.length == 2
                    ? behavior.listAllToolFiles().stream().filter(t -> !t.enabled())
                            .map(PowerToolBehavior.ToolSummary::localId).filter(id -> id.startsWith(current)).toList()
                    : List.of();
            case "disable" -> args.length == 2
                    ? behavior.listAllToolFiles().stream().filter(PowerToolBehavior.ToolSummary::enabled)
                            .map(PowerToolBehavior.ToolSummary::localId).filter(id -> id.startsWith(current)).toList()
                    : List.of();
            case "save" -> args.length == 3
                    ? List.of("--overwrite").stream().filter(v -> v.startsWith(current)).toList()
                    : List.of();
            case "help" -> args.length == 2
                    ? List.of("triggers", "actions", "placeholders").stream().filter(v -> v.startsWith(current)).toList()
                    : List.of();
            case "uses" -> args.length == 2
                    ? List.of("1", "3", "10", "unlimited").stream().filter(v -> v.startsWith(current)).toList()
                    : List.of("--consume", "--recharge").stream().filter(v -> v.startsWith(current)).toList();
            case "cooldown", "cd" -> List.of("--t", "--scope", "--id", "--visual", "5s", "10s", "0")
                    .stream().filter(v -> v.startsWith(current)).toList();
            case "sneak" -> List.of("--t", "any", "require", "disallow").stream().filter(v -> v.startsWith(current)).toList();
            case "hand" -> List.of("--t", "main", "off", "both").stream().filter(v -> v.startsWith(current)).toList();
            case "cancel" -> List.of("--t", "true", "false").stream().filter(v -> v.startsWith(current)).toList();
            default -> List.of();
        };
    }

    private List<String> allGiveableToolIds(PowerToolBehavior behavior) {
        Set<String> ids = new TreeSet<>(behavior.registryLocalIds());
        ToolsModule module = toolsModuleSupplier.get();
        if (module != null) {
            ids.addAll(module.enabledToolLocalIds());
        }
        return new ArrayList<>(ids);
    }

    @Nullable
    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        send(sender, Messages.PLAYER_ONLY);
        return null;
    }

    @Nullable
    private ItemStack requireHeldItem(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            send(player, Messages.POWERTOOL_HOLD_ITEM);
            return null;
        }
        return held;
    }

    private static int parsePositiveAmount(String raw) {
        try {
            int val = Integer.parseInt(raw.trim());
            return (val >= 1 && val <= 64) ? val : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Object parseParamValue(String raw) {
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        if (raw.contains(",")) {
            List<String> list = new ArrayList<>();
            for (String part : raw.split(",")) {
                if (!part.isBlank()) list.add(part.trim());
            }
            return list;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
        }
        return raw;
    }

    private void send(CommandSender sender, Messages message, String... replacements) {
        plugin.getMessageManager().send(sender, message, replacements);
    }
}
