package codes.castled.allium.command.spec;

import java.util.List;
import java.util.Map;

/**
 * Canonical platform-neutral command specifications for Allium's core command families.
 */
public final class CoreCommandSpecs {

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
            "/fly [player]",
            "/fly <player>",
            "allium.fly",
            "allium.fly.others",
            List.of(
                    CommandArgSpec.optional(
                            "player",
                            "allium.fly.others",
                            SuggestionSpec.onlinePlayers("allium.fly.others")
                    )
            ),
            List.of(),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "toggle", "player.fly-toggle"
            ))
    );

    public static final CommandSpec GOD = new CommandSpec(
            "god",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of(),
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

    public static final CommandSpec GAMEMODE = new CommandSpec(
            "gamemode",
            CommandSpec.DEFAULT_NAMESPACE,
            "essentials",
            List.of("gm"),
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

    public static final List<CommandSpec> ITEM_SPECS = List.of(GIVE);

    /** {@code /allium}: plugin administration. {@code version} needs no permission; {@code reload} needs {@code allium.reload}. */
    public static final CommandSpec ALLIUM = new CommandSpec(
            "allium",
            CommandSpec.DEFAULT_NAMESPACE,
            "core",
            List.of(),
            "Allium administration",
            "/allium <reload|version|import lang [apply]|adopt-window [<duration>|off]>",
            "/allium <reload|version|import lang [apply]|adopt-window [<duration>|off]>",
            null,
            null,
            List.of(),
            List.of(
                    new CommandNodeSpec("reload", List.of(), "allium.reload", "/allium reload", List.of(), List.of()),
                    new CommandNodeSpec("version", List.of(), null, "/allium version", List.of(), List.of()),
                    new CommandNodeSpec("import", List.of(), "allium.import", "/allium import lang [apply]", List.of(), List.of(
                            new CommandNodeSpec("lang", List.of(), "allium.import", "/allium import lang [apply]",
                                    List.of(CommandArgSpec.optional("mode", "allium.import", SuggestionSpec.literals(List.of("apply"), null))),
                                    List.of()))),
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

    public static final List<CommandSpec> CORE_SPECS = List.of(ALLIUM);

    /** {@code /msg} with Essentials' aliases. */
    public static final CommandSpec MSG = new CommandSpec(
            "msg", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of("tell", "w", "whisper", "m", "t"),
            "Send a private message", "/msg <player> <message>", "/msg <player> <message>", "allium.msg", null,
            List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.required("message", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

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
            "unignore", CommandSpec.DEFAULT_NAMESPACE, "messaging", List.of(),
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

    public static final List<CommandSpec> MESSAGING_SPECS = List.of(MSG, REPLY, IGNORE, UNIGNORE, MSGTOGGLE, MESSAGESOUNDS, SOCIALSPY);

    /**
     * {@code /mail}: text mail and gifts, to online and offline players. Targets complete from the
     * known-player index; the gift amount completes from common stack sizes.
     */
    public static final CommandSpec MAIL = new CommandSpec(
            "mail", CommandSpec.DEFAULT_NAMESPACE, "mail", List.of(),
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
            "createkit", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of(),
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
            "delkit", CommandSpec.DEFAULT_NAMESPACE, "kits", List.of(),
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

    public static final List<CommandSpec> KIT_SPECS = List.of(KIT, KITS, CREATEKIT, DELKIT);

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
            "mute", CommandSpec.DEFAULT_NAMESPACE, "moderation", List.of(),
            "Mute a player with an optional duration and reason",
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

    public static final List<CommandSpec> MODERATION_SPECS = List.of(VANISH, MUTE, UNMUTE, MUTES);

    public static final List<CommandSpec> TRAVEL_SPECS = java.util.stream.Stream.of("sethome","home","delhome","homes","setwarp","warp","delwarp","warps")
            .map(CoreCommandSpecs::travelSpec).toList();
    private static CommandSpec travelSpec(String command) {
        boolean warp=command.contains("warp"),list=command.equals("homes")||command.equals("warps"),set=command.startsWith("set");
        String usage="/"+command+(list?"":warp?" <name>":" [name]")+(set?" [confirm]":"");
        java.util.ArrayList<CommandArgSpec> args=new java.util.ArrayList<>();
        if(!list){var suggestion=new SuggestionSpec(warp?SuggestionSource.WARP_NAMES:SuggestionSource.HOME_NAMES,List.of(),null);
            args.add(warp?CommandArgSpec.required("name",null,suggestion):CommandArgSpec.optional("name",null,suggestion));
            if(set)args.add(CommandArgSpec.optional("confirm",null,SuggestionSpec.literals(List.of("confirm"),null)));}
        return new CommandSpec(command,CommandSpec.DEFAULT_NAMESPACE,"travel",List.of(),"Allium homes and warps: "+command,
                usage,usage,"allium."+command,null,args,List.of(),CommandMessageKeys.ofOutcomes(Map.of()));
    }

    public static final List<CommandSpec> TELEPORT_SPECS = List.of(
            teleportSpec("tpa", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpahere", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpaccept", List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpdeny", List.of(CommandArgSpec.optional("player", null, SuggestionSpec.onlinePlayers(null)))),
            teleportSpec("tpacancel", List.of()),
            teleportSpec("tptoggle", List.of(CommandArgSpec.optional("state", null, SuggestionSpec.literals(List.of("on", "off"), null)),
                    CommandArgSpec.optional("player", "allium.tptoggle.others", SuggestionSpec.onlinePlayers("allium.tptoggle.others")))),
            teleportSpec("back", List.of()), teleportSpec("spawn", List.of()), teleportSpec("setspawn", List.of()),
            teleportSpec("tp", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)),
                    CommandArgSpec.optional("target", "allium.tp.others", SuggestionSpec.onlinePlayers("allium.tp.others")))),
            teleportSpec("tphere", List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null)))));

    private static CommandSpec teleportSpec(String command, List<CommandArgSpec> args) {
        String usage = "/" + command + switch(command) {
            case "tpa", "tpahere", "tphere" -> " <player>";
            case "tpaccept", "tpdeny" -> " [requester]";
            case "tp" -> " <player> [target]";
            case "tptoggle" -> " [on|off] [player]";
            default -> "";
        };
        return new CommandSpec(command, CommandSpec.DEFAULT_NAMESPACE, "teleport", List.of(), "Allium safe teleport: " + command,
                usage, usage, "allium." + command, null, args, List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    }

    public static final List<CommandSpec> ESSENTIALS_PROOF_SPECS = List.of(
            HEAL,
            FEED,
            FLY,
            GOD,
            GAMEMODE
    );

    private CoreCommandSpecs() {
    }
}
