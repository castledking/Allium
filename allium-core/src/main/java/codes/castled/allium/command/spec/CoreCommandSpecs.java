package codes.castled.allium.command.spec;

import java.util.List;
import java.util.Map;

/**
 * Canonical platform-neutral command specifications for Allium's core command families.
 */
public final class CoreCommandSpecs {

    /** One-line /help descriptions for the generated command families (matching plugin.yml). */
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("tpa", "Ask to teleport to a player"),
            Map.entry("tpahere", "Ask a player to teleport to you"),
            Map.entry("tpaccept", "Accept a teleport request"),
            Map.entry("tpdeny", "Deny a teleport request"),
            Map.entry("tpacancel", "Cancel your outgoing teleport request"),
            Map.entry("tptoggle", "Allow or block teleport requests to you"),
            Map.entry("back", "Return to your previous location"),
            Map.entry("spawn", "Teleport to spawn"),
            Map.entry("setspawn", "Set the spawn point here"),
            Map.entry("tp", "Teleport to a player or coordinates, or move a player"),
            Map.entry("tppos", "Teleport to coordinates"),
            Map.entry("tphere", "Teleport a player to you"),
            Map.entry("tpo", "Teleport to or move a player, bypassing /tptoggle"),
            Map.entry("tpohere", "Teleport a player to you, bypassing /tptoggle"),
            Map.entry("tpall", "Teleport all online players to your location, bypassing /tptoggle"),
            Map.entry("tpoffline", "Teleport to where an offline player logged out"),
            Map.entry("there", "Teleport to the block you are looking at, or send selected entities there"),
            Map.entry("top", "Teleport to the highest block above you"),
            Map.entry("bottom", "Teleport to the lowest block below you"),
            Map.entry("tppet", "Toggle selecting your own pets for /tp"),
            Map.entry("tpent", "Toggle selecting mobs and other entities for /tp"),
            Map.entry("sethome", "Set a home here"),
            Map.entry("home", "Teleport to one of your homes"),
            Map.entry("delhome", "Delete one of your homes"),
            Map.entry("renamehome", "Rename one of your homes"),
            Map.entry("homes", "List your homes"),
            Map.entry("setwarp", "Create or move a warp here"),
            Map.entry("warp", "Teleport to a warp, or send a player to a warp"),
            Map.entry("warpinfo", "Show a warp's location and permission"),
            Map.entry("delwarp", "Delete a warp"),
            Map.entry("warps", "List the warps you can use"),
            Map.entry("kick", "Kick a player with a reason"),
            Map.entry("ban", "Ban a player permanently"),
            Map.entry("tempban", "Ban a player for a set time"),
            Map.entry("unban", "Lift a player's ban"),
            Map.entry("banlist", "List active bans"),
            Map.entry("ipban", "Ban a player's IP address"),
            Map.entry("freeze", "Freeze or unfreeze a player in place"),
            Map.entry("setjail", "Create or move a jail here"),
            Map.entry("jail", "Send a player to a jail"),
            Map.entry("unjail", "Release a player from jail"),
            Map.entry("warn", "Warn a player and record it"),
            Map.entry("notes", "Read or add staff notes on a player"),
            Map.entry("balance", "Show your (or a player's) balance"),
            Map.entry("pay", "Pay money to a player"),
            Map.entry("paytoggle", "Allow or refuse incoming payments"),
            Map.entry("eco", "Give, take or set player balances"),
            Map.entry("baltop", "Show the richest accounts"),
            Map.entry("cheque", "Write a redeemable money cheque"),
            Map.entry("tax", "Inspect, impose, list or refund server taxes"),
            Map.entry("nick", "Set or clear your nickname (or another player's)"),
            Map.entry("channel", "Join, leave or write to a chat channel"),
            Map.entry("delmsg", "Delete a chat message for everyone"),
            Map.entry("action", "Describe what you are doing in chat"));

    private static String describe(String command, String fallback) {
        return DESCRIPTIONS.getOrDefault(command, fallback);
    }


    public static final CommandSpec HEAL = new CommandSpec(
            "heal",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of(),
            "Heal yourself or another player",
            "/heal [player]",
            "/heal <player>",
            "allium.heal",
            "allium.heal.others",
            List.of(
                    CommandArgSpec.optional(
                            "player",
                            "allium.heal.others",
                            SuggestionSpec.onlinePlayers("allium.heal.others")
                    )
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "self", "player.heal",
                    "other", "player.heal-other"
            ))
    );

    public static final CommandSpec FEED = new CommandSpec(
            "feed",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of(),
            "Feed yourself or another player",
            "/feed [player]",
            "/feed <player>",
            "allium.feed",
            "allium.feed.others",
            List.of(
                    CommandArgSpec.optional(
                            "player",
                            "allium.feed.others",
                            SuggestionSpec.onlinePlayers("allium.feed.others")
                    )
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "self", "player.feed",
                    "other", "player.feed-other"
            ))
    );

    public static final CommandSpec FLY = new CommandSpec(
            "fly",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of(),
            "Toggle flight mode",
            "/fly [player] | /fly <player> <set|add|take|reset> [time]",
            "/fly <player> | /fly <player> <set|add|take|reset> [time]",
            // allium.fly includes allium.tfly; without allium.fly, /fly is timed flight.
            "allium.tfly",
            "allium.fly.others",
            List.of(
                    CommandArgSpec.optional(
                            "player",
                            "allium.fly.others",
                            SuggestionSpec.onlinePlayers("allium.fly.others")
                    ),
                    CommandArgSpec.optional("action", "allium.tfly.add",
                            SuggestionSpec.literals(List.of("add", "take", "set", "reset"), "allium.tfly.add")),
                    CommandArgSpec.optional("time", "allium.tfly.add",
                            SuggestionSpec.literals(List.of("10m", "30m", "1h", "1d"), "allium.tfly.add"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "toggle", "player.fly-toggle"
            ))
    );

    public static final CommandSpec TOGGLE_SLOW_FALL = new CommandSpec("toggleslowfall",CommandSpec.DEFAULT_NAMESPACE,"essentials",List.of("tsf","slowfalltoggle","toggleslowfalling"),"Toggle slow falling on flight loss","/toggleslowfall [on|off|status]","/toggleslowfall [on|off|status]","allium.fly",null,List.of(CommandArgSpec.optional("state",null,SuggestionSpec.literals(List.of("on","off","status"),"allium.fly"))),List.of(),CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec GOD = new CommandSpec(
            "god",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of("godmode", "tgm"),
            "Toggle god mode",
            "/god [player]",
            "/god <player>",
            "allium.god",
            "allium.god.others",
            List.of(
                    CommandArgSpec.optional(
                            "player",
                            "allium.god.others",
                            SuggestionSpec.onlinePlayers("allium.god.others")
                    )
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "toggle", "player.god-toggle"
            ))
    );

    /** Fixed-mode aliases share the gamemode command family and its permission policy. */
    public static String gamemodeAlias(String label) {
        if(label==null)return null;
        String name=label.substring(label.lastIndexOf(':')+1).toLowerCase(java.util.Locale.ROOT);
        return switch(name){case "gmt"->"toggle";case "gmc","creative","creativemode"->"creative";case "gma","adventure","adventuremode"->"adventure";
            case "gms","survival","survivalmode"->"survival";case "gmsp","spectator","spectatormode"->"spectator";default->null;};
    }

    /** {@code /wspeed} and {@code /flyspeed} select walk and fly speed respectively. */
    public static String speedAlias(String label) {
        if(label==null)return null;
        String name=label.substring(label.lastIndexOf(':')+1).toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "wspeed", "walkspeed" -> "walk";
            case "fspeed", "flyspeed" -> "fly";
            default -> null;
        };
    }

    /** {@code /payon} and {@code /payoff} are fixed forms of {@code /paytoggle}. */
    public static String payToggleAlias(String label) {
        if (label == null) return null;
        String name = label.substring(label.lastIndexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (name) { case "payon" -> "on"; case "payoff" -> "off"; default -> null; };
    }

    /** {@code /payconfirmon} and {@code /payconfirmoff} are fixed forms of {@code /payconfirmtoggle}. */
    public static String payConfirmAlias(String label) {
        if (label == null) return null;
        String name = label.substring(label.lastIndexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (name) { case "payconfirmon" -> "on"; case "payconfirmoff" -> "off"; default -> null; };
    }
    public static final CommandSpec GAMEMODE = new CommandSpec(
            "gamemode",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of("gm","gmc","gma","gms","gmsp","gmt","creative","survival","spectator","adventure",
                    "creativemode","survivalmode","spectatormode","adventuremode"),
            "Change your gamemode",
            "/gamemode <mode> [player]",
            "/gamemode <mode> <player>",
            "allium.gamemode",
            "allium.gamemode.others",
            List.of(
                    CommandArgSpec.required(
                            "mode",
                            null,
                            SuggestionSpec.gamemodes()
                    ),
                    CommandArgSpec.optional(
                            "player",
                            "allium.gamemode.others",
                            SuggestionSpec.onlinePlayers("allium.gamemode.others")
                    )
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "self", "player.gamemode-self",
                    "other", "player.gamemode-other"
            ))
    );

    /**
     * M1 harness for the item library: resolves item specs through ItemService and delivers them
     * through ItemDeliveryService. Not yet the full M2 give (no kits, aliases or {@code /i}).
     */
    public static final CommandSpec GIVE = new CommandSpec(
            "give",
            CommandSpec.DEFAULT_NAMESPACE,
            "items",
            List.of(),
            "Give items (M1 item-library harness)",
            "/give <player> <item[,item...]> [amount] [key:value...] [-e] [--overflow=drop|refuse|partial]",
            "/give <player> <item[,item...]> [amount] [key:value...] [-e] [--overflow=drop|refuse|partial]",
            "allium.give",
            null,
            List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.required("item", null, SuggestionSpec.none())
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.ofEntries(
                    Map.entry("delivered", "item.give.delivered"),
                    Map.entry("dropped", "item.give.dropped"),
                    Map.entry("partial", "item.give.partial"),
                    Map.entry("degraded", "item.give.degraded"),
                    Map.entry("refused-unresolved", "item.give.refused-unresolved"),
                    Map.entry("refused", "item.give.refused"),
                    Map.entry("offline", "item.give.offline"),
                    Map.entry("cancelled", "item.give.cancelled"),
                    Map.entry("failed", "item.give.failed"),
                    Map.entry("invalid", "item.give.invalid"),
                    Map.entry("no-target", "item.give.no-target"),
                    Map.entry("unresolved", "item.give.unresolved"),
                    Map.entry("privileged", "item.give.privileged"),
                    Map.entry("pending", "item.give.pending"),
                    Map.entry("warning", "item.give.warning"),
                    Map.entry("received", "item.give.received")
            ))
    );

    /**
     * Self-use alias of {@link #GIVE}: uses the exact same {@code ItemSpecParser}, library,
     * delivery pipeline, and {@code allium.give} / {@code allium.give.privileged} permissions.
     */
    public static final CommandSpec ITEM = new CommandSpec(
            "i",
            CommandSpec.DEFAULT_NAMESPACE,
            "items",
            List.of("item"),
            "Give items to yourself",
            "/i <item[,item...]> [amount] [key:value...] [-e] [--overflow=drop|refuse|partial]",
            "/give <player> <item[,item...]> [amount] [key:value...] [-e] [--overflow=drop|refuse|partial]",
            "allium.give",
            null,
            List.of(
                    CommandArgSpec.required("item", null, SuggestionSpec.none())
            ),
            List.of(),
            GIVE.messageKeys()
    );

    public static final List<CommandSpec> ITEM_SPECS = List.of(GIVE, ITEM);

    /** {@code /allium}: plugin administration. {@code version} needs no permission; {@code reload} needs {@code allium.reload}. */
    public static final CommandSpec ALLIUM = new CommandSpec(
            "allium",
            CommandSpec.DEFAULT_NAMESPACE,
            "core",
            List.of("core"),
            "Allium administration",
            "/allium <reload|version|addon|sethomes|import <lang|vouchers|legacy|essentials>|escalate|adopt-window [<duration>|off]|hide|creativemanager|modguard>",
            "/allium <reload|version|addon|sethomes|import <lang|vouchers|legacy|essentials>|escalate|adopt-window [<duration>|off]|hide|creativemanager|modguard>",
            null,
            null,
            List.of(),
            List.of(
                    new CommandNodeSpec("addon", List.of("addons"), "allium.addon", "/allium addon <list|download|enable|disable> [addon]", List.of(), List.of(
                            new CommandNodeSpec("list", List.of(), "allium.addon", "/allium addon list", List.of(), List.of()),
                            new CommandNodeSpec("download", List.of("install"), "allium.addon", "/allium addon download <addon>",
                                    List.of(CommandArgSpec.required("addon", "allium.addon", SuggestionSpec.none())), List.of()),
                            new CommandNodeSpec("enable", List.of(), "allium.addon", "/allium addon enable <addon>",
                                    List.of(CommandArgSpec.required("addon", "allium.addon", SuggestionSpec.none())), List.of()),
                            new CommandNodeSpec("disable", List.of(), "allium.addon", "/allium addon disable <addon>",
                                    List.of(CommandArgSpec.required("addon", "allium.addon", SuggestionSpec.none())), List.of()))),
                    new CommandNodeSpec("hide", List.of(), "allium.hide.admin", "/allium hide <status|reload|enabled|group|resolve|fix|unfix>", List.of(), List.of()),
                    new CommandNodeSpec("modguard", List.of(), null, "/allium modguard <reload|alerts [on|off]>", List.of(), List.of(
                            new CommandNodeSpec("reload", List.of(), "allium.reload", "/allium modguard reload", List.of(), List.of()),
                            new CommandNodeSpec("alerts", List.of(), "modguard.notify", "/allium modguard alerts [on|off]",
                                    List.of(CommandArgSpec.optional("state", "modguard.notify", SuggestionSpec.literals(List.of("on", "off"), null))),
                                    List.of()))),
                    new CommandNodeSpec("creativemanager", List.of(), "allium.gamemode.creative.manage",
                            "/allium creativemanager [gui|active|player <player> [action]|group <group> [action]]", List.of(), List.of()),
                    new CommandNodeSpec("sethomes", List.of(), "allium.sethomes", "/allium sethomes <player> [number|+N|-N|-1]",
                            List.of(
                                    CommandArgSpec.required("player", "allium.sethomes", SuggestionSpec.knownPlayers("allium.sethomes")),
                                    CommandArgSpec.optional("limit", "allium.sethomes", SuggestionSpec.literals(List.of("-1", "+1", "+5", "3", "5", "10"), "allium.sethomes"))
                            ),
                            List.of()),
                    new CommandNodeSpec("reload", List.of(), "allium.reload", "/allium reload", List.of(), List.of()),
                    new CommandNodeSpec("version", List.of(), null, "/allium version", List.of(), List.of()),
                    new CommandNodeSpec("import", List.of(), "allium.import", "/allium import <tidy [apply]|config [apply]|lang [apply]|vouchers [apply]|legacy [apply <run>]|essentials [apply <run>]>", List.of(), List.of(
                            new CommandNodeSpec("tidy", List.of(), "allium.import", "/allium import tidy [apply]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null))),
                                    List.of()),
                            new CommandNodeSpec("config", List.of(), "allium.import", "/allium import config [apply]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null))),
                                    List.of()),
                            new CommandNodeSpec("lang", List.of(), "allium.import", "/allium import lang [apply]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null))),
                                    List.of()),
                            new CommandNodeSpec("vouchers", List.of(), "allium.import", "/allium import vouchers [apply]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null))),
                                    List.of()),
                            new CommandNodeSpec("legacy", List.of(), "allium.import", "/allium import legacy [apply <run>]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null)),
                                            CommandArgSpec.optional("run", "allium.import", SuggestionSpec.none())),
                                    List.of()),
                            new CommandNodeSpec("essentials", List.of(), "allium.import", "/allium import essentials [apply <run>]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null)),
                                            CommandArgSpec.optional("run", "allium.import", SuggestionSpec.none())),
                                    List.of()))),
                    new CommandNodeSpec("escalate", List.of(), "allium.escalate", "/allium escalate <staffmember> <target> <reason>",
                            List.of(), List.of()),
                    new CommandNodeSpec("adopt-window", List.of(), "allium.adoptwindow", "/allium adopt-window [<duration>|off]",
                            List.of(CommandArgSpec.optional("duration", "allium.adoptwindow", SuggestionSpec.literals(List.of("10m", "off"), null))),
                            List.of())
            ),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "reloaded", "core.reload-success",
                    "reload-failed", "core.reload-failed",
                    "version", "core.version"
            ))
    );

    public static final CommandSpec HELP = new CommandSpec("help",CommandSpec.DEFAULT_NAMESPACE,"core",List.of(),"Browse commands available to you","/help [page|command|plugin] [page]","/help [page|command|plugin] [page]","allium.help",null,List.of(),List.of(),CommandMessageKeys.ofOutcomes(Map.of()));
    public static final List<CommandSpec> CORE_SPECS = List.of(ALLIUM,HELP);

    /** {@code /msg} with Essentials' aliases. */
    public static final CommandSpec MSG = new CommandSpec(
            "msg", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of("tell", "w", "whisper", "m", "t", "pm"),
            "Send a private message", "/msg <player> <message>", "/msg <player> <message>", "allium.msg", null,
            List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.required("message", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec REPLY_TOGGLE = new CommandSpec(
            "rtoggle", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of("replytoggle"),
            "Choose whether reply uses your last sender or last recipient",
            "/rtoggle", "/rtoggle", "allium.rtoggle", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec REALNAME = new CommandSpec(
            "realname", CommandSpec.DEFAULT_NAMESPACE, "chat", List.of(),
            "Find a visible online player's account name from their nickname",
            "/realname <nickname>", "/realname <nickname>", "allium.realname", null,
            List.of(CommandArgSpec.required("nickname", null, SuggestionSpec.none())), List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec REPLY = new CommandSpec(
            "reply", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of("r"),
            "Reply to your last private message", "/reply <message>", "/reply <message>", "allium.reply", null,
            List.of(CommandArgSpec.required("message", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec IGNORE = new CommandSpec(
            "ignore", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of(),
            "Ignore a player's private messages, or list who you ignore", "/ignore [player]", "/ignore [player]", "allium.ignore", null,
            List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec UNIGNORE = new CommandSpec(
            "unignore", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of("delignore", "remignore", "rmignore"),
            "Stop ignoring a player", "/unignore <player>", "/unignore <player>", "allium.ignore", null,
            List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec MSGTOGGLE = new CommandSpec(
            "msgtoggle", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of(),
            "Turn receiving private messages on or off", "/msgtoggle [on|off] [player]", "/msgtoggle [on|off] <player>",
            "allium.msgtoggle", "allium.msgtoggle.others",
            List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                    CommandArgSpec.optional("player", "allium.msgtoggle.others", SuggestionSpec.onlinePlayers("allium.msgtoggle.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    /**
     * {@code /messagesounds} ({@code /msounds}, {@code /mfx}): the player's private-message sound
     * volume. No arguments reports it; {@code volume <0-1>}, {@code toggle} and {@code reset} change it.
     */
    public static final CommandSpec MESSAGESOUNDS = new CommandSpec(
            "messagesounds", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of("msounds", "mfx"),
            "Your private-message sound volume", "/mfx [volume <0-1>|toggle|reset]", "/mfx [volume <0-1>|toggle|reset]",
            "allium.messagesounds", null,
            List.of(),
            List.of(
                    new CommandNodeSpec("volume", List.of(), null, "/mfx volume <0-1>",
                            List.of(CommandArgSpec.required("volume", null, SuggestionSpec.literals(List.of("0", "0.25", "0.5", "0.75", "1"), null))),
                            List.of()),
                    new CommandNodeSpec("toggle", List.of(), null, "/mfx toggle", List.of(), List.of()),
                    new CommandNodeSpec("reset", List.of(), null, "/mfx reset", List.of(), List.of())),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec SOCIALSPY = new CommandSpec(
            "socialspy", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of(),
            "Observe delivered private messages between other players", "/socialspy [on|off]", "/socialspy [on|off]",
            "allium.socialspy", null,
            List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of(
                    "on", "messaging.socialspy.on", "off", "messaging.socialspy.off")));

    public static final List<CommandSpec> MESSAGING_SPECS = List.of(MSG, REPLY, REPLY_TOGGLE, IGNORE, UNIGNORE, MSGTOGGLE, MESSAGESOUNDS, SOCIALSPY);

    /**
     * {@code /mail}: text mail and gifts, to online and offline players. Targets complete from the
     * known-player index; the gift amount completes from common stack sizes.
     */
    public static final CommandSpec MAIL = new CommandSpec(
            "mail", CommandSpec.DEFAULT_NAMESPACE, "mail", List.of("memo"),
            "Send and read mail, and gifts of items", "/mail <read|send|gift|claim|delete|clear>",
            "/mail send <player> <message>",
            "allium.mail", null,
            List.of(),
            List.of(
                    new CommandNodeSpec("read", List.of(), "allium.mail", "/mail read", List.of(), List.of()),
                    new CommandNodeSpec("send", List.of(), "allium.mail.send", "/mail send <player> <message>",
                            List.of(CommandArgSpec.required("player", "allium.mail.send", SuggestionSpec.knownPlayers("allium.mail.send")),
                                    CommandArgSpec.required("message", "allium.mail.send", SuggestionSpec.none())),
                            List.of()),
                    new CommandNodeSpec("gift", List.of(), "allium.mail.gift", "/mail gift <player> [amount] [note]",
                            List.of(CommandArgSpec.required("player", "allium.mail.gift", SuggestionSpec.knownPlayers("allium.mail.gift")),
                                    CommandArgSpec.optional("amount", "allium.mail.gift",
                                            SuggestionSpec.literals(List.of("1", "8", "16", "32", "64"), null)),
                                    CommandArgSpec.optional("note", "allium.mail.gift", SuggestionSpec.none())),
                            List.of()),
                    new CommandNodeSpec("claim", List.of(), "allium.mail", "/mail claim <id|all>",
                            List.of(CommandArgSpec.required("id", "allium.mail", SuggestionSpec.mailIds(true))),
                            List.of()),
                    new CommandNodeSpec("delete", List.of(), "allium.mail", "/mail delete <id>",
                            List.of(CommandArgSpec.required("id", "allium.mail", SuggestionSpec.mailIds(false))), List.of()),
                    new CommandNodeSpec("clear", List.of(), "allium.mail", "/mail clear", List.of(), List.of())),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> MAIL_SPECS = List.of(MAIL);

    public static final CommandSpec KIT = new CommandSpec(
            "kit", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of(),
            "Claim a kit or list available kits",
            "/kit [name] [player]",
            "/kit <name> <player>",
            "allium.kit", "allium.kit.others",
            List.of(
                    CommandArgSpec.optional("name", null, SuggestionSpec.kitNames(false)),
                    CommandArgSpec.optional("player", "allium.kit.others", SuggestionSpec.onlinePlayers("allium.kit.others"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec KITS = new CommandSpec(
            "kits", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of(),
            "List available kits",
            "/kits",
            "/kits",
            "allium.kits", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec CREATEKIT = new CommandSpec(
            "createkit", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of("ck", "kc", "createk", "kitcreate"),
            "Create a kit from your inventory",
            "/createkit <name> [cooldown] [once]",
            "/createkit <name> [cooldown] [once]",
            "allium.createkit", null,
            List.of(
                    CommandArgSpec.required("name", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("cooldown", null, SuggestionSpec.literals(List.of("0", "60", "300", "3600", "86400"), null)),
                    CommandArgSpec.optional("once", null, SuggestionSpec.literals(List.of("once"), null))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec DELKIT = new CommandSpec(
            "delkit", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of("deletekit", "remkit", "rmkit"),
            "Delete a kit definition",
            "/delkit <name>",
            "/delkit <name>",
            "allium.delkit", null,
            List.of(
                    CommandArgSpec.required("name", null, SuggestionSpec.kitNames(true))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec SHOWKIT = new CommandSpec(
            "showkit", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of("kitpreview", "preview", "kitshow"),
            "Preview the items in a kit",
            "/showkit <kit>",
            "/showkit <kit>",
            "allium.showkit", null,
            List.of(
                    CommandArgSpec.required("kit", null, SuggestionSpec.kitNames(false))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec KITRESET = new CommandSpec(
            "kitreset", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of("kitr", "resetkit"),
            "Clear a player's cooldown for a kit",
            "/kitreset <kit> [player]",
            "/kitreset <kit> <player>",
            "allium.kitreset", "allium.kitreset.others",
            List.of(
                    CommandArgSpec.required("kit", null, SuggestionSpec.kitNames(true)),
                    CommandArgSpec.optional("player", "allium.kitreset.others", SuggestionSpec.knownPlayers("allium.kitreset.others"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final List<CommandSpec> KIT_SPECS = List.of(KIT, KITS, CREATEKIT, DELKIT, SHOWKIT, KITRESET);

    public static final CommandSpec VANISH = new CommandSpec(
            "vanish", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of("v"),
            "Toggle vanish mode for yourself or another player",
            "/vanish [on|off] [player]",
            "/vanish [on|off] <player>",
            "allium.vanish", "allium.vanish.others",
            List.of(
                    CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                    CommandArgSpec.optional("player", "allium.vanish.others", SuggestionSpec.onlinePlayers("allium.vanish.others"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec MUTE = new CommandSpec(
            "mute", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of("silence"),
            "Mute or toggle mute on a player with an optional duration and reason",
            "/mute <player> [duration] [reason]",
            "/mute <player> [duration] [reason]",
            "allium.mute", null,
            List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.optional("duration", null, SuggestionSpec.literals(List.of("5m", "15m", "1h", "1d", "7d"), null)),
                    CommandArgSpec.optional("reason", null, SuggestionSpec.none())
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec UNMUTE = new CommandSpec(
            "unmute", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Unmute a muted player",
            "/unmute <player> [reason]",
            "/unmute <player> [reason]",
            "allium.unmute", null,
            List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.optional("reason", null, SuggestionSpec.none())
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec MUTES = new CommandSpec(
            "mutes", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of("mutelist"),
            "List currently active mutes",
            "/mutes",
            "/mutes",
            "allium.mute", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final List<CommandSpec> DISCIPLINE_SPECS = java.util.stream.Stream.of(
            "kick", "kickall", "ban", "tempban", "unban", "banlist", "ipban", "tempbanip", "unbanip",
            "freeze", "setjail", "deljail", "jails", "jailedplayers", "jail", "unjail", "togglejail", "warn", "notes")
            .map(CoreCommandSpecs::disciplineSpec).toList();

    private static CommandSpec disciplineSpec(String name) {
        String usage = switch (name) {
            case "kick", "ban", "ipban", "warn" -> "/" + name + " <player> [reason]";
            case "kickall" -> "/kickall [reason]";
            case "tempban" -> "/tempban <player> <duration> [reason]";
            case "tempbanip" -> "/tempbanip <player|ip> <duration> [reason]";
            case "unbanip" -> "/unbanip <player|ip>";
            case "unban", "unjail" -> "/" + name + " <player>";
            case "banlist", "jails", "jailedplayers" -> "/" + name;
            case "freeze" -> "/freeze <player> [on|off] [reason]";
            case "setjail" -> "/setjail <name>";
            case "deljail" -> "/deljail <jail>";
            case "jail" -> "/jail <player> <jail> [reason]";
            case "togglejail" -> "/togglejail <player> [jail] [time]";
            case "notes" -> "/notes <player> [add <note>]";
            default -> throw new IllegalArgumentException(name);
        };
        List<CommandArgSpec> arguments = switch (name) {
            case "banlist", "jails", "jailedplayers", "kickall" -> List.of();
            case "setjail" -> List.of(CommandArgSpec.required("name", null, SuggestionSpec.none()));
            case "deljail" -> List.of(CommandArgSpec.required("jail", null,
                    new SuggestionSpec(SuggestionSource.JAIL_NAMES, List.of(), null)));
            case "jail" -> List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.required("jail", null, new SuggestionSpec(SuggestionSource.JAIL_NAMES, List.of(), null)));
            case "togglejail" -> List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.optional("jail", null, new SuggestionSpec(SuggestionSource.JAIL_NAMES, List.of(), null)),
                    CommandArgSpec.optional("time", null, SuggestionSpec.literals(List.of("5m", "15m", "1h", "1d"), null)));
            case "freeze" -> List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)));
            case "tempban", "tempbanip" -> List.of(
                    CommandArgSpec.required(name.equals("tempbanip") ? "playerOrIp" : "player", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.required("duration", null, SuggestionSpec.literals(List.of("5m", "15m", "1h", "1d", "7d"), null)));
            case "warn" -> List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.optional("history", null, SuggestionSpec.literals(List.of("history", "list"), null)));
            case "notes" -> List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.optional("action", null, SuggestionSpec.literals(List.of("list", "add"), null)));
            default -> List.of(CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)));
        };
        List<String> aliases = switch (name) {
            case "unban" -> List.of("pardon");
            case "setjail" -> List.of("createjail");
            case "unbanip" -> List.of("pardonip");
            case "togglejail" -> List.of("tjail");
            case "deljail" -> List.of("remjail", "rmjail");
            default -> List.of();
        };
        return new CommandSpec(name, CommandSpec.DEFAULT_NAMESPACE, "moderation", aliases,
                describe(name, "Persisted, audited moderation: " + name), usage, usage, "allium." + name,
                null, arguments, List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    }

    public static final CommandSpec INVSEE = new CommandSpec(
            "invsee", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Inspect (and optionally edit) another player's inventory",
            "/invsee <player>",
            "/invsee <player>",
            "allium.invsee", null,
            List.of(
                    CommandArgSpec.required("player", null,
                            SuggestionSpec.knownOrOnlinePlayers(null, "allium.invsee.offline", "allium.moderation.bypass"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec ENDERCHEST = new CommandSpec(
            "enderchest", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of("echest", "ec"),
            "Open your own or inspect another player's ender chest",
            "/enderchest [player]",
            "/enderchest <player>",
            "allium.enderchest", "allium.enderchest.others",
            List.of(
                    CommandArgSpec.optional("player", "allium.enderchest.others",
                            SuggestionSpec.knownOrOnlinePlayers("allium.enderchest.others", "allium.enderchest.offline", "allium.moderation.bypass"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec COMMANDSPY = new CommandSpec(
            "commandspy", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of("cmdspy", "cspy"),
            "Opt in or out of seeing other players' commands",
            "/commandspy [on|off]",
            "/commandspy [on|off]",
            "allium.commandspy", null,
            List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null))),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec SPY = new CommandSpec(
            "spy", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Inspect and toggle message, local chat, and command spying for a player",
            "/spy [player]",
            "/spy [player]",
            "allium.spy", null,
            List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null))),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec SPECTATE = new CommandSpec(
            "spectate", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Spectate a player using the configured spectate commands",
            "/spectate <player>",
            "/spectate <player>",
            "allium.spectate", null,
            List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec REPORT = new CommandSpec(
            "report", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Report a player, or anything else, to staff",
            "/report <player> <what happened> | /report <message>",
            "/report <player> <what happened> | /report <message>",
            "allium.report", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec ESCALATE = new CommandSpec(
            "escalate", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Hand an issue up to senior staff",
            "/escalate <target> <reason>",
            "/escalate <target> <reason>",
            "allium.escalate", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> MODERATION_SPECS = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(VANISH, MUTE, UNMUTE, MUTES, INVSEE, ENDERCHEST, COMMANDSPY, SPY, SPECTATE, REPORT, ESCALATE),
            DISCIPLINE_SPECS.stream()).toList();

    public static final List<CommandSpec> CHAT_SPECS = java.util.stream.Stream.of("nick","channel","delmsg","action")
        .map(name->new CommandSpec(name,CommandSpec.DEFAULT_NAMESPACE,"chat",
            switch(name){case "nick"->List.of("nickname");case "delmsg"->List.of("deletemsg");case "action"->List.of("me","describe");
                default->List.of("g","global","shout","l","local","a","ac","amsg","staffchat");},
            describe(name,"Allium chat: "+name),name.equals("action")?"/action <message>":"/"+name,name.equals("action")?"/action <message>":"/"+name,
            switch(name){case "delmsg"->null;case "action"->"allium.me";default->"allium."+name;},null,
            List.of(CommandArgSpec.optional(name.equals("action")?"message":"arguments",null,null)),List.of(),CommandMessageKeys.ofOutcomes(Map.of()))).toList();

    public static final CommandSpec CLEAR_CHAT = new CommandSpec("clearchat", CommandSpec.DEFAULT_NAMESPACE, "chat", List.of(),
            "Clear the chat window for everyone or one player", "/clearchat [player]", "/clearchat [player]",
            "allium.clearchat", "allium.clearchat.others",
            List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers("allium.clearchat.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    private static final List<String> MONEY_AMOUNTS = List.of("1", "10", "100", "1k", "100k", "1M");
    private static final List<String> TAX_PERCENTS = List.of("5", "10", "25", "50", "100");

    private static CommandSpec economySpec(String name, List<String> aliases, String usage, String othersPermission,
                                           List<CommandArgSpec> arguments, List<CommandNodeSpec> children) {
        return new CommandSpec(name, CommandSpec.DEFAULT_NAMESPACE, "economy", aliases, describe(name, "Allium economy: " + name),
                usage, usage, "allium." + name, othersPermission, arguments, children, CommandMessageKeys.ofOutcomes(Map.of()));
    }

    private static CommandNodeSpec ecoNode(String name, boolean amount) {
        var args = new java.util.ArrayList<CommandArgSpec>();
        args.add(CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)));
        if (amount) {
            args.add(CommandArgSpec.required("amount", null, SuggestionSpec.literals(MONEY_AMOUNTS, null)));
        }
        return new CommandNodeSpec(name, List.of(), "allium.eco", "/eco " + name + " <player>" + (amount ? " <amount>" : ""),
                args, List.of());
    }

    /** Economy commands. Player arguments complete from the cached name index, never from storage. */
    public static final List<CommandSpec> ECONOMY_SPECS = List.of(
            economySpec("balance", List.of("bal"), "/balance [player]", null,
                    List.of(CommandArgSpec.optional("player", "allium.balance.others",
                            SuggestionSpec.knownPlayers("allium.balance.others"))), List.of()),
            economySpec("pay", List.of(), "/pay <player> <amount>", null,
                    List.of(CommandArgSpec.required("player", null,
                                    SuggestionSpec.knownOrOnlinePlayers(null, "allium.pay.offline")),
                            CommandArgSpec.required("amount", null, SuggestionSpec.literals(MONEY_AMOUNTS, null))), List.of()),
            economySpec("paytoggle", List.of("payon", "payoff"), "/paytoggle [on|off] [player]", "allium.paytoggle.others",
                    List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                            CommandArgSpec.optional("player", "allium.paytoggle.others",
                                    SuggestionSpec.knownPlayers("allium.paytoggle.others"))), List.of()),
            economySpec("payconfirmtoggle", List.of("payconfirm", "payconfirmon", "payconfirmoff"),
                    "/payconfirmtoggle [on|off] [player]", "allium.payconfirmtoggle.others",
                    List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                            CommandArgSpec.optional("player", "allium.payconfirmtoggle.others",
                                    SuggestionSpec.knownPlayers("allium.payconfirmtoggle.others"))), List.of()),
            economySpec("eco", List.of("money"), "/eco <give|take|set|open|reservations|resolve> ...", null,
                    List.of(),
                    List.of(ecoNode("give", true), ecoNode("take", true), ecoNode("set", true), ecoNode("open", false),
                            new CommandNodeSpec("reservations", List.of(), "allium.eco.admin", "/eco reservations [player]",
                                    List.of(CommandArgSpec.optional("player", null, SuggestionSpec.knownPlayers(null))), List.of()),
                            new CommandNodeSpec("resolve", List.of(), "allium.eco.admin",
                                    "/eco resolve <KIT|MAIL|REPAIR|CHEQUE> <workflow> <capture|release> <reason>",
                                    List.of(CommandArgSpec.required("kind", null, SuggestionSpec.literals(List.of("KIT", "MAIL", "REPAIR", "CHEQUE"), null)),
                                            CommandArgSpec.required("workflow", null, SuggestionSpec.none()),
                                            CommandArgSpec.required("decision", null,
                                                    SuggestionSpec.literals(List.of("capture", "release"), null)),
                                            CommandArgSpec.required("reason", null, SuggestionSpec.none())), List.of()))),
            economySpec("baltop", List.of("balancetop"), "/baltop [page]", null,
                    List.of(CommandArgSpec.optional("page", null, SuggestionSpec.literals(List.of("1", "2", "3"), null))), List.of()),
            economySpec("cheque", List.of(), "/cheque <amount> [player]", "allium.cheque.others",
                    List.of(CommandArgSpec.required("amount", null, SuggestionSpec.literals(MONEY_AMOUNTS, null)),
                            CommandArgSpec.optional("player", "allium.cheque.others",
                                    SuggestionSpec.onlinePlayers("allium.cheque.others"))), List.of()),
            economySpec("tax", List.of(), "/tax [balance|impose|history|refund]", null, List.of(),
                    List.of(
                            new CommandNodeSpec("balance", List.of("bal"), "allium.tax.balance",
                                    "/tax balance", List.of(), List.of()),
                            new CommandNodeSpec("impose", List.of(), "allium.tax.impose",
                                    "/tax impose <player|*|*> <percent>",
                                    List.of(CommandArgSpec.required("target", null, SuggestionSpec.knownPlayers(null)),
                                            CommandArgSpec.required("percent", null, SuggestionSpec.literals(TAX_PERCENTS, null))), List.of()),
                            new CommandNodeSpec("history", List.of(), "allium.tax.history",
                                    "/tax history [player] [page]",
                                    List.of(CommandArgSpec.optional("player", null, SuggestionSpec.knownPlayers(null)),
                                            CommandArgSpec.optional("page", null, SuggestionSpec.literals(List.of("1", "2"), null))), List.of()),
                            new CommandNodeSpec("refund", List.of(), "allium.tax.refund",
                                    "/tax refund <id|player> [percent] [confirm]",
                                    List.of(CommandArgSpec.required("target", null, SuggestionSpec.knownPlayers(null)),
                                            CommandArgSpec.optional("percent", null, SuggestionSpec.literals(TAX_PERCENTS, null)),
                                            CommandArgSpec.optional("confirm", null, SuggestionSpec.literals(List.of("confirm"), null))), List.of()))));

    /** {@code /bed}: {@code /home bed}, for holders of {@code allium.home.bed}. */
    public static final CommandSpec BED = new CommandSpec("bed", CommandSpec.DEFAULT_NAMESPACE, "travel", List.of(),
            "Teleport to your bed", "/bed", "/bed", "allium.home.bed", null, List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> TRAVEL_SPECS = java.util.stream.Stream.concat(
            java.util.stream.Stream.of("sethome","home","delhome","renamehome","homes","setwarp","warp","warpinfo","delwarp","warps").map(CoreCommandSpecs::travelSpec),
            java.util.stream.Stream.of(BED)).toList();
    private static CommandSpec travelSpec(String command) {
        boolean warp=command.contains("warp"),list=command.equals("homes")||command.equals("warps"),set=command.startsWith("set");
        String usage=switch(command){
            case "homes"->"/homes [player]";
            case "warps"->"/warps";
            case "warp"->"/warp <name> [player]";
            case "warpinfo"->"/warpinfo <name>";
            case "renamehome"->"/renamehome <home> <newhome>";
            default->"/"+command+(warp?" <name>":" [name]")+(set?" [confirm]":"");
        };
        String othersPerm=switch(command){
            case "homes"->"allium.homes.others";
            case "home"->"allium.home.others";
            case "delhome"->"allium.delhome.others";
            case "renamehome"->"allium.renamehome.others";
            case "warp"->"allium.warp.others";
            default->null;
        };
        java.util.ArrayList<CommandArgSpec> args=new java.util.ArrayList<>();
        if(command.equals("homes"))args.add(CommandArgSpec.optional("player","allium.homes.others",SuggestionSpec.knownPlayers("allium.homes.others")));
        if(!list){var suggestion=new SuggestionSpec(warp?SuggestionSource.WARP_NAMES:SuggestionSource.HOME_NAMES,List.of(),null);
            args.add(warp||command.equals("renamehome")?CommandArgSpec.required("name",null,suggestion):CommandArgSpec.optional("name",null,suggestion));
            if(set)args.add(CommandArgSpec.optional("confirm",null,SuggestionSpec.literals(List.of("confirm"),null)));
            if(command.equals("warp"))args.add(CommandArgSpec.optional("player","allium.warp.others",SuggestionSpec.onlinePlayers("allium.warp.others")));
            if(command.equals("renamehome"))args.add(CommandArgSpec.required("newhome",null,SuggestionSpec.none()));
        }
        List<String> aliases = switch (command) {
            case "sethome" -> List.of("createhome");
            case "setwarp" -> List.of("createwarp");
            case "delhome" -> List.of("remhome", "rmhome");
            case "delwarp" -> List.of("remwarp", "rmwarp");
            default -> List.of();
        };
        return new CommandSpec(command,CommandSpec.DEFAULT_NAMESPACE,"travel",aliases,describe(command,"Allium homes and warps: "+command),
                usage,command.equals("warp")?"/warp <name> <player>":usage,"allium."+command,othersPerm,args,List.of(),CommandMessageKeys.ofOutcomes(Map.of()));
    }

    public static final List<CommandSpec> TELEPORT_SPECS = List.of(
            teleportSpec("tpa", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpahere", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpaall", List.of(CommandArgSpec.optional("player", "allium.tpaall.others", SuggestionSpec.onlinePlayers("allium.tpaall.others")))),
            teleportSpec("tpaccept", List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpdeny", List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpacancel", List.of()),
            teleportSpec("tptoggle", List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                    CommandArgSpec.optional("player", "allium.tptoggle.others", SuggestionSpec.onlinePlayers("allium.tptoggle.others")))),
            teleportSpec("tpauto", List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                    CommandArgSpec.optional("player", "allium.tpauto.others", SuggestionSpec.onlinePlayers("allium.tpauto.others")))),
            teleportSpec("back", List.of()), teleportSpec("spawn", List.of()), teleportSpec("setspawn", List.of(CommandArgSpec.optional("group", null, SuggestionSpec.literals(List.of("default"), null)))),
            teleportSpec("tp", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.optional("target", "allium.tp.others", SuggestionSpec.onlinePlayers("allium.tp.others")))),
            teleportSpec("tpo", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.optional("target", "allium.tpo", SuggestionSpec.onlinePlayers("allium.tpo")))),
            teleportSpec("tpohere", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpall", List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("there", List.of(CommandArgSpec.optional("targets",null,SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tppet", List.of(CommandArgSpec.optional("player","allium.tppet.others",SuggestionSpec.onlinePlayers("allium.tppet.others")))),
            teleportSpec("tpent", List.of(CommandArgSpec.optional("player","allium.tpmob.others",SuggestionSpec.onlinePlayers("allium.tpmob.others")))),
            teleportSpec("tpoffline", List.of(CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)))),
            teleportSpec("tppos", List.of(CommandArgSpec.required("x", null, SuggestionSpec.literals(List.of("~"), null)),
                    CommandArgSpec.required("y", null, SuggestionSpec.literals(List.of("~"), null)),
                    CommandArgSpec.required("z", null, SuggestionSpec.literals(List.of("~"), null)),
                    CommandArgSpec.optional("yaw", null, SuggestionSpec.literals(List.of("~"), null)),
                    CommandArgSpec.optional("pitch", null, SuggestionSpec.literals(List.of("~"), null)),
                    CommandArgSpec.optional("world", null, SuggestionSpec.none()))),
            teleportSpec("tphere", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("jump", List.of()),
            teleportSpec("top", List.of()), teleportSpec("bottom", List.of()));

    private static CommandSpec teleportSpec(String command, List<CommandArgSpec> args) {
        String usage = "/" + command + switch(command) {
            case "tpa", "tpahere", "tpoffline", "tpohere" -> " <player>";
            case "tpo" -> " <player> [target]";
            case "tphere", "there" -> " [players|selectors...]";
            case "tppet", "tpent", "tpall", "tpaall" -> " [player]";
            case "tpaccept", "tpdeny" -> " [requester]";
            case "setspawn" -> " [group]";
            case "tp" -> " <player> [target] | /tp <x> <y> <z> [yaw] [pitch] [world]";
            case "tppos" -> " <x> <y> <z> [yaw] [pitch] [world]";
            case "tptoggle", "tpauto" -> " [on|off] [player]";
            default -> "";
        };
        List<String> aliases = switch (command) {
            case "tp" -> List.of("tp2p", "tele");
            case "tpa" -> List.of("call", "tpask");
            case "back" -> List.of("return");
            case "tpaccept" -> List.of("tpyes");
            case "tpdeny" -> List.of("tpno");
            case "jump" -> List.of("j", "jumpto");
            case "tpoffline" -> List.of("otp", "offlinetp");
            case "tphere" -> List.of("here", "s");
            case "there" -> List.of("tpthere");
            case "tpent" -> List.of("tpentity", "tpmob", "teleportmob", "tpm", "tpe");
            default -> List.of();
        };
        String othersPerm = switch (command) {
            case "tptoggle" -> "allium.tptoggle.others";
            case "tpauto" -> "allium.tpauto.others";
            case "tpaall" -> "allium.tpaall.others";
            default -> null;
        };
        return new CommandSpec(command, CommandSpec.DEFAULT_NAMESPACE, "teleport", aliases, describe(command, "Allium safe teleport: " + command),
                usage, usage, command.equals("tpoffline")?"allium.tp.offline":command.equals("there")?"allium.tpthere":command.equals("tpent")?"allium.tpmob":"allium." + command, othersPerm, args, List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    }

    public static final CommandSpec REPAIR = new CommandSpec(
            "repair", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("fix"),
            "Repair the held item or all damaged items",
            "/repair [hand|all]", "/repair [hand|all]",
            "allium.repair", null,
            List.of(CommandArgSpec.optional("scope", null,
                    SuggestionSpec.literals(List.of("hand", "all"), null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec DISPOSAL = new CommandSpec(
            "disposal", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("trash", "dispose"),
            "Open a disposal inventory",
            "/disposal", "/disposal",
            "allium.disposal", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec WORKBENCH = new CommandSpec(
            "workbench", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("craft", "wb", "wbench"),
            "Open a virtual crafting table",
            "/workbench", "/workbench",
            "allium.workbench", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec ANVIL = new CommandSpec(
            "anvil", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Open a virtual anvil",
            "/anvil", "/anvil",
            "allium.anvil", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final List<CommandSpec> UTILITY_SPECS = List.of(
            REPAIR, DISPOSAL, WORKBENCH, ANVIL,
            workstation("grindstone", "grindstone"),
            workstation("cartographytable", "cartography table", "carttable"),
            workstation("stonecutter", "stonecutter"),
            workstation("loom", "loom"),
            workstation("smithing", "smithing table", "smithingtable")
    );

    private static CommandSpec workstation(String label, String name, String... aliases) {
        return new CommandSpec(label, CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(aliases),
                "Open a virtual " + name, "/" + label, "/" + label, "allium." + label, null,
                List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    }

    public static final CommandSpec SPEED = new CommandSpec(
            "speed", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("wspeed", "walkspeed", "flyspeed", "fspeed"),
            "Change walk or fly speed (0-10 or reset)",
            "/speed [walk|fly] <0..10|reset> [player]", "/speed [walk|fly] <0..10|reset> <player>",
            "allium.speed", "allium.speed.others",
            List.of(
                    CommandArgSpec.required("typeOrSpeed", null,
                            SuggestionSpec.literals(List.of("walk", "fly", "reset", "1", "2", "5", "10"), null)),
                    CommandArgSpec.optional("speedOrPlayer", null,
                            SuggestionSpec.literals(List.of("reset", "1", "2", "5", "10"), null)),
                    CommandArgSpec.optional("player", "allium.speed.others",
                            SuggestionSpec.onlinePlayers("allium.speed.others"))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of("set", "player.speed-set"))
    );

    public static final CommandSpec SCALE = new CommandSpec(
            "scale", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Set the scale of a player or selected mobs",
            "/scale <0..16> [player]", "/scale <0..16> <player>",
            "allium.scale", "allium.scale.others",
            List.of(
                    CommandArgSpec.required("scale", null,
                            SuggestionSpec.literals(List.of("0", "0.1", "0.5", "1", "2", "4", "8", "16"), null)),
                    CommandArgSpec.optional("player", "allium.scale.others",
                            SuggestionSpec.onlinePlayers("allium.scale.others"))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec PING = new CommandSpec(
            "ping", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("pong"),
            "Check your ping or another player's ping",
            "/ping [player]", "/ping <player>",
            "allium.ping", "allium.ping.others",
            List.of(CommandArgSpec.optional("player", "allium.ping.others",
                    SuggestionSpec.onlinePlayers("allium.ping.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec COORDS = new CommandSpec(
            "coords", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("getpos", "whereami", "getloc", "getlocation", "position"),
            "Show your position or another player's",
            "/coords [player]", "/coords <player>",
            "allium.getpos", "allium.getpos.others",
            List.of(CommandArgSpec.optional("player", "allium.getpos.others",
                    SuggestionSpec.onlinePlayers("allium.getpos.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec GLOW = new CommandSpec(
            "glow", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Set your glow colour, or another player's",
            "/glow <color|rainbow|off|spectator> [player]", "/glow <color|rainbow|off|spectator> <player>",
            "allium.glow", "allium.glow.others",
            List.of(CommandArgSpec.required("color", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("player", "allium.glow.others", SuggestionSpec.onlinePlayers("allium.glow.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec COMPASS = new CommandSpec(
            "compass", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("direction"),
            "Show the direction you are facing",
            "/compass", "/compass",
            "allium.compass", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec EXT = new CommandSpec(
            "ext", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("extinguish"),
            "Extinguish fire on yourself or another player",
            "/ext [player]", "/ext <player>",
            "allium.ext", "allium.ext.others",
            List.of(CommandArgSpec.optional("player", "allium.ext.others",
                    SuggestionSpec.onlinePlayers("allium.ext.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec BURN = new CommandSpec(
            "burn", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Set a player on fire for a duration in seconds",
            "/burn <player> [seconds]", "/burn <player> [seconds]",
            "allium.burn", null,
            List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.optional("seconds", null,
                            SuggestionSpec.literals(List.of("5", "10", "30"), null))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec SMITE = new CommandSpec(
            "smite", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("lightning", "strike", "shock", "thor"),
            "Strike a player or the block you are looking at with visual lightning",
            "/smite [player]", "/smite <player>",
            "allium.smite", null,
            List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec NV = new CommandSpec(
            "nv", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("nightvision"),
            "Toggle night vision for yourself or another player",
            "/nv [on|off] [player]", "/nv [on|off] <player>",
            "allium.nv", "allium.nv.others",
            List.of(
                    CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                    CommandArgSpec.optional("player", "allium.nv.others",
                            SuggestionSpec.onlinePlayers("allium.nv.others"))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec CLEARINVENTORY = new CommandSpec(
            "clearinventory", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("clear", "ci", "clean", "clearinvent"),
            "Clear inventory items for yourself, a player, or a group",
            "/clearinventory [player|*|**] [item] [maxCount]", "/clearinventory <player|*|**> [item] [maxCount]",
            "allium.clearinventory", "allium.clearinventory.others",
            List.of(CommandArgSpec.optional("player", "allium.clearinventory.others",
                    SuggestionSpec.onlinePlayers("allium.clearinventory.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec CLEAR_CONFIRM_TOGGLE = new CommandSpec(
            "clearinventoryconfirmtoggle", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("clearconfirm", "clearconfirmon", "clearconfirmoff", "clearinventoryconfirmoff"),
            "Toggle inventory clear confirmation",
            "/clearinventoryconfirmtoggle", "/clearinventoryconfirmtoggle",
            "allium.clearinventoryconfirmtoggle", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec MORE = new CommandSpec(
            "more", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Refill the held item stack",
            "/more [amount] [player]", "/more <amount|max> <player>",
            "allium.more", "allium.more.others",
            List.of(
                    CommandArgSpec.optional("amount", null,
                            SuggestionSpec.literals(List.of("64"), null)),
                    CommandArgSpec.optional("player", "allium.more.others",
                            SuggestionSpec.onlinePlayers("allium.more.others"))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec HAT = new CommandSpec(
            "hat", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Wear the held item on your head",
            "/hat", "/hat",
            "allium.hat", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec SKULL = new CommandSpec(
            "skull", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("head", "playerhead", "playerskull"),
            "Give yourself or another player a player skull",
            "/skull [owner] [player]", "/skull <owner> <player>",
            "allium.skull", "allium.skull.others",
            List.of(
                    CommandArgSpec.optional("owner", null, SuggestionSpec.knownPlayers(null)),
                    CommandArgSpec.optional("player", "allium.skull.others",
                            SuggestionSpec.onlinePlayers("allium.skull.others"))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec ITEMDB = new CommandSpec(
            "itemdb", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("iteminfo", "itemno"),
            "Inspect the held item ID, durability, and Allium identity",
            "/itemdb [player]", "/itemdb <player>",
            "allium.itemdb", "allium.itemdb.others",
            List.of(CommandArgSpec.optional("player", "allium.itemdb.others",
                    SuggestionSpec.onlinePlayers("allium.itemdb.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec GC = new CommandSpec(
            "gc", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("uptime", "lag", "tps", "mem", "memory"),
            "Display runtime uptime, memory, disk, TPS, and world counts",
            "/gc", "/gc",
            "allium.gc", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec TIME = new CommandSpec(
            "time", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "View, set, add, or pause world time",
            "/time [set|add|pause] [value] [world|*]", "/time [set|add|pause] [value] <world|*>",
            "allium.time", null,
            List.of(
                    CommandArgSpec.optional("action", null,
                            SuggestionSpec.literals(List.of("set", "add", "pause", "day", "noon", "night", "midnight"), null)),
                    CommandArgSpec.optional("value", null,
                            SuggestionSpec.literals(List.of("day", "noon", "sunset", "night", "midnight", "sunrise"), null))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of("set", "utility.time-set"))
    );

    public static final CommandSpec DAY = new CommandSpec(
            "day", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Set world time to day",
            "/day [world|*]", "/day <world|*>",
            "allium.time.set", null,
            List.of(), List.of(),
            CommandMessageKeys.ofOutcomes(Map.of("set", "utility.time-set"))
    );

    public static final CommandSpec NIGHT = new CommandSpec(
            "night", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Set world time to night",
            "/night [world|*]", "/night <world|*>",
            "allium.time.set", null,
            List.of(), List.of(),
            CommandMessageKeys.ofOutcomes(Map.of("set", "utility.time-set"))
    );

    public static final CommandSpec WEATHER = new CommandSpec(
            "weather", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("rain", "storm", "sun", "sky"),
            "Change world weather",
            "/weather <clear|sun|rain|storm|thunder> [duration] [world|*]",
            "/weather <clear|sun|rain|storm|thunder> [duration] <world|*>",
            "allium.weather", null,
            List.of(
                    CommandArgSpec.required("condition", null,
                            SuggestionSpec.literals(List.of("clear", "rain", "thunder"), null)),
                    CommandArgSpec.optional("duration", null,
                            SuggestionSpec.literals(List.of("60", "300", "600"), null))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of("set", "utility.weather-set"))
    );

    public static final CommandSpec ENCHANT = new CommandSpec(
            "enchant", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Apply or remove an enchantment on the held item",
            "/enchant <enchantment> <level> [player]", "/enchant <enchantment> <level> <player>",
            "allium.enchant", "allium.enchant.others",
            List.of(
                    CommandArgSpec.required("enchantment", null, SuggestionSpec.none()),
                    CommandArgSpec.required("level", null, SuggestionSpec.literals(List.of("0", "1", "3", "5"), null)),
                    CommandArgSpec.optional("player", "allium.enchant.others",
                            SuggestionSpec.onlinePlayers("allium.enchant.others"))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /rename <name>|clear}: the held item's custom name. */
    public static final CommandSpec RENAME = new CommandSpec(
            "rename", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("itemname", "iname", "itemrename", "irename"),
            "Rename the item in your hand",
            "/rename <name|clear>", "/rename <name|clear>",
            "allium.rename", null,
            List.of(CommandArgSpec.required("name", null, SuggestionSpec.literals(List.of("clear"), null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /lore add|set|insert|remove|clear}: the held item's lore lines. */
    public static final CommandSpec LORE = new CommandSpec(
            "lore", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("itemlore", "ilore"),
            "Edit the lore of the item in your hand",
            "/lore <add|set|insert|remove|clear|copy|paste>", "/lore <add|set|insert|remove|clear|copy|paste>",
            "allium.lore", null,
            List.of(),
            List.of(
                    new CommandNodeSpec("add", List.of(), null, "/lore add <text>", List.of(), List.of()),
                    new CommandNodeSpec("set", List.of(), null, "/lore set <line> <text>",
                            List.of(CommandArgSpec.required("line", null, SuggestionSpec.literals(List.of("1", "2", "3"), null))), List.of()),
                    new CommandNodeSpec("insert", List.of(), null, "/lore insert <line> <text>",
                            List.of(CommandArgSpec.required("line", null, SuggestionSpec.literals(List.of("1", "2", "3"), null))), List.of()),
                    new CommandNodeSpec("remove", List.of(), null, "/lore remove <line>",
                            List.of(CommandArgSpec.required("line", null, SuggestionSpec.literals(List.of("1", "2", "3"), null))), List.of()),
                    new CommandNodeSpec("clear", List.of(), null, "/lore clear", List.of(), List.of()),
                    new CommandNodeSpec("copy", List.of(), null, "/lore copy", List.of(), List.of()),
                    new CommandNodeSpec("paste", List.of(), null, "/lore paste", List.of(), List.of())),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    private static final List<CommandArgSpec> XP_CHANGE_ARGS = List.of(
            CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
            CommandArgSpec.required("amount", null, SuggestionSpec.literals(List.of("10", "100", "1L", "5L", "30L"), null)));

    /** {@code /xp}: show, give, take, set or reset experience (vanilla's add/remove/query words work too). */
    public static final CommandSpec XP = new CommandSpec(
            "xp", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("exp"),
            "Show or change experience",
            "/xp [player] | /xp <give|take|set> <player> <amount>[L] | /xp reset <player>",
            "/xp <player> | /xp <give|take|set> <player> <amount>[L] | /xp reset <player>",
            "allium.xp", "allium.xp.others",
            List.of(CommandArgSpec.optional("player", "allium.xp.others", SuggestionSpec.onlinePlayers("allium.xp.others"))),
            List.of(
                    new CommandNodeSpec("give", List.of("add"), "allium.xp.give", "/xp give <player> <amount>[L]", XP_CHANGE_ARGS, List.of()),
                    new CommandNodeSpec("take", List.of("remove"), "allium.xp.take", "/xp take <player> <amount>[L]", XP_CHANGE_ARGS, List.of()),
                    new CommandNodeSpec("set", List.of(), "allium.xp.set", "/xp set <player> <amount>[L]", XP_CHANGE_ARGS, List.of()),
                    new CommandNodeSpec("reset", List.of(), "allium.xp.set", "/xp reset <player>",
                            List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))), List.of())),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /customtext [section] [page]}: pages of {@code custom.txt}, with click and hover tags. */
    public static final CommandSpec CUSTOMTEXT = new CommandSpec(
            "customtext", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Show a page of the server's custom text",
            "/customtext [section] [page]", "/customtext [section] [page]",
            "allium.customtext", null,
            List.of(CommandArgSpec.optional("section", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /motd [section] [page]}: {@code motd.txt}, also shown on join. */
    public static final CommandSpec MOTD = new CommandSpec(
            "motd", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Show the message of the day",
            "/motd [section] [page]", "/motd [section] [page]",
            "allium.motd", null,
            List.of(CommandArgSpec.optional("section", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /rules [chapter] [page]}: {@code rules.txt}. */
    public static final CommandSpec RULES = new CommandSpec(
            "rules", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Show the server rules",
            "/rules [chapter] [page]", "/rules [chapter] [page]",
            "allium.rules", null,
            List.of(CommandArgSpec.optional("chapter", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /info [chapter] [page]}: {@code info.txt}, or the player's or their group's own file. */
    public static final CommandSpec INFO = new CommandSpec(
            "info", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("about", "ifo", "news"),
            "Show the server info pages",
            "/info [chapter] [page]", "/info [chapter] [page]",
            "allium.info", null,
            List.of(CommandArgSpec.optional("chapter", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /inform [player] [chapter] [page]}: staff show a player their info pages. */
    public static final CommandSpec INFORM = new CommandSpec(
            "inform", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Show a player the info pages",
            "/inform [player] [chapter] [page]", "/inform <player> [chapter] [page]",
            "allium.inform", null,
            List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /broadcast <message>}: staff's message to everyone. */
    public static final CommandSpec BROADCAST = new CommandSpec(
            "broadcast", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("bc", "bcast"),
            "Send a message to everyone online",
            "/broadcast <message>", "/broadcast <message>",
            "allium.broadcast", null,
            List.of(CommandArgSpec.required("message", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /broadcasts [send <name>|next]}: the timed broadcasts of {@code broadcasts.txt}. */
    public static final CommandSpec BROADCASTWORLD = new CommandSpec(
            "broadcastworld", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("bcw", "bcastw", "shoutworld"),
            "Broadcast a message to a world",
            "/broadcastworld <world> <message>", "/broadcastworld <world> <message>",
            "allium.broadcastworld", null,
            List.of(CommandArgSpec.required("world", null, SuggestionSpec.none()),
                    CommandArgSpec.required("message", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec BROADCASTS = new CommandSpec(
            "broadcasts", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Show the timed broadcasts, or send one now",
            "/broadcasts [send <name>|next]", "/broadcasts [send <name>|next]",
            "allium.broadcasts.admin", null,
            List.of(),
            List.of(new CommandNodeSpec("send", List.of(), null, "/broadcasts send <name>", List.of(), List.of()),
                    new CommandNodeSpec("next", List.of(), null, "/broadcasts next", List.of(), List.of())),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /spawnmob <mob>[:data][,<mount>[:data]] [amount] [player]}: Essentials-style mob spawning. */
    public static final CommandSpec SPAWNMOB = new CommandSpec(
            "spawnmob", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("mob"),
            "Spawn one or more mobs at your target block or a player",
            "/spawnmob <mob>[:data][,<mount>[:data]] [amount] [player]",
            "/spawnmob <mob>[:data][,<mount>[:data]] <amount> <player>",
            "allium.spawnmob", "allium.spawnmob.others",
            List.of(
                    CommandArgSpec.required("mob", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("amount", null, SuggestionSpec.literals(List.of("1", "2", "5", "10"), null)),
                    CommandArgSpec.optional("player", "allium.spawnmob.others", SuggestionSpec.onlinePlayers("allium.spawnmob.others"))
            ),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /recipe [item|hand] [number]}: the read-only recipe window. */
    public static final CommandSpec RECIPE = new CommandSpec(
            "recipe", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("formula", "method", "recipes"),
            "Show how an item is made",
            "/recipe [item] [number]", "/recipe <item> [number]",
            "allium.recipe", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /potion <clear|apply|effect:<effect> power:<power> duration:<seconds>>}: edit or apply held potion effects. */
    public static final CommandSpec POTION = new CommandSpec(
            "potion", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("elixer", "elixir"),
            "Edit or apply custom effects on the held potion",
            "/potion <clear|apply|effect:<effect> power:<power> duration:<seconds> [splash:<true|false>]>",
            "/potion <clear|apply|effect:<effect> power:<power> duration:<seconds>>",
            "allium.potion", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /firework <<meta>|power <power>|clear|fire [amount]>}: edit or launch the held firework rocket. */
    public static final CommandSpec FIREWORK = new CommandSpec(
            "firework", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Edit or launch the held firework rocket",
            "/firework <color:<colors> [fade:<colors>] [shape:<shape>] [effect:<effects>]|power <0-4>|clear|fire [amount]>",
            "/firework <meta|power <0-4>|clear|fire [amount]>",
            "allium.firework", null,
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /ptime [list|reset|@<time>|<time>] [player|*]}: per-player client time (never {@code /pt}). */
    public static final CommandSpec PTIME = new CommandSpec(
            "ptime", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("playertime"),
            "View, set, freeze, or reset a player's client time",
            "/ptime [list|reset|@<time>|<time>] [player|*]",
            "/ptime <list|reset|@<time>|<time>> [player|*]",
            "allium.ptime", "allium.ptime.others",
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    /** {@code /pweather [list|reset|sun|storm] [player|*]}: per-player client weather. */
    public static final CommandSpec PWEATHER = new CommandSpec(
            "pweather", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("playerweather"),
            "View, set, or reset a player's client weather",
            "/pweather [list|reset|sun|storm] [player|*]",
            "/pweather <list|reset|sun|storm> [player|*]",
            "allium.pweather", "allium.pweather.others",
            List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final List<CommandSpec> PARITY_UTILITY_SPECS = List.of(
            SPEED, SCALE, PING, COORDS, COMPASS, GLOW, EXT, BURN, SMITE, NV, CLEARINVENTORY, CLEAR_CONFIRM_TOGGLE, MORE, HAT, SKULL,
            ITEMDB, GC, TIME, DAY, NIGHT, WEATHER, PTIME, PWEATHER, ENCHANT, RENAME, LORE, XP, INFO, INFORM, CUSTOMTEXT, MOTD, RULES,
            BROADCAST, BROADCASTWORLD, BROADCASTS, REALNAME, SPAWNMOB, RECIPE, POTION, FIREWORK
    );

    public static final List<CommandSpec> ESSENTIALS_PROOF_SPECS = List.of(
            HEAL,
            FEED,
            FLY,
            TOGGLE_SLOW_FALL,
            GOD,
            GAMEMODE
    );

    public static final CommandSpec LIST = new CommandSpec(
            "list",
            CommandSpec.DEFAULT_NAMESPACE,
            "info",
            List.of("online", "who", "playerlist", "plist"),
            "List online players",
            "/list",
            "/list",
            "allium.list",
            null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec SEEN = new CommandSpec(
            "seen",
            CommandSpec.DEFAULT_NAMESPACE,
            "info",
            List.of("alts"),
            "Show when a player was last seen and first joined",
            "/seen <player>",
            "/seen <player>",
            "allium.seen",
            null,
            List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec WHOIS = new CommandSpec(
            "whois",
            CommandSpec.DEFAULT_NAMESPACE,
            "info",
            List.of(),
            "Inspect player UUID, name history, and staff-only location",
            "/whois <player>",
            "/whois <player>",
            "allium.whois",
            null,
            List.of(
                    CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec NEAR = new CommandSpec(
            "near",
            CommandSpec.DEFAULT_NAMESPACE,
            "info",
            List.of("nearby"),
            "List nearby visible players",
            "/near [radius]",
            "/near [radius]",
            "allium.near",
            null,
            List.of(
                    CommandArgSpec.optional("radius", null, SuggestionSpec.literals(List.of("50", "100", "200", "500"), null))
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec PLAYTIME = new CommandSpec(
            "playtime", CommandSpec.DEFAULT_NAMESPACE, "info", List.of(),
            "Show how long you (or another player) have played",
            "/playtime [player]", "/playtime <player>",
            "allium.playtime", "allium.playtime.others",
            List.of(CommandArgSpec.optional("player", "allium.playtime.others", SuggestionSpec.onlinePlayers("allium.playtime.others"))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final CommandSpec AFK = new CommandSpec(
            "afk",
            CommandSpec.DEFAULT_NAMESPACE,
            "info",
            List.of("away"),
            "Toggle AFK status with an optional message",
            "/afk [message]",
            "/afk [message]",
            "allium.afk",
            null,
            List.of(
                    CommandArgSpec.optional("message", null, SuggestionSpec.none())
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of())
    );

    public static final List<CommandSpec> INFO_SPECS = List.of(LIST, SEEN, WHOIS, NEAR, AFK, PLAYTIME);

    public static final CommandSpec ALTEXEMPT = new CommandSpec(
            "altexempt", CommandSpec.DEFAULT_NAMESPACE, "alts", List.of(),
            "Exempt an account from alt detection, or check its exemption",
            "/altexempt <add|remove|check> <player> [reason]",
            "/altexempt <add|remove|check> <player> [reason]",
            "allium.altexempt", null,
            List.of(),
            List.of("add", "remove", "check").stream().map(action -> new CommandNodeSpec(action, List.of(), "allium.altexempt",
                    "/altexempt " + action + " <player>" + (action.equals("add") ? " [reason]" : ""),
                    action.equals("add")
                            ? List.of(CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null)),
                                    CommandArgSpec.optional("reason", null, SuggestionSpec.none()))
                            : List.of(CommandArgSpec.required("player", null, SuggestionSpec.knownPlayers(null))),
                    List.of())).toList(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> ALTS_SPECS = List.of(ALTEXEMPT);

    public static final CommandSpec RESTORE = new CommandSpec(
            "restore", CommandSpec.DEFAULT_NAMESPACE, "restore", List.of(),
            "Browse inventory snapshots and restore them",
            "/restore [player]", "/restore <player>",
            "allium.restore", "allium.restore.others",
            List.of(CommandArgSpec.optional("player", "allium.restore.others", SuggestionSpec.knownPlayers("allium.restore.others"))),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> RESTORE_SPECS = List.of(RESTORE);

    public static final CommandSpec POWERTOOL = new CommandSpec(
            "powertool", CommandSpec.DEFAULT_NAMESPACE, "tools",
            List.of("pt", "tool", "tools", "powertools", "ptt", "powertooltoggle", "pttoggle", "powertoollist", "ptlist"),
            "Create, edit, save, bind, enable/disable and give interactive powertool items",
            "/pt <add|set|remove|clear|cooldown|perm|uses|cancel|sneak|hand|fish|info|save|delete|bind|enable|disable|get|give|list|toggle|reload|help> [args...]",
            "/pt <enable|disable|give|list|delete|reload|help> [args...]",
            "allium.powertool", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec VOUCHER = new CommandSpec(
            "voucher", CommandSpec.DEFAULT_NAMESPACE, "tools", List.of("vouchers"),
            "Give and list vouchers: powertools named voucher_<name>. /vouchers is /voucher list",
            "/voucher <give|list|reload> [player] [voucher] [amount]",
            "/voucher <give|list|reload> [player] [voucher] [amount]",
            "allium.voucher.give", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    /** {@code /unrestrain [player]}: frees restrained players (the Handcuffs' ride action). */
    public static final CommandSpec UNRESTRAIN = new CommandSpec(
            "unrestrain", CommandSpec.DEFAULT_NAMESPACE, "tools", List.of(),
            "Free every restrained player, or one player",
            "/unrestrain [player]", "/unrestrain [player]",
            "allium.unrestrain", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> TOOLS_SPECS = List.of(POWERTOOL, VOUCHER, UNRESTRAIN);

    public static final CommandSpec CREATIVEMANAGER = new CommandSpec(
            "creativemanager", CommandSpec.DEFAULT_NAMESPACE, "creative", List.of("cm"),
            "Manage creative-mode flags for players and permission groups",
            "/creativemanager [gui|active|player <player> [action]|group <group> [action]]",
            "/creativemanager <player|group> <name> <list|check|add|remove> [flag]",
            "allium.gamemode.creative.manage", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> CREATIVE_SPECS = List.of(CREATIVEMANAGER);

    public static final CommandSpec RTP = new CommandSpec(
            "rtp", CommandSpec.DEFAULT_NAMESPACE, "rtp", List.of("tpr", "tprandom"),
            "Teleport to a random safe spot",
            "/rtp [location] [player]",
            "/rtp [location] <player>",
            "allium.rtp", "allium.rtp.others",
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec SETTPR = new CommandSpec(
            "settpr", CommandSpec.DEFAULT_NAMESPACE, "rtp", List.of("settprandom"),
            "Set a random teleport location's centre, range, shape and heights",
            "/settpr <location> <center|minrange|maxrange|shape|miny|maxy|world|remove> [value]",
            "/settpr <location> <minrange|maxrange|shape|miny|maxy|world|remove> [value]",
            "allium.settpr", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> RTP_SPECS = List.of(RTP, SETTPR);

    public static final CommandSpec WORTH = new CommandSpec(
            "worth", CommandSpec.DEFAULT_NAMESPACE, "worth", List.of("price"),
            "Show what items sell for",
            "/worth [hand|inventory|blocks|item] [amount]",
            "/worth <item> [amount]",
            "allium.worth", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec SELL = new CommandSpec(
            "sell", CommandSpec.DEFAULT_NAMESPACE, "worth", List.of(),
            "Sell items for their worth",
            "/sell <hand|inventory|all|blocks|item> [-][amount][s]",
            "/sell <hand|inventory|all|blocks|item> [-][amount][s]",
            "allium.sell", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec SETWORTH = new CommandSpec(
            "setworth", CommandSpec.DEFAULT_NAMESPACE, "worth", List.of(),
            "Set what an item sells for",
            "/setworth [hand|item] <price>",
            "/setworth <item> <price>",
            "allium.setworth", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> WORTH_SPECS = List.of(WORTH, SELL, SETWORTH);

    public static final CommandSpec ANTIOCH = new CommandSpec(
            "antioch", CommandSpec.DEFAULT_NAMESPACE, "explosions", List.of("grenade", "tnt"),
            "Throw primed TNT where you look",
            "/antioch", "/antioch",
            "allium.antioch", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec FIREBALL = new CommandSpec(
            "fireball", CommandSpec.DEFAULT_NAMESPACE, "explosions", List.of("fireentity", "fireskull"),
            "Throw a fireball or another projectile",
            "/fireball [type] [speed] [ride]", "/fireball [type] [speed] [ride]",
            "allium.fireball", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec BEEZOOKA = new CommandSpec(
            "beezooka", CommandSpec.DEFAULT_NAMESPACE, "explosions", List.of("beecannon"),
            "Throw an exploding bee",
            "/beezooka", "/beezooka",
            "allium.beezooka", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec KITTYCANNON = new CommandSpec(
            "kittycannon", CommandSpec.DEFAULT_NAMESPACE, "explosions", List.of(),
            "Throw an exploding kitten",
            "/kittycannon", "/kittycannon",
            "allium.kittycannon", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec NUKE = new CommandSpec(
            "nuke", CommandSpec.DEFAULT_NAMESPACE, "explosions", List.of(),
            "Rain TNT on players; it breaks no blocks",
            "/nuke [player...]", "/nuke [player...]",
            "allium.nuke", null,
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final CommandSpec EXPLODE = new CommandSpec(
            "explode", CommandSpec.DEFAULT_NAMESPACE, "explosions", List.of(),
            "Drop an exploding fireball on yourself or a player",
            "/explode [player] [power]", "/explode <player> [power]",
            "allium.explode", "allium.explode.others",
            List.of(),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of()));

    public static final List<CommandSpec> EXPLOSION_SPECS = List.of(ANTIOCH, FIREBALL, BEEZOOKA, KITTYCANNON, NUKE, EXPLODE);

    private CoreCommandSpecs() {
    }
}
