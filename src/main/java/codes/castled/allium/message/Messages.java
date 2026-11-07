package codes.castled.allium.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public enum Messages {

    // -- Core --
    PREFIX("prefix", "&7[&dAllium&7]"),
    RELOAD_SUCCESS("core.reload-success", "{prefix} &aConfiguration reloaded."),
    RELOAD_FAILED("core.reload-failed", "{prefix} &cReload failed. Check console for details."),
    PLUGIN_VERSION("core.version", "{prefix} &fRunning Allium &dv{version}&f."),

    // -- Errors --
    NO_PERMISSION("error.no-permission", "{prefix} &cYou don't have permission to do that."),
    PLAYER_ONLY("error.player-only", "{prefix} &cThis command can only be used by players."),
    CONSOLE_ONLY("error.console-only", "{prefix} &cThis command can only be used from the console."),
    INVALID_ARGS("error.invalid-args", "{prefix} &cUsage: &f{usage}"),
    PLAYER_NOT_FOUND("error.player-not-found", "{prefix} &cPlayer &f{player} &cnot found."),
    NUMBER_INVALID("error.number-invalid", "{prefix} &c'{input}' is not a valid number."),
    WORLD_NOT_FOUND("error.world-not-found", "{prefix} &cWorld &f{world} &cnot found."),
    COOLDOWN_ACTIVE("error.cooldown-active", "{prefix} &cPlease wait &f{time} &cbefore using this again."),
    MODULE_DISABLED("error.module-disabled", "{prefix} &cThis feature is currently disabled."),

    // -- Teleportation --
    TELEPORT_SUCCESS("teleport.success", "{prefix} &aTeleported to &f{target}&a."),
    TELEPORT_REQUEST_SENT("teleport.request-sent", "{prefix} &aTeleport request sent to &f{player}&a."),
    TELEPORT_REQUEST_RECEIVED("teleport.request-received", "{prefix} &f{player} &awants to teleport to you. &f/tpaccept &aor &f/tpdeny"),
    TELEPORT_ACCEPTED("teleport.accepted", "{prefix} &aTeleport request accepted."),
    TELEPORT_DENIED("teleport.denied", "{prefix} &cTeleport request denied."),
    TELEPORT_CANCELLED("teleport.cancelled", "{prefix} &cTeleport request expired."),
    TELEPORT_BACK("teleport.back", "{prefix} &aReturned to your previous location."),
    TELEPORT_NO_BACK("teleport.no-back", "{prefix} &cNo previous location to return to."),
    TELEPORT_NO_PENDING("teleport.no-pending", "{prefix} &cYou have no pending teleport requests."),

    // -- Homes --
    HOME_SET("home.set", "{prefix} &aHome &f{name} &aset."),
    HOME_DELETED("home.deleted", "{prefix} &aHome &f{name} &adeleted."),
    HOME_NOT_FOUND("home.not-found", "{prefix} &cHome &f{name} &cnot found."),
    HOME_LIMIT("home.limit-reached", "{prefix} &cYou have reached your home limit (&f{limit}&c)."),
    HOME_LIST("home.list", "{prefix} &fHomes: &d{homes}"),
    HOME_NONE("home.none", "{prefix} &cYou have no homes set."),

    // -- Warps --
    WARP_SET("warp.set", "{prefix} &aWarp &f{name} &aset."),
    WARP_DELETED("warp.deleted", "{prefix} &aWarp &f{name} &adeleted."),
    WARP_NOT_FOUND("warp.not-found", "{prefix} &cWarp &f{name} &cnot found."),
    WARP_LIST("warp.list", "{prefix} &fWarps: &d{warps}"),
    WARP_NONE("warp.none", "{prefix} &cNo warps available."),

    // -- Spawn --
    SPAWN_SET("spawn.set", "{prefix} &aSpawn point set."),
    SPAWN_TELEPORT("spawn.teleport", "{prefix} &aTeleported to spawn."),
    SPAWN_NOT_SET("spawn.not-set", "{prefix} &cSpawn has not been set."),

    // -- Kits --
    KIT_RECEIVED("kit.received", "{prefix} &aReceived kit &f{name}&a."),
    KIT_NOT_FOUND("kit.not-found", "{prefix} &cKit &f{name} &cnot found."),
    KIT_LIST("kit.list", "{prefix} &fKits: &d{kits}"),
    KIT_NONE("kit.none", "{prefix} &cNo kits available."),
    KIT_COOLDOWN("kit.cooldown", "{prefix} &cKit &f{name} &con cooldown. Wait &f{time}&c."),

    // -- Economy --
    BALANCE("economy.balance", "{prefix} &fBalance: &a{amount}"),
    BALANCE_OTHER("economy.balance-other", "{prefix} &f{player}&f's balance: &a{amount}"),
    PAY_SENT("economy.pay-sent", "{prefix} &aSent &f{amount} &ato &f{player}&a."),
    PAY_RECEIVED("economy.pay-received", "{prefix} &aReceived &f{amount} &afrom &f{player}&a."),
    PAY_INSUFFICIENT("economy.insufficient", "{prefix} &cInsufficient funds."),
    PAY_INVALID_AMOUNT("economy.invalid-amount", "{prefix} &cAmount must be greater than zero."),

    // -- Chat --
    MSG_FORMAT("chat.msg-format", "&7[&f{sender} &7-> &f{receiver}&7] &f{message}"),
    MSG_NO_REPLY("chat.no-reply", "{prefix} &cNo one to reply to."),
    SOCIALSPY_TOGGLE("chat.socialspy-toggle", "{prefix} &fSocialSpy {state}&f."),
    BROADCAST_FORMAT("chat.broadcast", "&4[Broadcast] &c{message}"),

    // -- Moderation --
    BAN("moderation.ban", "{prefix} &f{player} &chas been banned. Reason: &f{reason}"),
    BAN_TEMP("moderation.tempban", "{prefix} &f{player} &cbanned for &f{duration}&c. Reason: &f{reason}"),
    KICK("moderation.kick", "{prefix} &f{player} &chas been kicked. Reason: &f{reason}"),
    MUTE("moderation.mute", "{prefix} &f{player} &chas been muted. Reason: &f{reason}"),
    MUTE_TEMP("moderation.tempmute", "{prefix} &f{player} &cmuted for &f{duration}&c. Reason: &f{reason}"),
    UNMUTE("moderation.unmute", "{prefix} &f{player} &ahas been unmuted."),
    WARN("moderation.warn", "{prefix} &f{player} &chas been warned: &f{reason}"),
    MUTED_CHAT("moderation.muted-chat", "{prefix} &cYou are muted and cannot chat."),

    // -- Player --
    GAMEMODE_SELF("player.gamemode-self", "{prefix} &aGamemode set to &f{mode}&a."),
    GAMEMODE_OTHER("player.gamemode-other", "{prefix} &aSet &f{player}&a's gamemode to &f{mode}&a."),
    FLY_TOGGLE("player.fly-toggle", "{prefix} &fFly mode {state} &ffor &d{player}&f."),
    GOD_TOGGLE("player.god-toggle", "{prefix} &fGod mode {state} &ffor &d{player}&f."),
    HEAL("player.heal", "{prefix} &aYou have been healed."),
    HEAL_OTHER("player.heal-other", "{prefix} &aHealed &f{player}&a."),
    FEED("player.feed", "{prefix} &aYou have been fed."),
    FEED_OTHER("player.feed-other", "{prefix} &aFed &f{player}&a."),
    SPEED_SET("player.speed-set", "{prefix} &a{type} speed set to &f{speed}&a."),
    VANISH_TOGGLE("player.vanish-toggle", "{prefix} &fVanish {state}&f."),
    AFK_TOGGLE("player.afk-toggle", "{prefix} &f{player} &7is {state} AFK."),
    NICK_SET("player.nick-set", "{prefix} &aNickname set to &f{nick}&a."),
    NICK_RESET("player.nick-reset", "{prefix} &aNickname reset."),

    // -- Utility --
    TIME_SET("utility.time-set", "{prefix} &aTime set to &f{time} &ain &f{world}&a."),
    WEATHER_SET("utility.weather-set", "{prefix} &aWeather set to &f{weather} &ain &f{world}&a."),
    SEEN_ONLINE("utility.seen-online", "{prefix} &f{player} &ais currently online. Joined &f{time} &aago."),
    SEEN_OFFLINE("utility.seen-offline", "{prefix} &f{player} &cwas last seen &f{time} &cago."),
    NEAR("utility.near", "{prefix} &fNearby players: &d{players}"),
    NEAR_NONE("utility.near-none", "{prefix} &cNo players nearby."),

    // -- Tools --
    TOOL_DISARMER_DISARMED("tool.mob-disarmer.disarmed", "{prefix} &aDisarmed &f{target}&a: &f{removed} &aremoved, &f{dropped} &adropped. Charges: &f{charges}&7/&f{max}"),
    TOOL_DISARMER_DISARMED_BYPASS("tool.mob-disarmer.disarmed-bypass", "{prefix} &aDisarmed &f{target}&a: &f{removed} &aremoved, &f{dropped} &adropped."),
    TOOL_DISARMER_LAST_CHARGE("tool.mob-disarmer.last-charge", "{prefix} &cThat was the last charge. Fully recharged in &f{time}&c."),
    TOOL_DISARMER_BROKE("tool.mob-disarmer.broke", "{prefix} &cThe Mob Disarmer broke while in use."),
    TOOL_DISARMER_BROKEN("tool.mob-disarmer.broken", "{prefix} &cThis Mob Disarmer is broken."),
    TOOL_DISARMER_RECHARGING("tool.mob-disarmer.recharging", "{prefix} &cThe Mob Disarmer is recharging. Ready in &f{time}&c."),
    TOOL_DISARMER_STATUS("tool.mob-disarmer.status", "{prefix} &eRight-click a mob to disarm it. &7Charges: &a{charges}&7/{max}"),
    TOOL_DISARMER_STATUS_BYPASS("tool.mob-disarmer.status-bypass", "{prefix} &eRight-click a mob to disarm it. &7Charges: &aunlimited"),
    TOOL_DISARMER_PLAYER("tool.mob-disarmer.player-target", "{prefix} &cThe Mob Disarmer does not work on players."),
    TOOL_DISARMER_EXCLUDED("tool.mob-disarmer.excluded", "{prefix} &cThe Mob Disarmer does not work on &f{target}&c."),
    TOOL_DISARMER_TOO_FAR("tool.mob-disarmer.too-far", "{prefix} &cThat mob is too far away."),
    TOOL_DISARMER_PROTECTED("tool.mob-disarmer.protected", "{prefix} &cYou can't disarm that mob here."),
    TOOL_DISARMER_PROTECTED_REASON("tool.mob-disarmer.protected-reason", "{prefix} &c{reason}"),
    TOOL_DISARMER_SINGLE("tool.mob-disarmer.single-item", "{prefix} &cHold a single Mob Disarmer, not a stack."),
    TOOL_DISARMER_UPDATE_FAILED("tool.mob-disarmer.update-failed", "{prefix} &cThe Mob Disarmer could not be updated, so nothing was used.");

    private static final Map<String, Messages> BY_KEY;

    static {
        Map<String, Messages> map = new HashMap<>();
        for (Messages msg : values()) {
            map.put(msg.key, msg);
        }
        BY_KEY = Collections.unmodifiableMap(map);
    }

    private final String key;
    private final String defaultValue;

    Messages(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String getKey() {
        return key;
    }

    public String getDefault() {
        return defaultValue;
    }

    public static Messages fromKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }
}
