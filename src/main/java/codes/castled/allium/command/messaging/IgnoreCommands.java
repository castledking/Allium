package codes.castled.allium.command.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagingService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code /ignore [player]} and {@code /unignore <player>}. */
public final class IgnoreCommands {

    private IgnoreCommands() {
    }

    private static String nameOf(UUID id) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(id);
        return player.getName() != null ? player.getName() : id.toString();
    }

    public static final class Ignore implements SpecCommandHandler {
        private final PluginStart plugin;
        private final MessagingService messaging;
        private final IgnoreService ignores;

        public Ignore(PluginStart plugin, MessagingService messaging, IgnoreService ignores) {
            this.plugin = plugin;
            this.messaging = messaging;
            this.ignores = ignores;
        }

        @Override
        public CommandSpec spec() {
            return CoreCommandSpecs.IGNORE;
        }

        @Override
        public void execute(BukkitCommandContext context) {
            if (!(context.sender() instanceof Player player)) {
                plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
                return;
            }
            Set<UUID> list = ignores.list(player.getUniqueId());
            if (list == null) {
                plugin.getMessageManager().send(player, Messages.PREFERENCES_UNAVAILABLE, "{player}", player.getName());
                return;
            }
            if (context.args().length == 0) {
                if (list.isEmpty()) {
                    plugin.getMessageManager().send(player, Messages.IGNORE_LIST_EMPTY);
                } else {
                    List<String> names = new ArrayList<>();
                    list.forEach(id -> names.add(nameOf(id)));
                    plugin.getMessageManager().send(player, Messages.IGNORE_LIST, "{players}", String.join(", ", names));
                }
                return;
            }
            String name = context.args()[0];
            Player target = messaging.find(player, name);
            if (target == null || !messaging.visibleTo(player, target)) {
                plugin.getMessageManager().send(player, Messages.PLAYER_NOT_FOUND, "{player}", MessagingService.safeName(name));
                return;
            }
            if (target.equals(player)) {
                plugin.getMessageManager().send(player, Messages.IGNORE_SELF);
                return;
            }
            String shown = target.getName();
            switch (ignores.add(player.getUniqueId(), target.getUniqueId(), saved -> {
                if (!saved && player.isOnline()) {
                    plugin.getMessageManager().send(player, Messages.IGNORE_FAILED);
                }
            })) {
                case DONE -> plugin.getMessageManager().send(player, Messages.IGNORE_ADDED, "{player}", shown);
                case UNCHANGED -> plugin.getMessageManager().send(player, Messages.IGNORE_ALREADY, "{player}", shown);
                case FULL -> plugin.getMessageManager().send(player, Messages.IGNORE_FULL);
                case NOT_LOADED -> plugin.getMessageManager().send(player, Messages.PREFERENCES_UNAVAILABLE, "{player}", player.getName());
            }
        }
    }

    public static final class Unignore implements SpecCommandHandler {
        private final PluginStart plugin;
        private final IgnoreService ignores;

        public Unignore(PluginStart plugin, IgnoreService ignores) {
            this.plugin = plugin;
            this.ignores = ignores;
        }

        @Override
        public CommandSpec spec() {
            return CoreCommandSpecs.UNIGNORE;
        }

        @Override
        public void execute(BukkitCommandContext context) {
            if (!(context.sender() instanceof Player player)) {
                plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
                return;
            }
            if (context.args().length < 1) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            Set<UUID> list = ignores.list(player.getUniqueId());
            if (list == null) {
                plugin.getMessageManager().send(player, Messages.PREFERENCES_UNAVAILABLE, "{player}", player.getName());
                return;
            }
            String name = context.args()[0];
            UUID match = list.stream().filter(id -> nameOf(id).equalsIgnoreCase(name)).findFirst().orElse(null);
            if (match == null) {
                plugin.getMessageManager().send(player, Messages.IGNORE_NOT_IGNORED, "{player}", MessagingService.safeName(name));
                return;
            }
            String shown = nameOf(match);
            ignores.remove(player.getUniqueId(), match, saved -> {
                if (!saved && player.isOnline()) {
                    plugin.getMessageManager().send(player, Messages.IGNORE_FAILED);
                }
            });
            plugin.getMessageManager().send(player, Messages.IGNORE_REMOVED, "{player}", shown);
        }
    }
}
