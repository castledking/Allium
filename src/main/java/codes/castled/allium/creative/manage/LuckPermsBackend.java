package codes.castled.allium.creative.manage;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.util.Tristate;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Creative flags in LuckPerms. Isolated so LuckPerms classes load only when LuckPerms runs. Reads use
 * the holder's own global nodes and its cached permission data; writes change only the global
 * node, then record a {@code /lp log} entry and push the update to other servers, as an
 * {@code lp ... permission set} command would.
 */
final class LuckPermsBackend implements PermissionBackend {

    private static final UUID CONSOLE = new UUID(0L, 0L);

    private final LuckPerms api;

    LuckPermsBackend(LuckPerms api) {
        this.api = api;
    }

    /** The backend, or null when LuckPerms is not running. */
    @Nullable
    static LuckPermsBackend find(Server server) {
        Plugin plugin = server.getPluginManager().getPlugin("LuckPerms");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            return new LuckPermsBackend(LuckPermsProvider.get());
        } catch (IllegalStateException | LinkageError unavailable) {
            return null;
        }
    }

    @Override
    public String name() {
        return "LuckPerms";
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean knowsSetNodes() {
        return true;
    }

    @Override
    public java.util.List<String> groups() {
        return api.getGroupManager().getLoadedGroups().stream().map(Group::getName).sorted().toList();
    }

    @Override
    @Nullable
    public String group(String name) {
        Group group = api.getGroupManager().getGroup(name.toLowerCase(Locale.ROOT));
        return group == null ? null : group.getName();
    }

    @Override
    public CompletableFuture<Map<CreativeFlag, FlagValue>> read(CreativeHolder holder) {
        if (holder.isGroup()) {
            Group group = api.getGroupManager().getGroup(holder.name().toLowerCase(Locale.ROOT));
            if (group == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("no group " + holder.name()));
            }
            return CompletableFuture.completedFuture(values(group, false));
        }
        User loaded = api.getUserManager().getUser(holder.uuid());
        CompletableFuture<User> user = loaded != null
                ? CompletableFuture.completedFuture(loaded)
                : api.getUserManager().loadUser(holder.uuid(), holder.name());
        return user.thenApply(u -> values(u, true));
    }

    /** Undefined user flags come back with a null effective value and source {@code default}. */
    private static Map<CreativeFlag, FlagValue> values(PermissionHolder holder, boolean user) {
        // The holder's own default query options: a group's global data, a user's current contexts.
        CachedPermissionData data = holder.getCachedData().getPermissionData();
        boolean bypass = data.checkPermission(CreativeFlag.BYPASS.node()) == Tristate.TRUE;
        Map<CreativeFlag, FlagValue> out = new EnumMap<>(CreativeFlag.class);
        for (CreativeFlag flag : CreativeFlag.values()) {
            Boolean set = own(holder, flag.node());
            Tristate state = data.checkPermission(flag.node());
            FlagValue value;
            if (state != Tristate.UNDEFINED) {
                value = new FlagValue(set, state.asBoolean(), true, set != null ? "set here" : "inherited");
            } else if (bypass && flag.coveredByBypass()) {
                value = new FlagValue(set, true, true, "bypass");
            } else if (user) {
                value = new FlagValue(set, null, true, "default");
            } else {
                value = new FlagValue(set, false, true, "not set");
            }
            out.put(flag, value);
        }
        return out;
    }

    @Nullable
    private static Boolean own(PermissionHolder holder, String node) {
        for (Node candidate : holder.getNodes()) {
            if (candidate.getKey().equals(node) && candidate.getContexts().isEmpty()) {
                return candidate.getValue();
            }
        }
        return null;
    }

    @Override
    public Boolean holds(CreativeHolder holder, String permission) {
        if (holder.isGroup()) {
            return null;
        }
        net.luckperms.api.model.user.User user = api.getUserManager().getUser(holder.uuid());
        if (user == null) {
            return null;
        }
        // A result with no node came from LuckPerms' operator or default-permission processors.
        var result = user.getCachedData().getPermissionData().queryPermission(permission);
        return result.result() == net.luckperms.api.util.Tristate.TRUE && result.node() != null;
    }

    @Override
    public CompletableFuture<Void> writeNode(CreativeHolder holder, String permission, boolean give, Actor actor) {
        Consumer<PermissionHolder> change = target -> {
            target.data().clear(node -> node.getKey().equals(permission) && node.getContexts().isEmpty());
            if (give) {
                target.data().add(PermissionNode.builder(permission).value(true).build());
            }
        };
        CompletableFuture<Void> saved = holder.isGroup()
                ? api.getGroupManager().modifyGroup(holder.name().toLowerCase(Locale.ROOT), change::accept)
                : api.getUserManager().modifyUser(holder.uuid(), change::accept);
        return saved.thenCompose(done -> {
            Action.Builder builder = api.getActionLogger().actionBuilder()
                    .timestamp(Instant.now())
                    .source(actor.uuid() == null ? CONSOLE : actor.uuid())
                    .sourceName(actor.name())
                    .targetType(holder.isGroup() ? Action.Target.Type.GROUP : Action.Target.Type.USER)
                    .targetName(holder.isGroup() ? holder.name().toLowerCase(Locale.ROOT) : holder.name())
                    .description(give ? "permission set " + permission + " true" : "permission unset " + permission);
            if (!holder.isGroup()) {
                builder.target(holder.uuid());
            }
            Action action = builder.build();
            api.getMessagingService().ifPresent(messaging -> {
                if (holder.isGroup()) {
                    messaging.pushUpdate();
                } else {
                    api.getUserManager().loadUser(holder.uuid()).thenAccept(messaging::pushUserUpdate);
                }
            });
            return api.getActionLogger().submit(action);
        });
    }
}
