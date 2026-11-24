package codes.castled.allium.importer.essentials;

import codes.castled.allium.pack.Json;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure read-only configuration and permission mapping report comparing EssentialsX
 * {@code config.yml} against Allium's authoritative {@code config.yml}.
 *
 * <p>Classifies every inspected source setting and permission node as {@code MAPPED},
 * {@code MANUAL}, {@code UNSUPPORTED}, or {@code CONFLICT} without ever overwriting operator-set
 * Allium settings.
 */
public final class EssentialsConfigReport {

    public enum Outcome {
        MAPPED,
        MANUAL,
        UNSUPPORTED,
        CONFLICT
    }

    public record ConfigEntry(
            String sourceKey,
            String sourceValue,
            @Nullable String targetKey,
            @Nullable String currentValue,
            @Nullable String proposedValue,
            Outcome outcome,
            String detail
    ) {}

    public record PermissionEntry(
            String essentialsNode,
            @Nullable String alliumNode,
            Outcome outcome,
            String detail
    ) {}

    private static final Pattern UNSUPPORTED_CHAT_TOKEN = Pattern.compile(
            "\\{(?:TEAMNAME|TEAM|TEAMPREFIX|TEAMSUFFIX|FACTION|SHORTWORLDNAME|WORLDNAME|TOWN|NATION)[^}]*}",
            Pattern.CASE_INSENSITIVE
    );

    private final List<ConfigEntry> configEntries;
    private final List<PermissionEntry> permissionEntries;

    private EssentialsConfigReport(List<ConfigEntry> configEntries, List<PermissionEntry> permissionEntries) {
        this.configEntries = List.copyOf(configEntries);
        this.permissionEntries = List.copyOf(permissionEntries);
    }

    public List<ConfigEntry> configEntries() { return configEntries; }
    public List<PermissionEntry> permissionEntries() { return permissionEntries; }

    public Map<Outcome, Long> configCounts() {
        Map<Outcome, Long> out = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) out.put(o, 0L);
        for (ConfigEntry e : configEntries) out.merge(e.outcome(), 1L, Long::sum);
        return out;
    }

    public Map<Outcome, Long> permissionCounts() {
        Map<Outcome, Long> out = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) out.put(o, 0L);
        for (PermissionEntry e : permissionEntries) out.merge(e.outcome(), 1L, Long::sum);
        return out;
    }

    public static EssentialsConfigReport analyze(Path essentialsDir, ConfigurationSection alliumConfig, EssentialsImportPlan plan) {
        YamlConfiguration ess = new YamlConfiguration();
        Path configFile = essentialsDir == null ? null : essentialsDir.resolve("config.yml");
        if (configFile != null && Files.isRegularFile(configFile)) {
            try {
                ess.loadFromString(Files.readString(configFile, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Malformed Essentials config.yml is already captured in EssentialsSnapshot.notes().
            }
        }
        return analyze(ess, alliumConfig == null ? new YamlConfiguration() : alliumConfig, plan);
    }

    public static EssentialsConfigReport analyze(ConfigurationSection ess, ConfigurationSection allium, EssentialsImportPlan plan) {
        List<ConfigEntry> configs = new ArrayList<>();
        List<PermissionEntry> perms = new ArrayList<>();

        analyzeTeleport(ess, allium, configs);
        analyzeHomesAndWarps(ess, allium, plan, configs, perms);
        analyzeEconomy(ess, allium, configs);
        analyzeOnboarding(ess, allium, plan, configs);
        analyzeSignsAndMobs(ess, allium, plan, configs, perms);
        analyzeChatAndJoin(ess, allium, configs);
        analyzeGeneralPermissions(plan, perms);

        return new EssentialsConfigReport(configs, perms);
    }

    private static void analyzeTeleport(ConfigurationSection ess, ConfigurationSection allium, List<ConfigEntry> out) {
        if (ess.contains("teleport-delay")) {
            int val = ess.getInt("teleport-delay", 0);
            int cur = allium.getInt("teleport.warmup-seconds", 3);
            compareNumeric("teleport-delay", Integer.toString(val), "teleport.warmup-seconds",
                    Integer.toString(cur), Integer.toString(val), val == cur,
                    "Default warmup applies to all teleports; per-type overrides live under teleport.warmups.*", out);
        }
        if (ess.contains("teleport-cooldown")) {
            int val = ess.getInt("teleport-cooldown", 0);
            int curHome = allium.getInt("teleport.cooldowns.home", 0);
            int curWarp = allium.getInt("teleport.cooldowns.warp", 0);
            int curTpa = allium.getInt("teleport.cooldowns.tpa", 0);
            boolean matches = (val == curHome && val == curWarp && val == curTpa);
            out.add(new ConfigEntry("teleport-cooldown", Integer.toString(val),
                    "teleport.cooldowns.{home,warp,tpa,tpahere,back,spawn,tp,tphere}",
                    "home=" + curHome + ",warp=" + curWarp + ",tpa=" + curTpa,
                    Integer.toString(val),
                    matches ? Outcome.MAPPED : Outcome.CONFLICT,
                    matches ? "Matches Allium travel cooldowns"
                            : "Allium uses per-command cooldowns under teleport.cooldowns.*; keeping existing Allium values"));
        }
        if (ess.contains("tpa-accept-cancellation")) {
            int val = ess.getInt("tpa-accept-cancellation", 120);
            int cur = allium.getInt("teleport.request-expiry-seconds", 60);
            compareNumeric("tpa-accept-cancellation", Integer.toString(val), "teleport.request-expiry-seconds",
                    Integer.toString(cur), Integer.toString(val), val == cur,
                    "TPA/TPAHere request expiry in seconds", out);
        }
        if (ess.contains("teleport-safety")) {
            boolean val = ess.getBoolean("teleport-safety", true);
            boolean cur = allium.getBoolean("teleport.safety.enabled", true);
            compareBoolean("teleport-safety", val, "teleport.safety.enabled", cur,
                    "Safe destination adjustment before teleport", out);
        }
        for (String manualKey : List.of("force-disable-teleport-safety", "teleport-to-center",
                "teleport-passenger-dismount", "teleport-invulnerability")) {
            if (ess.contains(manualKey)) {
                out.add(new ConfigEntry(manualKey, String.valueOf(ess.get(manualKey)),
                        "teleport.safety.*", null, null, Outcome.MANUAL,
                        "Review teleport safety, block centering, passenger handling, and arrival grace manually"));
            }
        }
    }

    private static void analyzeHomesAndWarps(ConfigurationSection ess, ConfigurationSection allium,
                                             EssentialsImportPlan plan, List<ConfigEntry> configs,
                                             List<PermissionEntry> perms) {
        ConfigurationSection homes = ess.getConfigurationSection("sethome-multiple");
        if (homes != null) {
            for (String tier : homes.getKeys(false)) {
                int limit = homes.getInt(tier, -1);
                if (limit < 0) continue;
                if ("default".equalsIgnoreCase(tier)) {
                    int cur = allium.getInt("homes.default-limit", 1);
                    compareNumeric("sethome-multiple.default", Integer.toString(limit), "homes.default-limit",
                            Integer.toString(cur), Integer.toString(limit), limit == cur,
                            "Baseline home limit without multiple-home permission", configs);
                } else {
                    String alliumPerm = "allium.sethome.multiple." + limit;
                    configs.add(new ConfigEntry("sethome-multiple." + tier, Integer.toString(limit),
                            "permission:" + alliumPerm, null, alliumPerm, Outcome.MANUAL,
                            "Allium resolves home counts numerically from permission nodes; grant " + alliumPerm
                                    + " to ranks that held essentials.sethome.multiple." + tier));
                    perms.add(new PermissionEntry("essentials.sethome.multiple." + tier, alliumPerm, Outcome.MAPPED,
                            "Home tier '" + tier + "' (" + limit + " homes) maps to numeric Allium permission"));
                }
            }
        }
        if (ess.contains("respawn-at-home")) {
            boolean val = ess.getBoolean("respawn-at-home", false);
            boolean cur = allium.getBoolean("teleport.respawn-at-home", true);
            compareBoolean("respawn-at-home", val, "teleport.respawn-at-home", cur,
                    "Respawn at home named 'home' before falling back to spawn", configs);
        }
        if (ess.contains("compass-towards-home-perm")) {
            boolean val = ess.getBoolean("compass-towards-home-perm", false);
            boolean cur = allium.getBoolean("homes.compass-towards-home-perm", false);
            compareBoolean("compass-towards-home-perm", val, "homes.compass-towards-home-perm", cur,
                    "Compass points toward first home for holders of allium.home.compass", configs);
        }
        if (ess.contains("world-home-permissions")) {
            configs.add(new ConfigEntry("world-home-permissions", String.valueOf(ess.getBoolean("world-home-permissions")),
                    null, null, null, Outcome.MANUAL,
                    "Review cross-world home permissions against Allium's world/region access policy"));
        }
        if (ess.contains("spawn-if-no-home")) {
            configs.add(new ConfigEntry("spawn-if-no-home", String.valueOf(ess.getBoolean("spawn-if-no-home")),
                    "teleport.respawn-at-home", String.valueOf(allium.getBoolean("teleport.respawn-at-home", true)), null,
                    Outcome.MANUAL,
                    "Allium /home tries bed when no home is set; spawn fallback on death uses teleport.respawn-at-home"));
        }
        if (ess.contains("per-warp-permission")) {
            boolean perWarp = ess.getBoolean("per-warp-permission", false);
            if (perWarp) {
                configs.add(new ConfigEntry("per-warp-permission", "true", "permission:allium.warps.<name>",
                        "always enforced", "allium.warps.<name>", Outcome.MAPPED,
                        "Allium enforces allium.warps.<name> per warp; grant matching nodes so private warps stay private"));
            } else {
                configs.add(new ConfigEntry("per-warp-permission", "false", "permission:allium.warps.*",
                        "per-warp enforced", "allium.warps.*", Outcome.MANUAL,
                        "Allium requires allium.warps.<name> (or allium.warps.*) for /warp; grant allium.warps.* for public warps"));
            }
        }
        if (plan != null) {
            Set<String> warps = new LinkedHashSet<>();
            for (var r : plan.records()) {
                if (r.domain() == EssentialsImportPlan.Domain.WARP && r.name() != null
                        && (r.status() == EssentialsImportPlan.Status.IMPORT
                        || r.status() == EssentialsImportPlan.Status.PRESENT
                        || r.status() == EssentialsImportPlan.Status.REPLAYED)) {
                    warps.add(r.name());
                }
            }
            for (String warp : warps) {
                perms.add(new PermissionEntry("essentials.warps." + warp, "allium.warps." + warp, Outcome.MAPPED,
                        "Verify rank grants for warp '" + warp + "' so pre-existing private warps stay private"));
            }
        }
    }

    private static void analyzeEconomy(ConfigurationSection ess, ConfigurationSection allium, List<ConfigEntry> out) {
        compareMoney(ess, "starting-balance", allium, "economy.starting-balance", "100.00",
                "Initial balance for newly opened accounts", out);
        compareMoney(ess, "min-money", allium, "economy.min-money", "0.00",
                "Minimum account balance bound", out);
        compareMoney(ess, "max-money", allium, "economy.max-money", "10000000000000.00",
                "Maximum account balance bound", out);
        compareMoney(ess, "minimum-pay-amount", allium, "economy.minimum-pay-amount", "0.01",
                "Minimum /pay transfer amount", out);
        if (ess.contains("currency-symbol")) {
            String val = ess.getString("currency-symbol", "$");
            String cur = allium.getString("economy.currency-symbol", "$");
            compareString("currency-symbol", val, "economy.currency-symbol", cur,
                    "Display currency symbol (kept separate from money bounds)", out);
        }
        if (ess.contains("currency-symbol-suffix")) {
            compareBoolean("currency-symbol-suffix", ess.getBoolean("currency-symbol-suffix", false),
                    "economy.currency-symbol-suffix", allium.getBoolean("economy.currency-symbol-suffix", false),
                    "Whether currency symbol renders after the amount", out);
        }
        if (ess.contains("pay-excludes-ignore-list")) {
            compareBoolean("pay-excludes-ignore-list", ess.getBoolean("pay-excludes-ignore-list", false),
                    "economy.pay-excludes-ignore-list", allium.getBoolean("economy.pay-excludes-ignore-list", true),
                    "Block /pay when recipient ignores sender", out);
        }
        ConfigurationSection cmdCosts = ess.getConfigurationSection("command-costs");
        if (cmdCosts != null && !cmdCosts.getKeys(false).isEmpty()) {
            out.add(new ConfigEntry("command-costs", cmdCosts.getKeys(false).size() + " command(s)",
                    null, null, null, Outcome.UNSUPPORTED,
                    "General per-command costs are not auto-imported (Allium supports paid kits and paid signs)"));
        }
        ConfigurationSection cmdCooldowns = ess.getConfigurationSection("command-cooldowns");
        if (cmdCooldowns != null && !cmdCooldowns.getKeys(false).isEmpty()) {
            out.add(new ConfigEntry("command-cooldowns", cmdCooldowns.getKeys(false).size() + " rule(s)",
                    null, null, null, Outcome.UNSUPPORTED,
                    "General per-command cooldowns are not auto-imported (Allium has teleport.cooldowns.* and report cooldowns)"));
        }
    }

    private static void analyzeOnboarding(ConfigurationSection ess, ConfigurationSection allium,
                                          EssentialsImportPlan plan, List<ConfigEntry> out) {
        ConfigurationSection newbies = ess.getConfigurationSection("newbies");
        if (newbies != null) {
            String kit = newbies.getString("kit", "");
            if (kit != null && !kit.isBlank() && !"none".equalsIgnoreCase(kit.trim())) {
                String normKit = kit.trim().toLowerCase(Locale.ROOT);
                String cur = allium.getString("kits.first-join-kit", "");
                boolean kitPlanned = plan != null && plan.records().stream().anyMatch(r ->
                        r.domain() == EssentialsImportPlan.Domain.KIT && normKit.equals(r.key())
                                && r.status() != EssentialsImportPlan.Status.BLOCKED
                                && r.status() != EssentialsImportPlan.Status.SKIPPED);
                if (cur.equalsIgnoreCase(normKit)) {
                    out.add(new ConfigEntry("newbies.kit", normKit, "kits.first-join-kit", cur, normKit,
                            Outcome.MAPPED, "Matches Allium first-join starter kit"));
                } else if (!cur.isBlank()) {
                    out.add(new ConfigEntry("newbies.kit", normKit, "kits.first-join-kit", cur, normKit,
                            Outcome.CONFLICT, "Allium already sets kits.first-join-kit='" + cur + "'; keeping Allium policy"));
                } else {
                    out.add(new ConfigEntry("newbies.kit", normKit, "kits.first-join-kit", "", normKit,
                            kitPlanned ? Outcome.MAPPED : Outcome.MANUAL,
                            kitPlanned ? "Ready to set kits.first-join-kit: '" + normKit + "' after kit import"
                                    : "Kit '" + normKit + "' was not found in Essentials kits; verify plugins/Allium/kits/"
                                    + normKit + ".yml before enabling kits.first-join-kit"));
                }
            }
            String spawnpoint = newbies.getString("spawnpoint", "");
            if (spawnpoint != null && !spawnpoint.isBlank() && !"none".equalsIgnoreCase(spawnpoint.trim())) {
                boolean cur = allium.getBoolean("teleport.first-join-spawn", false);
                out.add(new ConfigEntry("newbies.spawnpoint", spawnpoint.trim(), "teleport.first-join-spawn",
                        String.valueOf(cur), "true",
                        cur ? Outcome.MAPPED : Outcome.MANUAL,
                        "Allium onboarding places first-join players at group/default spawn when teleport.first-join-spawn: true"));
            }
            String announce = newbies.getString("announce-format", "");
            if (announce != null && !announce.isBlank() && !"none".equalsIgnoreCase(announce.trim())) {
                String translated = translateJoinTokens(announce);
                boolean curEnabled = allium.getBoolean("join-and-quit-messages.first-join.enabled", false);
                String curFormat = allium.getString("join-and-quit-messages.first-join.format", "");
                out.add(new ConfigEntry("newbies.announce-format", announce,
                        "join-and-quit-messages.first-join.{enabled,format}",
                        "enabled=" + curEnabled + ", format=" + curFormat, translated,
                        curEnabled && translated.equals(curFormat) ? Outcome.MAPPED : Outcome.MANUAL,
                        "Enable join-and-quit-messages.first-join.enabled: true and review translated format"));
            }
        }
        if (ess.contains("spawn-on-join")) {
            Object raw = ess.get("spawn-on-join");
            boolean essOn = raw instanceof Boolean b ? b : (raw instanceof List<?> l && !l.isEmpty());
            boolean cur = allium.getBoolean("teleport.spawn-on-join", false);
            if (raw instanceof Boolean) {
                compareBoolean("spawn-on-join", essOn, "teleport.spawn-on-join", cur,
                        "Teleport players to spawn on every join", out);
            } else {
                out.add(new ConfigEntry("spawn-on-join", String.valueOf(raw), "teleport.spawn-on-join",
                        String.valueOf(cur), String.valueOf(essOn), Outcome.MANUAL,
                        "Essentials per-group spawn-on-join list requires manual review; Allium supports boolean teleport.spawn-on-join"));
            }
        }
    }

    private static void analyzeSignsAndMobs(ConfigurationSection ess, ConfigurationSection allium,
                                            EssentialsImportPlan plan, List<ConfigEntry> configs,
                                            List<PermissionEntry> perms) {
        if (plan != null) {
            if (!plan.supportedSigns().isEmpty()) {
                configs.add(new ConfigEntry("enabledSigns (supported)", String.join(", ", plan.supportedSigns()),
                        "enabledSigns", String.join(", ", allium.getStringList("enabledSigns")),
                        String.join(", ", plan.supportedSigns()), Outcome.MAPPED,
                        "Supported Essentials signs are merged into config.yml enabledSigns automatically on apply"));
                for (String sign : plan.supportedSigns()) {
                    perms.add(new PermissionEntry("essentials.signs.use." + sign, "allium.signs.use." + sign,
                            Outcome.MAPPED, "Sign use permission for [" + sign + "]"));
                    perms.add(new PermissionEntry("essentials.signs.create." + sign, "allium.signs.create." + sign,
                            Outcome.MAPPED, "Sign creation permission for [" + sign + "]"));
                }
            }
            for (String unsupported : plan.unsupportedSigns()) {
                configs.add(new ConfigEntry("enabledSigns." + unsupported, unsupported,
                        null, null, null, Outcome.UNSUPPORTED,
                        "Essentials sign type '" + unsupported + "' is not an Allium sign type (skipped)"));
                perms.add(new PermissionEntry("essentials.signs.use." + unsupported, null,
                        Outcome.UNSUPPORTED, "Sign type '" + unsupported + "' has no Allium equivalent"));
            }
        }
        if (ess.contains("sign-use-per-second")) {
            int val = ess.getInt("sign-use-per-second", 4);
            int cur = allium.getInt("sign-use-per-second", 4);
            compareNumeric("sign-use-per-second", Integer.toString(val), "sign-use-per-second",
                    Integer.toString(cur), Integer.toString(val), val == cur,
                    "Shared utility-sign click rate limit per second", configs);
        }
        if (ess.contains("spawnmob-limit")) {
            int val = ess.getInt("spawnmob-limit", 10);
            int cur = allium.getInt("spawnmob.limit", 10);
            compareNumeric("spawnmob-limit", Integer.toString(val), "spawnmob.limit",
                    Integer.toString(cur), Integer.toString(val), val == cur,
                    "Maximum entities spawned per /spawnmob invocation", configs);
        }
    }

    private static void analyzeChatAndJoin(ConfigurationSection ess, ConfigurationSection allium, List<ConfigEntry> out) {
        if (ess.contains("max-nick-length")) {
            int val = ess.getInt("max-nick-length", 15);
            int cur = allium.getInt("nickname.max-length", 32);
            compareNumeric("max-nick-length", Integer.toString(val), "nickname.max-length",
                    Integer.toString(cur), Integer.toString(val), val == cur,
                    "Maximum visible nickname length", out);
        }
        if (ess.contains("nickname-prefix")) {
            String val = ess.getString("nickname-prefix", "");
            if (val != null && !val.isEmpty()) {
                out.add(new ConfigEntry("nickname-prefix", val, null, null, null, Outcome.MANUAL,
                        "Allium displays stored nicknames directly without auto-prepending a separate prefix character"));
            }
        }
        ConfigurationSection chat = ess.getConfigurationSection("chat");
        if (chat != null) {
            if (chat.contains("radius")) {
                double radius = chat.getDouble("radius", 0.0);
                double cur = allium.getDouble("chat.channels.local-channel.radius", 100.0);
                if (radius > 0.0) {
                    compareNumeric("chat.radius", Double.toString(radius), "chat.channels.local-channel.radius",
                            Double.toString(cur), Double.toString(radius), Double.compare(radius, cur) == 0,
                            "Local chat radius in blocks", out);
                } else {
                    out.add(new ConfigEntry("chat.radius", "0", "chat.channels.enabled",
                            String.valueOf(allium.getBoolean("chat.channels.enabled", true)), null, Outcome.MANUAL,
                            "Essentials radius 0 disables local chat; Allium keeps global as default write channel"));
                }
            }
            if (chat.contains("format")) {
                String raw = chat.getString("format", "");
                if (raw != null && !raw.isBlank()) {
                    analyzeChatFormat("chat.format", raw, "chat-format.default",
                            allium.getString("chat-format.default", "<prefix><display_name>&f: <message>"), out);
                }
            }
            ConfigurationSection groupFormats = chat.getConfigurationSection("group-formats");
            if (groupFormats != null) {
                for (String group : groupFormats.getKeys(false)) {
                    String raw = groupFormats.getString(group, "");
                    if (raw != null && !raw.isBlank()) {
                        analyzeChatFormat("chat.group-formats." + group, raw, "chat-format.groups." + group,
                                allium.getString("chat-format.groups." + group, ""), out);
                    }
                }
            }
        }
        for (String key : List.of("custom-join-message", "custom-quit-message")) {
            String raw = ess.getString(key, "");
            if (raw != null && !raw.isBlank() && !"none".equalsIgnoreCase(raw.trim())) {
                String target = key.contains("join") ? "join-and-quit-messages.join.format" : "join-and-quit-messages.quit.format";
                String translated = translateJoinTokens(raw);
                String cur = allium.getString(target, "");
                out.add(new ConfigEntry(key, raw, target, cur, translated,
                        translated.equals(cur) ? Outcome.MAPPED : Outcome.MANUAL,
                        "Review translated join/quit template before replacing Allium's existing format"));
            }
        }
    }

    private static void analyzeChatFormat(String sourceKey, String raw, String targetKey, String current, List<ConfigEntry> out) {
        String translated = raw
                .replace("{DISPLAYNAME}", "<display_name>")
                .replace("{USERNAME}", "<player>")
                .replace("{MESSAGE}", "<message>")
                .replace("{PREFIX}", "<prefix>")
                .replace("{SUFFIX}", "<suffix>");
        Matcher bad = UNSUPPORTED_CHAT_TOKEN.matcher(translated);
        List<String> unsupportedTokens = new ArrayList<>();
        while (bad.find()) unsupportedTokens.add(bad.group());
        if (!unsupportedTokens.isEmpty()) {
            out.add(new ConfigEntry(sourceKey, raw, targetKey, current, translated, Outcome.UNSUPPORTED,
                    "Contains unsupported scoreboard/world/faction tokens " + unsupportedTokens + "; rewrite using PlaceholderAPI or Allium placeholders"));
        } else {
            out.add(new ConfigEntry(sourceKey, raw, targetKey, current, translated,
                    translated.equals(current) ? Outcome.MAPPED : Outcome.MANUAL,
                    "Preview translated chat format; Allium preserves existing chat-format.* unless manually updated"));
        }
    }

    private static void analyzeGeneralPermissions(EssentialsImportPlan plan, List<PermissionEntry> perms) {
        Map<String, String> core = new LinkedHashMap<>();
        core.put("essentials.back", "allium.back");
        core.put("essentials.back.ondeath", "allium.back.ondeath");
        core.put("essentials.home", "allium.home");
        core.put("essentials.sethome", "allium.sethome");
        core.put("essentials.sethome.multiple.unlimited", "allium.sethome.unlimited");
        core.put("essentials.warp", "allium.warp");
        core.put("essentials.tpa", "allium.tpa");
        core.put("essentials.tpahere", "allium.tpahere");
        core.put("essentials.spawn", "allium.spawn");
        core.put("essentials.msg", "allium.msg");
        core.put("essentials.mail", "allium.mail");
        core.put("essentials.nick", "allium.nick");
        core.put("essentials.nick.color", "allium.nick.color");
        core.put("essentials.chat.color", "allium.chat.color");
        core.put("essentials.chat.url", "allium.chat.url");
        core.put("essentials.chat.question", "allium.chat.question");
        core.put("essentials.chat.shout", "allium.chat.shout");
        core.put("essentials.socialspy", "allium.socialspy");
        core.put("essentials.vanish", "allium.vanish");
        core.put("essentials.mute", "allium.mute");
        core.put("essentials.jail", "allium.jail");
        core.put("essentials.worth", "allium.worth");
        core.put("essentials.sell", "allium.sell");
        core.put("essentials.sell.hand", "allium.sell.hand");
        core.put("essentials.sell.bulk", "allium.sell.bulk");
        core.put("essentials.sell.multiplier.<tier>", "allium.sell.multiplier.<tier>");
        core.put("essentials.kit", "allium.kit");
        core.put("essentials.kit.exemptdelay", "allium.kit.bypass.cooldown");

        for (Map.Entry<String, String> e : core.entrySet()) {
            perms.add(new PermissionEntry(e.getKey(), e.getValue(), Outcome.MAPPED, "Standard EssentialsX -> Allium node"));
        }

        if (plan != null) {
            Set<String> kits = new LinkedHashSet<>();
            for (var r : plan.records()) {
                if (r.domain() == EssentialsImportPlan.Domain.KIT
                        && (r.status() == EssentialsImportPlan.Status.IMPORT
                        || r.status() == EssentialsImportPlan.Status.PRESENT
                        || r.status() == EssentialsImportPlan.Status.REPLAYED)) {
                    kits.add(r.key());
                }
            }
            for (String kit : kits) {
                perms.add(new PermissionEntry("essentials.kits." + kit, "allium.kits." + kit, Outcome.MAPPED,
                        "Per-kit claim permission for imported kit '" + kit + "'"));
            }
        }
    }

    private static void compareMoney(ConfigurationSection ess, String essKey, ConfigurationSection allium,
                                     String alliumKey, String defaultVal, String detail, List<ConfigEntry> out) {
        if (!ess.contains(essKey)) return;
        String rawEss = String.valueOf(ess.get(essKey)).trim();
        String rawCur = String.valueOf(allium.get(alliumKey, defaultVal)).trim();
        boolean equal;
        try {
            equal = new BigDecimal(rawEss).compareTo(new BigDecimal(rawCur)) == 0;
        } catch (NumberFormatException bad) {
            out.add(new ConfigEntry(essKey, rawEss, alliumKey, rawCur, null, Outcome.MANUAL,
                    "Malformed numeric money bound in Essentials config.yml"));
            return;
        }
        compareNumeric(essKey, rawEss, alliumKey, rawCur, rawEss, equal, detail, out);
    }

    private static void compareNumeric(String sourceKey, String sourceVal, String targetKey, String curVal,
                                       String proposedVal, boolean equal, String detail, List<ConfigEntry> out) {
        out.add(new ConfigEntry(sourceKey, sourceVal, targetKey, curVal, proposedVal,
                equal ? Outcome.MAPPED : Outcome.CONFLICT,
                equal ? detail + " (matches current Allium setting)"
                        : detail + " (Allium keeps current value '" + curVal + "' over Essentials '" + sourceVal + "')"));
    }

    private static void compareBoolean(String sourceKey, boolean sourceVal, String targetKey, boolean curVal,
                                       String detail, List<ConfigEntry> out) {
        boolean equal = (sourceVal == curVal);
        out.add(new ConfigEntry(sourceKey, Boolean.toString(sourceVal), targetKey,
                Boolean.toString(curVal), Boolean.toString(sourceVal),
                equal ? Outcome.MAPPED : Outcome.CONFLICT,
                equal ? detail + " (matches current Allium setting)"
                        : detail + " (Allium keeps current value '" + curVal + "' over Essentials '" + sourceVal + "')"));
    }

    private static void compareString(String sourceKey, String sourceVal, String targetKey, String curVal,
                                      String detail, List<ConfigEntry> out) {
        boolean equal = sourceVal.equals(curVal);
        out.add(new ConfigEntry(sourceKey, sourceVal, targetKey, curVal, sourceVal,
                equal ? Outcome.MAPPED : Outcome.CONFLICT,
                equal ? detail + " (matches current Allium setting)"
                        : detail + " (Allium keeps current value '" + curVal + "' over Essentials '" + sourceVal + "')"));
    }

    private static String translateJoinTokens(String raw) {
        return raw.replace("{PLAYER}", "%name%")
                .replace("{USERNAME}", "%name%")
                .replace("{DISPLAYNAME}", "%name%");
    }

    public String summaryLine() {
        var cc = configCounts();
        return "Config mapping: mapped=" + cc.get(Outcome.MAPPED)
                + " manual=" + cc.get(Outcome.MANUAL)
                + " conflict=" + cc.get(Outcome.CONFLICT)
                + " unsupported=" + cc.get(Outcome.UNSUPPORTED)
                + " | Permissions: " + permissionEntries.size() + " (see config-mapping.txt)";
    }

    public String renderText(String runId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EssentialsX Config & Permission Mapping Report — ").append(runId).append('\n');
        sb.append("# Existing Allium settings remain authoritative on CONFLICT; nothing here is auto-overwritten.\n");
        var cc = configCounts();
        var pc = permissionCounts();
        sb.append("# Config outcomes: mapped=").append(cc.get(Outcome.MAPPED))
                .append(" manual=").append(cc.get(Outcome.MANUAL))
                .append(" conflict=").append(cc.get(Outcome.CONFLICT))
                .append(" unsupported=").append(cc.get(Outcome.UNSUPPORTED)).append('\n');
        sb.append("# Permission outcomes: mapped=").append(pc.get(Outcome.MAPPED))
                .append(" manual=").append(pc.get(Outcome.MANUAL))
                .append(" conflict=").append(pc.get(Outcome.CONFLICT))
                .append(" unsupported=").append(pc.get(Outcome.UNSUPPORTED)).append("\n\n");

        sb.append("== Configuration Keys ==\n");
        for (ConfigEntry e : configEntries) {
            sb.append(String.format(Locale.ROOT, "[%-11s] %s = %s", e.outcome().name(), e.sourceKey(), e.sourceValue()));
            if (e.targetKey() != null) sb.append(" -> ").append(e.targetKey());
            if (e.currentValue() != null) sb.append(" (allium=").append(e.currentValue()).append(')');
            sb.append(" | ").append(e.detail()).append('\n');
        }

        sb.append("\n== Permission Nodes ==\n");
        for (PermissionEntry p : permissionEntries) {
            sb.append(String.format(Locale.ROOT, "[%-11s] %s -> %s | %s%n",
                    p.outcome().name(), p.essentialsNode(),
                    p.alliumNode() == null ? "(none)" : p.alliumNode(), p.detail()));
        }
        return sb.toString();
    }

    public String renderJsonl() {
        StringBuilder sb = new StringBuilder();
        for (ConfigEntry e : configEntries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "config");
            row.put("outcome", e.outcome().name());
            row.put("sourceKey", e.sourceKey());
            row.put("sourceValue", e.sourceValue());
            row.put("targetKey", e.targetKey());
            row.put("currentValue", e.currentValue());
            row.put("proposedValue", e.proposedValue());
            row.put("detail", e.detail());
            sb.append(Json.write(row)).append('\n');
        }
        for (PermissionEntry p : permissionEntries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "permission");
            row.put("outcome", p.outcome().name());
            row.put("essentialsNode", p.essentialsNode());
            row.put("alliumNode", p.alliumNode());
            row.put("detail", p.detail());
            sb.append(Json.write(row)).append('\n');
        }
        return sb.toString();
    }
}
