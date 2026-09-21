package codes.castled.allium.managers.chat;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordReadyEvent;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import github.scarsz.discordsrv.dependencies.jda.api.events.RawGatewayEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.jda.api.utils.data.DataArray;
import github.scarsz.discordsrv.dependencies.jda.api.utils.data.DataObject;
import github.scarsz.discordsrv.dependencies.jda.internal.JDAImpl;
import github.scarsz.discordsrv.dependencies.jda.internal.requests.Method;
import github.scarsz.discordsrv.dependencies.jda.internal.requests.RestActionImpl;
import github.scarsz.discordsrv.dependencies.jda.internal.requests.Route;
import github.scarsz.discordsrv.dependencies.jda.internal.utils.config.SessionConfig;
import github.scarsz.discordsrv.dependencies.jda.internal.utils.config.flags.ConfigFlag;
import github.scarsz.discordsrv.util.DiscordUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.SchedulerAdapter;

import static codes.castled.allium.managers.core.Text.DebugSeverity.INFO;
import static codes.castled.allium.managers.core.Text.DebugSeverity.WARN;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a "Ban Player" entry under Apps when staff right-click a message in
 * Discord (relayed chat, joins, leaves, deaths, advancements). It opens a
 * dialog for the player, duration and reason, then runs a console ban.
 *
 * DiscordSRV bundles JDA 4, which predates message context menus and modals,
 * so the command is registered and answered over raw REST using the bot's own
 * connection, and interactions are read from raw gateway events. The command
 * is global because DiscordSRV bulk-overwrites guild commands.
 */
public final class DiscordBanContextMenu {

    private static final String CONFIG = "discord-moderation.";
    private static final String MODAL_ID = "allium_ban";
    private static final int INTERACTION_APPLICATION_COMMAND = 2;
    private static final int INTERACTION_MODAL_SUBMIT = 5;
    private static final int COMMAND_TYPE_MESSAGE = 3;
    private static final int CALLBACK_MESSAGE = 4;
    private static final int CALLBACK_MODAL = 9;
    private static final int FLAG_EPHEMERAL = 64;
    private static final long PERMISSION_ADMINISTRATOR = 1L << 3;
    private static final Pattern PLAYER_NAME = Pattern.compile("[.A-Za-z0-9_]{1,32}");
    private static final Pattern DURATION = Pattern.compile("[A-Za-z0-9]{1,16}");
    private static final Pattern NAME_TOKEN = Pattern.compile("[.A-Za-z0-9_]{3,17}");

    private final PluginStart plugin;
    private Object listener;
    private volatile JDA hookedJda;
    private volatile boolean subscribed;
    private String registeredName = "";

    public DiscordBanContextMenu(PluginStart plugin) {
        this.plugin = plugin;
        retryHook();
    }

    public void retryHook() {
        try {
            Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (discordSrv == null || !discordSrv.isEnabled()) {
                return;
            }
            if (!subscribed) {
                DiscordSRV.api.subscribe(this);
                subscribed = true;
            }
            sync();
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "[DiscordBan] unavailable: " + t.getMessage());
        }
    }

    public void shutdown() {
        try {
            if (hookedJda != null && listener != null) {
                hookedJda.removeEventListener(listener);
            }
            if (subscribed) {
                DiscordSRV.api.unsubscribe(this);
            }
        } catch (Throwable ignored) {
        }
        hookedJda = null;
        subscribed = false;
    }

    @Subscribe
    public void onDiscordReady(DiscordReadyEvent event) {
        sync();
    }

    /**
     * Registers (or removes) the message command to match the config and makes
     * sure interactions from the current JDA session reach this handler.
     */
    public synchronized void sync() {
        try {
            JDA jda = DiscordUtil.getJda();
            if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
                return;
            }
            String appId = jda.getSelfUser().getApplicationId();
            String name = commandName();
            boolean enabled = !staffRoleId().isEmpty();

            if (enabled && jda != hookedJda) {
                if (hookedJda != null && listener != null) {
                    hookedJda.removeEventListener(listener);
                }
                enableRawEvents(jda);
                if (listener == null) {
                    listener = new RawInteractionListener();
                }
                jda.addEventListener(listener);
                hookedJda = jda;
            }

            if (enabled) {
                DataObject body = DataObject.empty()
                        .put("name", name)
                        .put("type", COMMAND_TYPE_MESSAGE)
                        .put("dm_permission", false);
                new RestActionImpl<Void>(jda, Route.custom(Method.POST, "applications/{application_id}/commands").compile(appId), body)
                        .queue(ok -> plugin.getLogger().info("[DiscordBan] Registered \"" + name + "\" message command."),
                                error -> Text.sendDebugLog(WARN, "[DiscordBan] Failed to register message command: " + error.getMessage()));
            }

            String stale = enabled ? (registeredName.isEmpty() || registeredName.equals(name) ? "" : registeredName) : name;
            if (!stale.isEmpty()) {
                deleteCommand(jda, appId, stale);
            }
            registeredName = enabled ? name : "";
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "[DiscordBan] Failed to sync message command: " + t.getMessage());
        }
    }

    private void deleteCommand(JDA jda, String appId, String name) {
        new RestActionImpl<DataArray>(jda, Route.custom(Method.GET, "applications/{application_id}/commands").compile(appId),
                (response, request) -> response.getArray())
                .queue(commands -> {
                    for (int i = 0; i < commands.length(); i++) {
                        DataObject command = commands.getObject(i);
                        if (command.getInt("type", 1) == COMMAND_TYPE_MESSAGE && name.equals(command.getString("name", ""))) {
                            new RestActionImpl<Void>(jda, Route.custom(Method.DELETE, "applications/{application_id}/commands/{command_id}")
                                    .compile(appId, command.getString("id"))).queue();
                        }
                    }
                }, error -> Text.sendDebugLog(WARN, "[DiscordBan] Failed to list message commands: " + error.getMessage()));
    }

    // JDA only emits raw gateway events when the session was built with them on;
    // DiscordSRV builds it without, so flip the flag on the live session.
    private static void enableRawEvents(JDA jda) throws ReflectiveOperationException {
        if (!(jda instanceof JDAImpl impl) || impl.isRawEvents()) {
            return;
        }
        Field field = JDAImpl.class.getDeclaredField("sessionConfig");
        field.setAccessible(true);
        ((SessionConfig) field.get(impl)).getFlags().add(ConfigFlag.RAW_EVENTS);
    }

    private final class RawInteractionListener extends ListenerAdapter {
        @Override
        public void onRawGateway(RawGatewayEvent event) {
            if (!"INTERACTION_CREATE".equals(event.getType())) {
                return;
            }
            try {
                handleInteraction(event.getPayload());
            } catch (Throwable t) {
                Text.sendDebugLog(WARN, "[DiscordBan] Interaction handling failed: " + t.getMessage());
            }
        }
    }

    private void handleInteraction(DataObject interaction) {
        String staffRoleId = staffRoleId();
        DataObject data = interaction.optObject("data").orElse(null);
        if (staffRoleId.isEmpty() || data == null || !interaction.hasKey("guild_id")) {
            return;
        }

        int type = interaction.getInt("type", 0);
        if (type == INTERACTION_APPLICATION_COMMAND) {
            if (data.getInt("type", 1) != COMMAND_TYPE_MESSAGE || !commandName().equals(data.getString("name", ""))) {
                return;
            }
            if (!isStaff(interaction, staffRoleId)) {
                reply(interaction, "You need the staff role to ban players.");
                return;
            }
            String targetId = data.getString("target_id", "");
            DataObject target = data.optObject("resolved")
                    .flatMap(resolved -> resolved.optObject("messages"))
                    .flatMap(messages -> messages.optObject(targetId))
                    .orElse(null);
            openModal(interaction, target == null ? "" : resolvePlayer(target));
        } else if (type == INTERACTION_MODAL_SUBMIT && MODAL_ID.equals(data.getString("custom_id", ""))) {
            if (!isStaff(interaction, staffRoleId)) {
                reply(interaction, "You need the staff role to ban players.");
                return;
            }
            submitBan(interaction, modalValues(data));
        }
    }

    private boolean isStaff(DataObject interaction, String staffRoleId) {
        DataObject member = interaction.optObject("member").orElse(null);
        if (member == null) {
            return false;
        }
        try {
            if ((Long.parseLong(member.getString("permissions", "0")) & PERMISSION_ADMINISTRATOR) != 0) {
                return true;
            }
        } catch (NumberFormatException ignored) {
        }

        Guild guild = hookedJda == null ? null : hookedJda.getGuildById(interaction.getString("guild_id"));
        Role staffRole = guild == null ? null : guild.getRoleById(staffRoleId);
        if (staffRole == null) {
            return false;
        }
        DataArray roles = member.optArray("roles").orElse(DataArray.empty());
        for (int i = 0; i < roles.length(); i++) {
            Role role = guild.getRoleById(roles.getString(i));
            if (role != null && role.getPosition() >= staffRole.getPosition()) {
                return true;
            }
        }
        return false;
    }

    private String resolvePlayer(DataObject message) {
        DiscordSrvMessageBridge bridge = plugin.getDiscordSrvMessageBridge();
        if (bridge != null) {
            UUID sender = bridge.findSenderByDiscordMessageId(message.getString("id", ""));
            String name = sender == null ? null : Bukkit.getOfflinePlayer(sender).getName();
            if (name != null) {
                return name;
            }
        }
        return findPlayerName(messageTexts(message), DiscordBanContextMenu::lookupPlayer);
    }

    private static String lookupPlayer(String candidate) {
        Player online = Bukkit.getPlayerExact(candidate);
        if (online != null) {
            return online.getName();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (candidate.equalsIgnoreCase(PlainTextComponentSerializer.plainText().serialize(player.displayName()))) {
                return player.getName();
            }
        }
        if (!NAME_TOKEN.matcher(candidate).matches()) {
            return null;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(candidate);
        return cached == null ? null : cached.getName();
    }

    private void openModal(DataObject interaction, String player) {
        DataArray rows = DataArray.empty()
                .add(row(textInput("player", "Player", 1, true, 32, player, "")))
                .add(row(textInput("duration", "Duration (blank = permanent)", 1, false, 16, "", "e.g. 30m, 7d, 1mo")))
                .add(row(textInput("reason", "Reason", 2, false, 400, "", defaultReason())));
        DataObject data = DataObject.empty()
                .put("custom_id", MODAL_ID)
                .put("title", truncate(commandName(), 45))
                .put("components", rows);
        respond(interaction, DataObject.empty().put("type", CALLBACK_MODAL).put("data", data));
    }

    private void submitBan(DataObject interaction, Map<String, String> values) {
        String player = values.getOrDefault("player", "").strip();
        String duration = values.getOrDefault("duration", "").strip();
        String reason = cleanReason(values.getOrDefault("reason", ""));
        if (!PLAYER_NAME.matcher(player).matches()) {
            reply(interaction, "That isn't a valid player name.");
            return;
        }
        if (!duration.isEmpty() && !DURATION.matcher(duration).matches()) {
            reply(interaction, "Duration must be letters and digits only, like 30m, 7d or 1mo.");
            return;
        }

        String exemptGroup = bannedGroup(player);
        if (exemptGroup != null) {
            reply(interaction, "Refused to ban " + player + " - they are in the ban-exempt group \"" + exemptGroup + "\".");
            return;
        }

        String staff = staffName(interaction);
        if (reason.isEmpty()) {
            reason = defaultReason();
        }
        String template = duration.isEmpty()
                ? plugin.getConfig().getString(CONFIG + "ban-command", "ban {player} {reason}")
                : plugin.getConfig().getString(CONFIG + "tempban-command", "tempban {player} {duration} {reason}");
        String command = buildCommand(template, player, duration, reason, staff);

        plugin.getLogger().info("[DiscordBan] " + staff + " banned via Discord: /" + command);
        SchedulerAdapter.run(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        reply(interaction, "Ran `/" + command.replace("`", "'") + "` on the server.");
    }

    private void reply(DataObject interaction, String content) {
        DataObject data = DataObject.empty()
                .put("content", content)
                .put("flags", FLAG_EPHEMERAL)
                .put("allowed_mentions", DataObject.empty().put("parse", DataArray.empty()));
        respond(interaction, DataObject.empty().put("type", CALLBACK_MESSAGE).put("data", data));
    }

    private void respond(DataObject interaction, DataObject body) {
        Route.CompiledRoute route = Route.custom(Method.POST, "interactions/{interaction_id}/{interaction_token}/callback")
                .compile(interaction.getString("id"), interaction.getString("token"));
        new RestActionImpl<Void>(hookedJda, route, body)
                .queue(null, error -> Text.sendDebugLog(WARN, "[DiscordBan] Interaction response failed: " + error.getMessage()));
    }

    private String staffRoleId() {
        return plugin.getConfig().getString(CONFIG + "staff-role-id", "").strip();
    }

    private String commandName() {
        return truncate(plugin.getConfig().getString(CONFIG + "command-name", "Ban Player").strip(), 32);
    }

    private String defaultReason() {
        return plugin.getConfig().getString(CONFIG + "default-reason", "Banned by staff via Discord");
    }

    /**
     * Returns the player's Vault group that is listed under {@code ban-exempt-groups},
     * or null if they should be allowed to be banned.
     */
    private String bannedGroup(String playerName) {
        List<String> exempt = plugin.getConfig().getStringList(CONFIG + "ban-exempt-groups");
        if (exempt.isEmpty() || plugin.getVaultPermission() == null) {
            return null;
        }
        try {
            Permission vaultPerms = (net.milkbowl.vault.permission.Permission) plugin.getVaultPermission();
            return matchesExemptGroup(vaultPerms.getPlayerGroups((String) null, playerName), exempt);
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "[DiscordBan] Ban-exempt group check failed for " + playerName + ": " + t.getMessage());
            return null;
        }
    }

    /** First Vault group that appears in the exempt list (case-insensitive), else null. */
    static String matchesExemptGroup(String[] groups, List<String> exemptGroups) {
        if (groups == null || exemptGroups == null || exemptGroups.isEmpty()) {
            return null;
        }
        for (String group : groups) {
            if (group == null) {
                continue;
            }
            for (String exempt : exemptGroups) {
                if (exempt != null && group.equalsIgnoreCase(exempt.strip())) {
                    return group;
                }
            }
        }
        return null;
    }

    private static String staffName(DataObject interaction) {
        return interaction.optObject("member")
                .flatMap(member -> member.optObject("user"))
                .map(user -> user.getString("username", "unknown"))
                .orElse("unknown");
    }

    private static DataObject row(DataObject component) {
        return DataObject.empty().put("type", 1).put("components", DataArray.empty().add(component));
    }

    private static DataObject textInput(String id, String label, int style, boolean required, int maxLength, String value, String placeholder) {
        DataObject input = DataObject.empty()
                .put("type", 4)
                .put("custom_id", id)
                .put("label", label)
                .put("style", style)
                .put("required", required)
                .put("max_length", maxLength);
        if (!value.isEmpty()) {
            input.put("value", truncate(value, maxLength));
        }
        if (!placeholder.isEmpty()) {
            input.put("placeholder", truncate(placeholder, 100));
        }
        return input;
    }

    /** Text fields of a relayed message, most specific first: embeds, webhook name, content. */
    static List<String> messageTexts(DataObject message) {
        List<String> texts = new ArrayList<>();
        DataArray embeds = message.optArray("embeds").orElse(DataArray.empty());
        for (int i = 0; i < embeds.length(); i++) {
            DataObject embed = embeds.getObject(i);
            embed.optObject("author").ifPresent(author -> texts.add(author.getString("name", "")));
            texts.add(embed.getString("title", ""));
            texts.add(embed.getString("description", ""));
        }
        if (message.hasKey("webhook_id")) {
            message.optObject("author").ifPresent(author -> texts.add(author.getString("username", "")));
        }
        texts.add(message.getString("content", ""));
        return texts;
    }

    /**
     * First player found in the texts: a whole text that names a player (e.g. a
     * webhook nickname), otherwise the first name-shaped word that does.
     */
    static String findPlayerName(List<String> texts, Function<String, String> lookup) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // DiscordSRV escapes markdown in names, e.g. Some\_Name.
            String unescaped = text.replace("\\", "").strip();
            String whole = lookup.apply(unescaped);
            if (whole != null) {
                return whole;
            }
            Matcher matcher = NAME_TOKEN.matcher(unescaped);
            while (matcher.find()) {
                String name = lookup.apply(matcher.group());
                if (name != null) {
                    return name;
                }
            }
        }
        return "";
    }

    static Map<String, String> modalValues(DataObject data) {
        Map<String, String> values = new HashMap<>();
        DataArray rows = data.optArray("components").orElse(DataArray.empty());
        for (int i = 0; i < rows.length(); i++) {
            DataArray components = rows.getObject(i).optArray("components").orElse(DataArray.empty());
            for (int j = 0; j < components.length(); j++) {
                DataObject component = components.getObject(j);
                values.put(component.getString("custom_id", ""), component.getString("value", ""));
            }
        }
        return values;
    }

    static String cleanReason(String reason) {
        String cleaned = reason.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return truncate(cleaned, 400);
    }

    static String buildCommand(String template, String player, String duration, String reason, String staff) {
        String command = template
                .replace("{player}", player)
                .replace("{duration}", duration)
                .replace("{reason}", reason.replace("{staff}", staff))
                .replace("{staff}", staff);
        command = command.replaceAll("\\s+", " ").strip();
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
