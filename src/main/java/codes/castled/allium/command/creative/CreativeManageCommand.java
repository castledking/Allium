package codes.castled.allium.command.creative;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.creative.manage.CreativeAccess;
import codes.castled.allium.creative.manage.CreativeFlag;
import codes.castled.allium.creative.manage.CreativeHolder;
import codes.castled.allium.creative.manage.CreativeManageMenus;
import codes.castled.allium.creative.manage.FlagValue;
import codes.castled.allium.creative.manage.PermissionBackend;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code /creativemanager} ({@code /cm}, {@code /allium creativemanager}): the menu of groups and
 * players, and {@code player|group <name> edit|list|check|add|remove [flag]}.
 */
public final class CreativeManageCommand implements SpecCommandHandler {

    private static final List<String> ACTIONS = List.of("edit", "list", "check", "add", "remove");

    private final PluginStart plugin;
    private final CreativeAccess access;
    private final CreativeManageMenus menus;

    public CreativeManageCommand(PluginStart plugin, CreativeAccess access, CreativeManageMenus menus) {
        this.plugin = plugin;
        this.access = access;
        this.menus = menus;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.CREATIVEMANAGER;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        run(context.sender(), context.args(), "/creativemanager", context::sendInvalidArgs);
    }

    /**
     * Runs the command for {@code sender}. {@code root} is how it was reached ({@code /creativemanager}
     * or {@code /allium creativemanager}), so a usage card names the right form.
     */
    public void run(CommandSender sender, String[] args, String root, Consumer<String> usage) {
        if (!sender.hasPermission(CreativeAccess.PERMISSION_MANAGE)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        String sub = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui", "active" -> {
                if (args.length > 1) {
                    usage.accept(root + " " + sub);
                } else if (!(sender instanceof Player viewer)) {
                    send(sender, Messages.PLAYER_ONLY);
                } else {
                    menus.openList(viewer, sub.equals("active") ? CreativeManageMenus.Filter.PLAYERS : CreativeManageMenus.Filter.ALL, 0);
                }
            }
            case "player", "group" -> holderCommand(sender, sub.equals("group"), Arrays.copyOfRange(args, 1, args.length), root, usage);
            default -> usage.accept(root);
        }
    }

    private void holderCommand(CommandSender sender, boolean group, String[] args, String root, Consumer<String> usage) {
        String form = root + " " + (group ? "group" : "player");
        if (args.length < 1 || args.length > 3) {
            usage.accept(form);
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "edit";
        if (!ACTIONS.contains(action)) {
            usage.accept(form);
            return;
        }
        boolean fits = switch (action) {
            case "add", "remove" -> args.length == 3;
            case "check" -> true;
            default -> args.length <= 2;
        };
        if (!fits) {
            String syntax = switch (action) {
                case "edit" -> "[edit]";
                case "check" -> "check [flag]";
                case "list" -> "list";
                default -> action + " <flag>";
            };
            usage.accept(form + (group ? " <group> " : " <player> ") + syntax);
            return;
        }
        CreativeFlag flag = null;
        if (args.length == 3) {
            flag = CreativeFlag.byKey(args[2]);
            if (flag == null) {
                send(sender, Messages.CREATIVE_MANAGE_UNKNOWN_FLAG, "{flag}", args[2], "{flags}", String.join(", ", CreativeFlag.keys()));
                return;
            }
        }
        if (action.equals("edit") && !(sender instanceof Player)) {
            send(sender, Messages.PLAYER_ONLY);
            return;
        }
        CreativeFlag chosen = flag;
        resolve(sender, group, args[0], holder -> act(sender, holder, action, chosen));
    }

    /** Finds the group, or the player (online first, then Allium's name index), then runs {@code then}. */
    private void resolve(CommandSender sender, boolean group, String name, Consumer<CreativeHolder> then) {
        if (group) {
            PermissionBackend backend = access.backend();
            if (!backend.canWrite()) {
                send(sender, Messages.CREATIVE_MANAGE_UNAVAILABLE);
                return;
            }
            String canonical = backend.group(name);
            if (canonical == null) {
                send(sender, Messages.CREATIVE_MANAGE_UNKNOWN_GROUP, "{group}", name);
                return;
            }
            then.accept(CreativeHolder.group(canonical));
            return;
        }
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null && PlayerNameVisibility.canSeeTarget(sender, online)) {
            then.accept(CreativeHolder.player(online.getUniqueId(), online.getName()));
            return;
        }
        if (plugin.getPlayerNames() == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            send(sender, Messages.PLAYER_NOT_FOUND, "{player}", name);
            return;
        }
        plugin.getPlayerNames().resolve(name).whenComplete((found, error) -> BukkitServerThread.runFor(plugin, plugin.getScheduler(), sender, () -> {
            if (sender instanceof Player viewer && !viewer.isOnline()) return;
            if (error != null || found == null || found.isEmpty()) {
                send(sender, Messages.PLAYER_NOT_FOUND, "{player}", name);
            } else {
                then.accept(CreativeHolder.player(found.get().uuid(), found.get().name()));
            }
        }, () -> {}, 0L));
    }

    private void act(CommandSender sender, CreativeHolder holder, String action, CreativeFlag flag) {
        switch (action) {
            case "edit" -> {
                if (sender instanceof Player viewer && viewer.isOnline()) {
                    menus.openFlags(viewer, holder, null, 0);
                }
            }
            case "list" -> access.read(sender, holder, (values, error) -> {
                if (error != null) {
                    failed(sender, holder, error);
                    return;
                }
                List<String> shown = listed(values);
                String backend = access.backend().name();
                if (shown.isEmpty()) {
                    send(sender, Messages.CREATIVE_MANAGE_LIST_EMPTY, "{holder}", holder.describe(), "{backend}", backend);
                } else {
                    send(sender, Messages.CREATIVE_MANAGE_LIST, "{holder}", holder.describe(), "{backend}", backend,
                            "{flags}", String.join(", ", shown));
                }
            });
            case "check" -> access.read(sender, holder, (values, error) -> {
                if (error != null) {
                    failed(sender, holder, error);
                    return;
                }
                for (CreativeFlag each : flag == null ? List.of(CreativeFlag.values()) : List.of(flag)) {
                    send(sender, Messages.CREATIVE_MANAGE_CHECK, "{flag}", each.key(), "{state}", values.get(each).describeEffective());
                }
            });
            case "add", "remove" -> change(sender, holder, flag, action.equals("add"));
            default -> {
            }
        }
    }

    /**
     * Flags the holder sets itself ({@code -flag} when denied). Without that knowledge (Vault), the flags
     * that apply.
     */
    static List<String> listed(Map<CreativeFlag, FlagValue> values) {
        List<String> out = new ArrayList<>();
        for (CreativeFlag flag : CreativeFlag.values()) {
            FlagValue value = values.get(flag);
            if (value.setKnown() ? value.set() != null : Boolean.TRUE.equals(value.effective())) {
                out.add(value.setKnown() && Boolean.FALSE.equals(value.set()) ? "-" + flag.key() : flag.key());
            }
        }
        return out;
    }

    private void change(CommandSender sender, CreativeHolder holder, CreativeFlag flag, boolean give) {
        PermissionBackend backend = access.backend();
        if (!backend.canWrite()) {
            send(sender, Messages.CREATIVE_MANAGE_UNAVAILABLE);
            return;
        }
        access.read(sender, holder, (values, error) -> {
            if (error != null) {
                failed(sender, holder, error);
                return;
            }
            FlagValue current = values.get(flag);
            if (current.setKnown()) {
                if (give && Boolean.TRUE.equals(current.set())) {
                    send(sender, Messages.CREATIVE_MANAGE_ALREADY, "{holder}", holder.describe(), "{flag}", flag.key());
                    return;
                }
                if (!give && current.set() == null) {
                    send(sender, Messages.CREATIVE_MANAGE_NOT_SET, "{holder}", holder.describe(), "{flag}", flag.key());
                    return;
                }
            }
            access.write(sender, holder, flag, give, failure -> {
                if (failure != null) {
                    failed(sender, holder, failure);
                } else {
                    send(sender, give ? Messages.CREATIVE_MANAGE_ADDED : Messages.CREATIVE_MANAGE_REMOVED,
                            "{flag}", flag.key(), "{holder}", holder.describe());
                }
            });
        });
    }

    private void failed(CommandSender sender, CreativeHolder holder, Throwable error) {
        if (error instanceof IllegalArgumentException && holder.isGroup()) {
            send(sender, Messages.CREATIVE_MANAGE_UNKNOWN_GROUP, "{group}", holder.name());
        } else if (error instanceof UnsupportedOperationException) {
            send(sender, Messages.CREATIVE_MANAGE_UNAVAILABLE);
        } else {
            send(sender, Messages.CREATIVE_MANAGE_FAILED, "{holder}", holder.describe(), "{reason}", String.valueOf(error.getMessage()));
        }
    }

    // -- Completion --------------------------------------------------------------------------------

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(CreativeAccess.PERMISSION_MANAGE) || args.length == 0) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values;
        if (args.length == 1) {
            values = List.of("gui", "active", "player", "group");
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean holder = sub.equals("player") || sub.equals("group");
            if (!holder) {
                return List.of();
            }
            if (args.length == 2) {
                if (sub.equals("group")) {
                    values = access.backend().groups();
                } else {
                    return plugin.getPlayerNames() == null ? List.of()
                            : plugin.getPlayerNames().complete(sender, args[1], 50);
                }
            } else if (args.length == 3) {
                values = ACTIONS;
            } else if (args.length == 4 && List.of("add", "remove", "check").contains(args[2].toLowerCase(Locale.ROOT))) {
                values = CreativeFlag.keys();
            } else {
                return List.of();
            }
        }
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(64).toList();
    }

    private void send(CommandSender sender, Messages message, String... replacements) {
        plugin.getMessageManager().send(sender, message, replacements);
    }
}
