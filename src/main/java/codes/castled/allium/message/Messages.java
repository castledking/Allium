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
    TOOL_DISARMER_UPDATE_FAILED("tool.mob-disarmer.update-failed", "{prefix} &cThe Mob Disarmer could not be updated, so nothing was used."),
    TOOL_OBLITERATOR_OBLITERATED("tool.phantom-obliterator.obliterated", "{prefix} &5Obliterated &d{count} &5unnamed phantom(s). &7Charges: &a{charges}&7/{max}"),
    TOOL_OBLITERATOR_OBLITERATED_BYPASS("tool.phantom-obliterator.obliterated-bypass", "{prefix} &5Obliterated &d{count} &5unnamed phantom(s)."),
    TOOL_OBLITERATOR_NOTHING_IN_RANGE("tool.phantom-obliterator.nothing-in-range", "{prefix} &7No unnamed phantoms within &d{radius} &7blocks. Charges: &a{charges}&7/{max}"),
    TOOL_OBLITERATOR_PROTECTED("tool.phantom-obliterator.protected", "{prefix} &cThose phantoms are protected here, so nothing was spent."),
    TOOL_OBLITERATOR_LAST_CHARGE("tool.phantom-obliterator.last-charge", "{prefix} &cThat was the last charge. Fully recharged in &f{time}&c."),
    TOOL_OBLITERATOR_BROKE("tool.phantom-obliterator.broke", "{prefix} &cThe Phantom Obliterator broke while in use."),
    TOOL_OBLITERATOR_BROKEN("tool.phantom-obliterator.broken", "{prefix} &cThis Phantom Obliterator is broken."),
    TOOL_OBLITERATOR_RECHARGING("tool.phantom-obliterator.recharging", "{prefix} &cThe Phantom Obliterator is recharging. Ready in &f{time}&c."),
    TOOL_OBLITERATOR_SINGLE("tool.phantom-obliterator.single-item", "{prefix} &cHold a single Phantom Obliterator, not a stack."),
    TOOL_OBLITERATOR_UPDATE_FAILED("tool.phantom-obliterator.update-failed", "{prefix} &cThe Phantom Obliterator could not be updated, so nothing was used."),
    ITEM_GIVE_DELIVERED("item.give.delivered", "{prefix} &aGave &f{amount}x {item} &ato &f{player}&a."),
    ITEM_GIVE_DROPPED("item.give.dropped", "{prefix} &aGave &f{given}&a of &f{amount}x {item} &ato &f{player}&a; &f{dropped} &adid not fit and dropped at their feet."),
    ITEM_GIVE_DEGRADED("item.give.degraded", "{prefix} &eGave &f{amount}x {item} &eto &f{player}&e, but a head was given without its skin."),
    ITEM_GIVE_REFUSED_UNRESOLVED("item.give.refused-unresolved", "{prefix} &cA skin for {item} could not be resolved, so nothing was given to {player}."),
    ITEM_GIVE_PARTIAL("item.give.partial", "{prefix} &eGave &f{given}&e of &f{amount}x {item} &eto &f{player}&e; &f{leftover} &edid not fit and were not given."),
    ITEM_GIVE_REFUSED("item.give.refused", "{prefix} &c{player}'s inventory cannot hold &f{amount}x {item}&c, so nothing was given."),
    ITEM_GIVE_OFFLINE("item.give.offline", "{prefix} &c{player} left before the items were ready, so nothing was given."),
    ITEM_GIVE_CANCELLED("item.give.cancelled", "{prefix} &cAnother plugin cancelled giving {item} to {player}."),
    ITEM_GIVE_FAILED("item.give.failed", "{prefix} &cCould not give {item}: &f{reason}"),
    ITEM_GIVE_NO_TARGET("item.give.no-target", "{prefix} &c{reason}"),
    ITEM_GIVE_INVALID("item.give.invalid", "{prefix} &cInvalid item spec: &f{reason}"),
    ITEM_GIVE_UNRESOLVED("item.give.unresolved", "{prefix} &cCould not give {item} ({status}): &f{reason}"),
    ITEM_GIVE_PRIVILEGED("item.give.privileged", "{prefix} &cGiving {item} ({behavior}) needs &f{permission}&c."),
    ITEM_GIVE_PENDING("item.give.pending", "{prefix} &7Resolving skull profiles for {player}..."),
    ITEM_GIVE_WARNING("item.give.warning", "{prefix} &eWarning: {warning}"),
    ITEM_GIVE_RECEIVED("item.give.received", "{prefix} &aYou received &f{amount}x {item}&a."),

    // Private messages (S07)
    MSG_SENT("messaging.sent", "&7[&fme &7→ &f{recipient}&7] &f{message}"),
    MSG_RECEIVED("messaging.received", "&7[&f{sender} &7→ &fme&7] &f{message}"),
    MSG_CANNOT_RECEIVE("messaging.cannot-receive", "{prefix} &c{player} can't receive messages right now."),
    MSG_NO_REPLY_TARGET("messaging.no-reply-target", "{prefix} &cYou have nobody to reply to."),
    MSG_MUTED("messaging.muted", "{prefix} &cYou are muted."),
    MSG_SELF("messaging.self", "{prefix} &cYou can't message yourself."),
    MSG_CONSOLE_NAME("messaging.console-name", "Console"),
    IGNORE_ADDED("messaging.ignore.added", "{prefix} &aYou are now ignoring &f{player}&a's private messages."),
    IGNORE_REMOVED("messaging.ignore.removed", "{prefix} &aYou are no longer ignoring &f{player}&a."),
    IGNORE_ALREADY("messaging.ignore.already", "{prefix} &cYou are already ignoring &f{player}&c."),
    IGNORE_NOT_IGNORED("messaging.ignore.not-ignored", "{prefix} &cYou are not ignoring &f{player}&c."),
    IGNORE_SELF("messaging.ignore.self", "{prefix} &cYou can't ignore yourself."),
    IGNORE_FULL("messaging.ignore.full", "{prefix} &cYour ignore list is full."),
    IGNORE_FAILED("messaging.ignore.failed", "{prefix} &cYour ignore list could not be saved; the change was undone."),
    IGNORE_LIST("messaging.ignore.list", "{prefix} &7You are ignoring: &f{players}"),
    IGNORE_LIST_EMPTY("messaging.ignore.list-empty", "{prefix} &7You are not ignoring anyone."),
    MSGTOGGLE_ON("messaging.toggle.on", "{prefix} &aYou now accept private messages."),
    MSGTOGGLE_OFF("messaging.toggle.off", "{prefix} &cYou no longer accept private messages."),
    MSGTOGGLE_OTHER_ON("messaging.toggle.other-on", "{prefix} &f{player} &anow accepts private messages."),
    MSGTOGGLE_OTHER_OFF("messaging.toggle.other-off", "{prefix} &f{player} &cno longer accepts private messages."),
    MSGTOGGLE_DISABLED("messaging.toggle.disabled", "{prefix} &cMessage preferences are disabled on this server."),

    // -- Message sounds (/messagesounds, /msounds, /mfx) --
    SOUNDS_STATUS_OVERRIDE("messaging.sounds.status-override", "{prefix} &7Message sound volume: &f{volume} &7(your setting)."),
    SOUNDS_STATUS_DEFAULT("messaging.sounds.status-default", "{prefix} &7Message sound volume: &f{volume} &7(the server default)."),
    SOUNDS_STATUS_MUTED("messaging.sounds.status-muted", "{prefix} &7Message sounds are &cmuted&7. &f/mfx toggle &7turns them back on at &f{volume}&7."),
    SOUNDS_USAGE("messaging.sounds.usage", "{prefix} &7Use &f/mfx volume <0-1>&7, &f/mfx toggle &7or &f/mfx reset&7."),
    SOUNDS_VOLUME_SET("messaging.sounds.volume-set", "{prefix} &aMessage sound volume set to &f{volume}&a."),
    SOUNDS_VOLUME_INVALID("messaging.sounds.volume-invalid", "{prefix} &c'{input}' is not a volume from 0 to 1; nothing changed."),
    SOUNDS_MUTED("messaging.sounds.muted", "{prefix} &cMessage sounds muted. &7/mfx toggle turns them back on."),
    SOUNDS_RESTORED("messaging.sounds.restored", "{prefix} &aMessage sounds are back on at &f{volume}&a."),
    SOUNDS_FALLBACK("messaging.sounds.fallback", "{prefix} &aMessage sounds are back on at the server default, &f{volume}&a."),
    SOUNDS_FALLBACK_SILENT("messaging.sounds.fallback-silent", "{prefix} &cThe server default volume is 0, so message sounds stay muted. &7Use &f/mfx volume <0-1>&7."),
    SOUNDS_RESET("messaging.sounds.reset", "{prefix} &aYou now follow the server's message sound volume, &f{volume}&a."),
    SOUNDS_DISABLED("messaging.sounds.disabled", "{prefix} &cMessage sound preferences are disabled on this server."),

    // -- Legacy imports (/allium import) --
    IMPORT_LANG_NO_SOURCE("core.import.lang-no-source", "{prefix} &cNo pre-recode lang file at &f{file}&c."),
    IMPORT_LANG_REPORT("core.import.lang-report", "{prefix} &7{mode}: &f{write} &7to write, &f{same} &7already imported, &f{kept} &7kept (customized), &f{unmapped} &7not portable yet. Details are in the console."),
    IMPORT_LANG_APPLIED("core.import.lang-applied", "{prefix} &aImported &f{write} &amessage(s) into &f{file}&a; the old file is backed up as &f{backup}&a."),
    IMPORT_LANG_NOTHING("core.import.lang-nothing", "{prefix} &aNothing to import; &f{file} &ais up to date."),
    IMPORT_LANG_FAILED("core.import.lang-failed", "{prefix} &cThe lang import failed: &f{reason}"),

    // Preferences (shared by messages, payments and teleport requests)
    PREFERENCES_UNAVAILABLE("preferences.unavailable", "{prefix} &c{player}'s settings are still loading. Try again shortly.");

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
