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
