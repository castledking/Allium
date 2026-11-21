package codes.castled.allium.command.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The usage lines shown on a command's usage card ({@code /help <command>}, or when a command is
 * typed with missing or wrong arguments). A command family listed here gets one line per form,
 * each with what it does; any other family falls back to its subcommand usages, then to the usage
 * string the command reported. Syntax is written after the label, so the card can show the label
 * the player actually typed ({@code /gm <mode>} for {@code /gm}).
 */
public final class CommandUsages {

    private CommandUsages() {
    }

    private static CommandUsage u(String syntax, String description) {
        return CommandUsage.of(syntax, description);
    }

    private static CommandUsage u(String syntax, String description, String permission) {
        return CommandUsage.of(syntax, description, permission);
    }

    private static Map.Entry<String, List<CommandUsage>> e(String command, CommandUsage... usages) {
        return Map.entry(command, List.of(usages));
    }

    private static final String HIDE = "allium.hide.admin";
    private static final String CM = "allium.gamemode.creative.manage";
    private static final String PT_EDIT = "allium.powertool.edit";
    private static final String PT_SAVE = "allium.powertool.save";
    private static final String PT_GIVE = "allium.powertool.give";

    private static final Map<String, List<CommandUsage>> USAGES = Map.ofEntries(
            e("heal", u("", "Heals you"), u("<player>", "Heals another player", "allium.heal.others")),
            e("feed", u("", "Fills your hunger"), u("<player>", "Fills another player's hunger", "allium.feed.others")),
            e("fly", u("", "Toggles your flight; without allium.fly, your timed flight"),
                    u("<player>", "Toggles another player's flight", "allium.fly.others"),
                    u("<player> add <time>", "Gives timed flight, e.g. 30m, 2h or 1d", "allium.tfly.add"),
                    u("<player> take <time>", "Takes timed flight", "allium.tfly.take"),
                    u("<player> set <time>", "Sets a player's timed flight", "allium.tfly.set"),
                    u("<player> reset", "Removes all of a player's timed flight", "allium.tfly.reset")),
            e("god", u("", "Toggles your god mode"), u("<player>", "Toggles another player's god mode", "allium.god.others")),
            e("toggleslowfall", u("", "Toggles slow falling when you lose flight"),
                    u("<on|off>", "Turns slow falling on flight loss on or off"),
                    u("status", "Shows whether it is on")),
            e("gamemode", u("<mode>", "Changes your gamemode"),
                    u("<mode> <player>", "Changes another player's gamemode", "allium.gamemode.others")),
            e("give", u("<player> <item> [amount]", "Gives a player an item"),
                    u("<player> <item,item...> [amount]", "Gives several items at once"),
                    u("<player> <item> [amount] [key:value...]", "Gives an item with a name, lore, enchantments or other properties"),
                    u("<player> <item> [amount] --overflow=<drop|refuse|partial>", "Chooses what happens when the inventory is full")),
            e("i", u("<item> [amount]", "Gives you an item"),
                    u("<item,item...> [amount]", "Gives you several items at once"),
                    u("<item> [amount] [key:value...]", "Gives you an item with a name, lore, enchantments or other properties")),
            e("allium", u("version", "Shows the Allium version"),
                    u("reload", "Reloads the configuration and language files", "allium.reload"),
                    u("sethomes <player> [number|+N|-N|-1]", "Shows or overrides a player's max homes", "allium.sethomes"),
                    u("import lang [apply]", "Imports messages from the pre-recode lang file (a dry run unless apply)", "allium.import"),
                    u("import vouchers [apply]", "Makes a powertool of each pre-recode voucher (a dry run unless apply)", "allium.import"),
                    u("import legacy [apply <run>]", "Imports homes, warps and logout positions from legacy Allium", "allium.import"),
                    u("adopt-window <duration>", "Adopts pre-recode privileged tools on first use for a while", "allium.adoptwindow"),
                    u("adopt-window off", "Closes the adoption window", "allium.adoptwindow"),
                    u("creativemanager [gui|active]", "Opens the creative manager; active lists the players", CM),
                    u("creativemanager <player|group> <name> [edit|list|check|add|remove] [flag]", "Edits a player's or group's creative flags", CM),
                    u("hide status", "Shows command hiding status and groups", HIDE),
                    u("hide reload", "Reloads hide.yml", HIDE),
                    u("hide enabled <true|false>", "Turns command hiding on or off", HIDE),
                    u("hide resolve <player> <command>", "Shows which permission decides a player's access to a command", HIDE),
                    u("hide fix <command> <permission>", "Overrides the permission used for a command", HIDE),
                    u("hide unfix <command>", "Removes a permission override", HIDE),
                    u("hide group <name> create <whitelist|blacklist>", "Creates a hide group", HIDE),
                    u("hide group <name> whitelist <true|false>", "Switches a group between whitelist and blacklist", HIDE),
                    u("hide group <name> inherit <commands|tabcompletes> <groups>", "Inherits rules from other groups", HIDE),
                    u("hide group <name> add [-c|-t|-tc] <rules>", "Adds command and tab-complete rules", HIDE),
                    u("hide group <name> remove [-c|-t|-tc] <rules>", "Removes command and tab-complete rules", HIDE),
                    u("hide group <name> check [-c|-t|-tc] <rules>", "Tests rules against a group", HIDE)),
            e("help", u("", "Shows the commands you can use"),
                    u("<page>", "Shows a page of the help menu"),
                    u("<command>", "Shows help for one command"),
                    u("<search> [page]", "Searches commands by name and description"),
                    u("<plugin> [page]", "Lists one plugin's commands", "allium.help.plugins")),
            e("msg", u("<player> <message>", "Sends a private message")),
            e("reply", u("<message>", "Replies to the last player who messaged you")),
            e("ignore", u("", "Lists the players you ignore"), u("<player>", "Ignores a player's private messages")),
            e("unignore", u("<player>", "Stops ignoring a player")),
            e("msgtoggle", u("", "Toggles receiving private messages"),
                    u("<on|off>", "Turns receiving private messages on or off"),
                    u("<on|off> <player>", "Turns another player's private messages on or off", "allium.msgtoggle.others")),
            e("messagesounds", u("", "Shows your private-message sound volume"),
                    u("volume <0-1>", "Sets your private-message sound volume"),
                    u("toggle", "Turns private-message sounds on or off"),
                    u("reset", "Goes back to the server's default volume")),
            e("socialspy", u("", "Toggles social spy"), u("<on|off>", "Turns social spy on or off")),
            e("commandspy", u("", "Toggles command spy"), u("<on|off>", "Turns command spy on or off")),
            e("spy", u("", "Shows what you spy on everywhere, with clickable toggles"),
                    u("<player>", "Shows spy status for a player with clickable toggles")),
            e("spectate", u("<player>", "Runs the configured spectate commands for a player")),
            e("kit", u("", "Lists the kits you can claim"), u("<name>", "Claims a kit"),
                    u("<name> <player>", "Gives a kit to another player", "allium.kit.others")),
            e("createkit", u("<name>", "Creates a kit from your inventory"),
                    u("<name> <cooldown>", "Creates a kit with a cooldown, e.g. 1d"),
                    u("<name> <cooldown> once", "Creates a kit each player can claim only once")),
            e("vanish", u("", "Toggles vanish"), u("<on|off>", "Turns vanish on or off"),
                    u("<on|off> <player>", "Turns vanish on or off for another player", "allium.vanish.others")),
            e("mute", u("<player> [reason]", "Mutes a player until they are unmuted"),
                    u("<player> <duration> [reason]", "Mutes a player for a set time, e.g. 30m, 2h or 7d")),
            e("freeze", u("<player>", "Freezes or unfreezes a player"),
                    u("<player> <on|off> [reason]", "Freezes or unfreezes a player, with a reason")),
            e("warn", u("<player> [reason]", "Warns a player and records it"),
                    u("<player> history", "Shows a player's warnings")),
            e("notes", u("<player>", "Reads the staff notes on a player"),
                    u("<player> add <note>", "Adds a staff note")),
            e("enderchest", u("", "Opens your ender chest"),
                    u("<player>", "Inspects another player's ender chest", "allium.enderchest.others")),
            e("nick", u("<nickname>", "Changes your nickname to the given text"),
                    u("off", "Removes your nickname"),
                    u("<player> <nickname>", "Changes the specified player's nickname to the given text", "allium.nick.others"),
                    u("<player> off", "Removes the given player's nickname", "allium.nick.others")),
            e("channel", u("", "Lists the channels you can read"),
                    u("join <channel> [read|write]", "Joins a channel to read it, or to write to it"),
                    u("leave <channel>", "Leaves a channel")),
            e("action", u("<message>", "Describes what you are doing, as * You <message>")),
            e("delmsg", u("<id>", "Deletes one chat message for everyone"),
                    u("<player> <amount>", "Deletes a player's last messages (1-200)")),
            e("paytoggle", u("", "Toggles incoming payments"), u("<on|off>", "Allows or refuses incoming payments"),
                    u("<on|off> <player>", "Allows or refuses another player's incoming payments", "allium.paytoggle.others")),
            e("sethome", u("", "Sets your default home here"), u("<name>", "Sets a named home here"),
                    u("<name> confirm", "Moves an existing home here")),
            e("home", u("", "Teleports you to your default home"), u("<name>", "Teleports you to one of your homes"),
                    u("bed", "Teleports you to your bed", "allium.home.bed"),
                    u("<player>:<home>", "Teleports you to another player's home", "allium.home.others"),
                    u("<player>:", "Lists another player's homes", "allium.home.others"),
                    u("<player>:bed", "Teleports you to another player's bed", "allium.home.others.bed")),
            e("delhome", u("", "Deletes your default home"), u("<name>", "Deletes one of your homes"),
                    u("<player>:<home>", "Deletes another player's home", "allium.delhome.others")),
            e("renamehome", u("<home> <newhome>", "Renames one of your homes"),
                    u("<player>:<home> <newhome>", "Renames another player's home", "allium.renamehome.others")),
            e("homes", u("", "Lists your homes"), u("<player>", "Lists another player's homes", "allium.homes.others")),
            e("bed", u("", "Teleports you to your bed")),
            e("setwarp", u("<name>", "Creates a warp here"), u("<name> confirm", "Moves an existing warp here")),
            e("warp", u("<name>", "Teleports you to a warp"),
                    u("<name> <player>", "Teleports another player to a warp", "allium.warp.others")),
            e("warpinfo", u("<name>", "Shows a warp's world, coordinates and permission")),
            e("tpaccept", u("", "Accepts your latest teleport request"), u("<player>", "Accepts a player's teleport request")),
            e("tpdeny", u("", "Denies your latest teleport request"), u("<player>", "Denies a player's teleport request")),
            e("tptoggle", u("", "Toggles teleport requests to you"), u("<on|off>", "Allows or blocks teleport requests to you"),
                    u("<on|off> <player>", "Allows or blocks teleport requests to another player", "allium.tptoggle.others")),
            e("tp", u("<player>", "Teleports you to a player"),
                    u("<player> <target>", "Teleports a player to another player", "allium.tp.others"),
                    u("<x> <y> <z> [yaw] [pitch] [world]", "Teleports you to coordinates", "allium.tp.position")),
            e("tpo", u("<player>", "Teleports you to a player, bypassing /tptoggle"),
                    u("<player> <target>", "Teleports a player to another player, bypassing /tptoggle")),
            e("tpohere", u("<player>", "Teleports a player to you, bypassing /tptoggle")),
            e("there", u("", "Teleports you to the block you are looking at"),
                    u("<players|selectors...>", "Sends players or selected entities there")),
            e("tphere", u("<player>", "Teleports a player to you"),
                    u("<players|selectors...>", "Teleports several players or selected entities to you")),
            e("repair", u("", "Repairs the item in your hand"),
                    u("all", "Repairs every damaged item in your inventory", "allium.repair.all")),
            e("speed", u("<0-10|reset>", "Sets your walk or fly speed, whichever you are using"),
                    u("<walk|fly> <0-10|reset>", "Sets your walk or fly speed"),
                    u("<walk|fly> <0-10|reset> <player>", "Sets another player's walk or fly speed", "allium.speed.others")),
            e("scale", u("<0-16>", "Sets your size"), u("<0-16> <player>", "Sets another player's size", "allium.scale.others")),
            e("coords", u("", "Shows your world, position and facing"),
                    u("<player>", "Shows another player's position", "allium.getpos.others")),
            e("compass", u("", "Shows the direction you are facing")),
            e("glow", u("<color>", "Glows in a colour"), u("rainbow", "Glows through the colours", "allium.glow.rainbow"),
                    u("off", "Stops glowing"), u("spectator", "Shows your glow only while in spectator", "allium.gamemode.spectator"),
                    u("<color|rainbow|off> <player>", "Sets another player's glow", "allium.glow.others")),
            e("smite", u("", "Strikes visual lightning where you are looking"),
                    u("<player>", "Strikes a player with visual lightning")),
            e("ping", u("", "Shows your ping"), u("<player>", "Shows another player's ping", "allium.ping.others")),
            e("ext", u("", "Puts out the fire on you"), u("<player>", "Puts out the fire on another player", "allium.ext.others")),
            e("nv", u("", "Toggles night vision"), u("<on|off>", "Turns night vision on or off"),
                    u("<on|off> <player>", "Turns night vision on or off for another player", "allium.nv.others")),
            e("more", u("", "Fills the stack in your hand"), u("<amount>", "Sets the size of the stack in your hand"),
                    u("<amount> <player>", "Sets the size of another player's held stack", "allium.more.others")),
            e("skull", u("", "Gives you your own head"), u("<owner>", "Gives you a player's head"),
                    u("<owner> <player>", "Gives another player a head", "allium.skull.others")),
            e("itemdb", u("", "Inspects the item in your hand"),
                    u("<player>", "Inspects another player's held item", "allium.itemdb.others")),
            e("time", u("", "Shows the time"),
                    u("set <time> [world|*]", "Sets the time, e.g. day, night or 6000", "allium.time.set"),
                    u("add <ticks> [world|*]", "Moves the time forward", "allium.time.add"),
                    u("pause [world|*]", "Pauses or resumes the time", "allium.time.pause")),
            e("day", u("", "Sets your world to day"), u("<world|*>", "Sets a world (or every world) to day")),
            e("night", u("", "Sets your world to night"), u("<world|*>", "Sets a world (or every world) to night")),
            e("enchant", u("<enchantment> <level>", "Enchants the item in your hand"),
                    u("<enchantment> 0", "Removes an enchantment from the item in your hand"),
                    u("<enchantment> <level> <player>", "Enchants another player's held item", "allium.enchant.others")),
            e("near", u("", "Lists the players near you"), u("<radius>", "Lists the players within a radius")),
            e("rtp",
                    u("", "Teleports you to a random safe spot in this world"),
                    u("<location|world>", "Teleports you to a random spot of a location or world"),
                    u("<location|world> <player>", "Sends another player to a random spot", "allium.rtp.others"),
                    u("biome <biome[,biome...]> [location]", "Teleports you to a random spot in one of those biomes", "allium.rtp.biome"),
                    u("info [location]", "Shows a location's centre, range, shape and heights"),
                    u("list", "Lists the random teleport locations")),
            e("settpr",
                    u("<location> center", "Centres the location where you stand", "allium.settpr"),
                    u("<location> minrange <blocks>", "Sets how close to the centre a spot may be", "allium.settpr"),
                    u("<location> maxrange <blocks>", "Sets how far from the centre a spot may be", "allium.settpr"),
                    u("<location> shape <square|circle>", "Searches a square or a circle", "allium.settpr"),
                    u("<location> miny <y|none>", "Sets the lowest landing height", "allium.settpr"),
                    u("<location> maxy <y|none>", "Sets the highest landing height (searches upward below it)", "allium.settpr"),
                    u("<location> world <world>", "Sets the location's world", "allium.settpr"),
                    u("<location> remove", "Deletes the location", "allium.settpr")),
            e("worth",
                    u("", "Shows what the item you hold sells for"),
                    u("hand [amount]", "Shows what the held item, or that many of it, sells for"),
                    u("<item> [amount]", "Shows what an item, or that many of it, sells for"),
                    u("inventory", "Shows what your whole inventory sells for"),
                    u("blocks", "Shows what the blocks in your inventory sell for")),
            e("sell",
                    u("hand [amount]", "Sells all items like the one you hold, or that many", "allium.sell.hand"),
                    u("<item> [amount]", "Sells all of an item, or that many (-10 keeps ten, 2s is two stacks)"),
                    u("inventory", "Sells everything sellable; repeat to confirm", "allium.sell.bulk"),
                    u("blocks", "Sells every sellable block; repeat to confirm", "allium.sell.bulk")),
            e("setworth",
                    u("<price>", "Sets the price of the item you hold", "allium.setworth"),
                    u("<item> <price>", "Sets an item's price; a negative price stops it selling", "allium.setworth")),
            e("voucher",
                    u("give <player> <voucher> [amount]", "Gives a player vouchers (a voucher is a powertool named voucher_<name>)", "allium.voucher.give"),
                    u("list", "Lists the vouchers", "allium.voucher.give"),
                    u("reload", "Reloads the powertools, vouchers included", "allium.voucher.reload")),
            e("antioch", u("", "Throws primed TNT where you are looking and announces it")),
            e("fireball",
                    u("", "Throws a fireball where you are looking"),
                    u("<type> [speed]", "Throws a fireball, arrow, skull, egg, snowball, potion, trident or other projectile"),
                    u("[type] <speed> ride", "Throws it and rides it", "allium.fireball.ride")),
            e("beezooka", u("", "Throws a bee that explodes harmlessly")),
            e("kittycannon", u("", "Throws a kitten that explodes harmlessly")),
            e("nuke",
                    u("", "Rains TNT on every player online; it breaks no blocks"),
                    u("<player...>", "Rains TNT on those players")),
            e("explode",
                    u("[power]", "Drops an exploding fireball on you"),
                    u("<player> [power]", "Drops one on another player", "allium.explode.others")),
            e("creativemanager",
                    u("", "Opens the menu of groups and players", CM),
                    u("gui", "Opens the menu of groups and players", CM),
                    u("active", "Opens the menu on the players, online and offline", CM),
                    u("player <player> [edit]", "Opens a player's creative flags", CM),
                    u("player <player> list", "Lists the flags set on a player", CM),
                    u("player <player> check [flag]", "Shows whether flags apply to a player, and why", CM),
                    u("player <player> add <flag>", "Gives a player a creative flag", CM),
                    u("player <player> remove <flag>", "Removes a player's own creative flag", CM),
                    u("group <group> [edit]", "Opens a group's creative flags", CM),
                    u("group <group> list", "Lists the flags set on a group", CM),
                    u("group <group> check [flag]", "Shows whether flags apply to a group", CM),
                    u("group <group> add <flag>", "Gives a group a creative flag", CM),
                    u("group <group> remove <flag>", "Removes a creative flag from a group", CM)),
            e("powertool",
                    u("add [flags...] <action> [args...]", "Adds an action to the item in your hand", PT_EDIT),
                    u("set <index> [flags...] <action> [args...]", "Replaces action number <index> on a trigger", PT_EDIT),
                    u("remove [trigger] [index]", "Removes one action, or every action on a trigger", PT_EDIT),
                    u("clear", "Removes every action from the item in your hand"),
                    u("cooldown [--t <trigger>] <duration> [--scope player|item|global]", "Sets a trigger's cooldown", PT_EDIT),
                    u("perm [--t <trigger>] <permission|none>", "Sets the permission needed to use the tool or a trigger", PT_EDIT),
                    u("uses <max|unlimited> [--consume true|false] [--recharge <duration>]", "Limits uses, with optional consume and recharge", PT_EDIT),
                    u("cancel [--t <trigger>] <true|false>", "Cancels or allows the normal click action", PT_EDIT),
                    u("sneak [--t <trigger>] <any|require|disallow>", "Requires or forbids sneaking", PT_EDIT),
                    u("hand [--t <trigger>] <main|off|both>", "Chooses which hand the tool works in", PT_EDIT),
                    u("info [tool]", "Shows the item in your hand or a saved tool"),
                    u("save <name> [--overwrite]", "Saves the item in your hand to tools/<name>.yml", PT_SAVE),
                    u("delete <name>", "Deletes a saved tool", PT_SAVE),
                    u("enable <tool>", "Turns a tool on"),
                    u("disable <tool>", "Turns a tool off"),
                    u("bind <name>", "Puts a saved tool's actions on the item in your hand", "allium.powertool.bind"),
                    u("get <item> [amount]", "Gives you a saved tool", PT_GIVE),
                    u("give <player> <item> [amount]", "Gives a player a saved tool", PT_GIVE),
                    u("list [filter]", "Lists tools and whether they are on"),
                    u("toggle", "Turns powertools on or off for yourself", "allium.powertool.toggle"),
                    u("reload", "Reloads every tool and the powertools settings", "allium.powertool.reload"),
                    u("help <triggers|actions|placeholders>", "Lists the triggers, actions or placeholders you can use")),
            e("afk", u("", "Toggles your AFK status"), u("<message>", "Goes AFK with a message")),
            e("playtime", u("", "Shows how long you have played and when you first joined"),
                    u("<player>", "Shows another player's playtime", "allium.playtime.others")),
            e("restore", u("", "Browses your inventory snapshots"),
                    u("<player>", "Browses another player's inventory snapshots", "allium.restore.others")),
            e("rename", u("<name>", "Renames the item in your hand; colors and gradients work"),
                    u("clear", "Removes the custom name from the item in your hand")),
            e("lore", u("add <text>", "Adds a line to the lore of the item in your hand"),
                    u("set <line> <text>", "Replaces a lore line"),
                    u("insert <line> <text>", "Inserts a lore line before another"),
                    u("remove <line>", "Removes a lore line"),
                    u("clear", "Removes all lore"),
                    u("copy", "Copies the lore of the item in your hand"),
                    u("paste", "Replaces the lore of the item in your hand with the copied lore")),
            e("xp", u("", "Shows your experience"),
                    u("<player>", "Shows another player's experience", "allium.xp.others"),
                    u("give <player> <amount>[L]", "Gives experience points, or levels with L (e.g. 5L)", "allium.xp.give"),
                    u("take <player> <amount>[L]", "Takes experience points, or levels with L", "allium.xp.take"),
                    u("set <player> <amount>[L]", "Sets experience points, or levels with L", "allium.xp.set"),
                    u("reset <player>", "Removes all of a player's experience", "allium.xp.set")),
            e("customtext", u("", "Shows the custom text index"),
                    u("<section> [page]", "Shows a section of the custom text")),
            e("motd", u("", "Shows the message of the day"),
                    u("<section> [page]", "Shows a section of the message of the day")),
            e("broadcast", u("<message>", "Sends a message to everyone online; colours and click tags work")),
            e("broadcasts", u("", "Shows the timed broadcasts and their settings"),
                    u("send <name>", "Sends one broadcast now"),
                    u("next", "Sends the next broadcast in the rotation now")),
            e("rules", u("", "Shows the server rules"),
                    u("<chapter> [page]", "Shows a chapter of the rules")),
            e("tpall", u("", "Teleports all online players to you, bypassing /tptoggle"),
                    u("<player>", "Teleports all online players to another player, bypassing /tptoggle")),
            e("spawnmob", u("<mob>[:data] [amount]", "Spawns mobs where you are looking"),
                    u("<mob>[:data],<mount>[:data] [amount]", "Spawns stacked mobs", "allium.spawnmob.stack"),
                    u("<mob>[:data] <amount> <player>", "Spawns mobs at another player", "allium.spawnmob.others")));

    /** The hand-written usage lines for a command family, or none. */
    public static List<CommandUsage> of(String command) {
        if (command == null) {
            return List.of();
        }
        return USAGES.getOrDefault(command.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * The lines a usage card shows for {@code spec}. A command that reported a narrower usage
     * ({@code /allium hide group <name> ...}) sees only the lines sharing its leading subcommand
     * words, when there are any. Without hand-written lines the subcommand usages, then the
     * reported usage, are shown without descriptions.
     */
    public static List<CommandUsage> select(CommandSpec spec, String requestedUsage) {
        List<CommandUsage> listed = of(spec.canonicalName());
        if (listed.isEmpty()) {
            listed = fromChildren(spec);
        }
        if (listed.isEmpty()) {
            return derive(requestedUsage == null || requestedUsage.isBlank() ? spec.selfUsage() : requestedUsage);
        }
        if (requestedUsage != null && !requestedUsage.equals(spec.selfUsage())) {
            // A command that reported one exact form (the console's /heal <player>) gets just that line.
            String wanted = afterLabel(firstAlternative(requestedUsage));
            List<CommandUsage> exact = listed.stream().filter(u -> u.syntax().equalsIgnoreCase(wanted)).toList();
            if (!exact.isEmpty()) {
                return exact;
            }
        }
        List<CommandUsage> narrowed = narrow(listed, requestedUsage);
        return narrowed.isEmpty() ? listed : narrowed;
    }

    /** The command a usage string names ({@code give} for {@code /give <player>}), lower-cased, or null. */
    public static String commandOf(String usage) {
        if (usage == null) {
            return null;
        }
        String trimmed = usage.trim();
        if (!trimmed.startsWith("/") || trimmed.length() < 2) {
            return null;
        }
        int space = trimmed.indexOf(' ');
        String command = (space < 0 ? trimmed.substring(1) : trimmed.substring(1, space)).toLowerCase(Locale.ROOT);
        return command.substring(command.lastIndexOf(':') + 1);
    }

    /** Lines sharing the requested usage's leading subcommand words; empty when it has none or none match. */
    static List<CommandUsage> narrow(List<CommandUsage> usages, String requestedUsage) {
        List<String> wanted = requestedLiterals(requestedUsage);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<CommandUsage> out = new ArrayList<>();
        for (CommandUsage usage : usages) {
            List<String> literals = usage.literalPrefix();
            if (literals.size() >= wanted.size() && literals.subList(0, wanted.size()).equals(wanted)) {
                out.add(usage);
            }
        }
        return out;
    }

    /** {@code /allium hide group <name>} gives {@code [hide, group]}: the words after the label up to the first argument. */
    static List<String> requestedLiterals(String requestedUsage) {
        List<String> tokens = CommandUsage.tokens(firstAlternative(requestedUsage));
        List<String> out = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (CommandUsage.isArgument(token) || token.contains("|")) {
                break;
            }
            out.add(token.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Each alternative of a reported usage ({@code /tp <player> | /tp <x> <y> <z>}) as a line without a description. */
    public static List<CommandUsage> derive(String usage) {
        List<CommandUsage> out = new ArrayList<>();
        if (usage == null) {
            return out;
        }
        for (String alternative : usage.split(" \\| ")) {
            out.add(CommandUsage.of(afterLabel(alternative), ""));
        }
        return out;
    }

    private static List<CommandUsage> fromChildren(CommandSpec spec) {
        List<CommandUsage> out = new ArrayList<>();
        for (CommandNodeSpec child : spec.children()) {
            if (child.usage() != null && !child.usage().isBlank()) {
                out.add(CommandUsage.of(afterLabel(child.usage()), "", child.permissionNode()));
            }
        }
        return out;
    }

    private static String firstAlternative(String usage) {
        if (usage == null) {
            return "";
        }
        int bar = usage.indexOf(" | ");
        return bar < 0 ? usage : usage.substring(0, bar);
    }

    /** {@code /gamemode <mode> [player]} becomes {@code <mode> [player]}. */
    static String afterLabel(String usage) {
        String trimmed = usage == null ? "" : usage.trim();
        if (!trimmed.startsWith("/")) {
            return trimmed;
        }
        int space = trimmed.indexOf(' ');
        return space < 0 ? "" : trimmed.substring(space + 1).trim();
    }
}
