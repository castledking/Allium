package codes.castled.allium.portable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Expands the server's configured spectate commands for either Bukkit runtime. */
public final class SpectateCommands {
    private static final List<String> DEFAULTS = Arrays.asList("tp {player} {target}", "gmsp {player}");
    private static final Set<String> SELF_GAMEMODE = new HashSet<>(Arrays.asList(
            "gmsp", "gmc", "gms", "gma", "spectator", "creative", "survival", "adventure",
            "spectatormode", "creativemode", "survivalmode", "adventuremode"));

    public static final class Invocation {
        public final String command;
        public final boolean asPlayer;

        private Invocation(String command, boolean asPlayer) {
            this.command = command;
            this.asPlayer = asPlayer;
        }
    }

    private SpectateCommands() {}

    public static List<Invocation> expand(List<String> configured, String actor, String target) {
        List<Invocation> result = new ArrayList<>();
        for (String raw : configured.isEmpty() ? DEFAULTS : configured) {
            if (raw == null || raw.codePoints().allMatch(Character::isWhitespace)) continue;
            String template = raw.trim();
            boolean asPlayer = template.regionMatches(true, 0, "player:", 0, 7);
            boolean explicitConsole = template.regionMatches(true, 0, "console:", 0, 8);
            if (asPlayer) template = template.substring(7).trim();
            else if (explicitConsole) template = template.substring(8).trim();
            if (template.startsWith("/")) template = template.substring(1).trim();
            if (template.isEmpty()) continue;

            String command = template.replace("{player}", actor).replace("{target}", target);
            if (!asPlayer && !explicitConsole && !template.contains("{player}")) {
                command = expandSelfShorthand(command, actor);
            }
            result.add(new Invocation(command, asPlayer));
        }
        return result;
    }

    private static String expandSelfShorthand(String command, String actor) {
        String[] parts = command.split("\\s+");
        if (parts.length == 0) return command;
        String head = parts[0].toLowerCase(Locale.ROOT);
        head = head.substring(head.lastIndexOf(':') + 1);
        if (parts.length == 1 && SELF_GAMEMODE.contains(head)) return command + " " + actor;
        if (parts.length == 2 && ("tp".equals(head) || "tpo".equals(head))) {
            return parts[0] + " " + actor + " " + parts[1];
        }
        return command;
    }
}
