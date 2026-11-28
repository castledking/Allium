package codes.castled.allium.portable;

/** Shared command output for adapters that run on different Java and Minecraft versions. */
public final class UtilityMessages {
    public static final String COMMANDS = "Allium commands: /allium version|reload, /ping, /coords, /depth, /list, "
            + "/sethome, /home, /delhome, /homes, /renamehome, /setwarp, /warp, /delwarp, /warps, "
            + "/setspawn, /spawn, /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel, /tphere, /back, "
            + "/tptoggle, /tpauto, /pm, /reply, /rtoggle, /msgtoggle, /ignore, /unignore, /ignorelist, "
            + "/channel, /g, /l, /a, /me, "
            + "/mute, /unmute, /setjail, /deljail, /jails, /jail, /unjail, /jailedplayers, "
            + "/warn, /warnings, /notes, /unnote, /freeze, /kickall, /vanish, /report, /helpop, "
            + "/mail, /seen, /whois, /playtime, /afk, /nick, /realname, /near, "
            + "/info, /rules, /motd, /customtext, /inform, /broadcasts, "
            + "/bal, /pay, /paytoggle, /payconfirmtoggle, /eco, /baltop, /worth, /setworth, /sell, "
            + "/heal, /feed, /fly, /gmc, /gms, /gma, /gmsp, /day, /night, /sun, /rain, /storm, "
            + "/clearinventory, /broadcast";

    private UtilityMessages() {}

    public static String version(String version, String platform, String game) {
        return "Allium " + version + " on " + platform + " (Minecraft " + game + ")";
    }

    public static String ping(String player, int latency) {
        return player + " has " + latency + " ms latency.";
    }

    public static String coordinates(String player, int x, int y, int z, String world) {
        return player + " is at " + x + ", " + y + ", " + z + " in " + world + ".";
    }

    public static String depth(int y, int seaLevel) {
        int diff = y - seaLevel;
        if (diff == 0) {
            return "You are at sea level (Y=" + seaLevel + ").";
        }
        if (diff > 0) {
            return "You are " + diff + " block(s) above sea level (Y=" + y + ").";
        }
        return "You are " + (-diff) + " block(s) below sea level (Y=" + y + ").";
    }
}
