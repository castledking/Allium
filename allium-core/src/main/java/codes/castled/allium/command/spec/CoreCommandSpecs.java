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
            "/allium <reload|version>",
            "/allium <reload|version>",
            null,
            null,
            List.of(),
            List.of(
                    new CommandNodeSpec("reload", List.of(), "allium.reload", "/allium reload", List.of(), List.of()),
                    new CommandNodeSpec("version", List.of(), null, "/allium version", List.of(), List.of())
            ),
            CommandMessageKeys.ofOutcomes(Map.of(
                    "reloaded", "core.reload-success",
                    "reload-failed", "core.reload-failed",
                    "version", "core.version"
            ))
    );

    public static final List<CommandSpec> CORE_SPECS = List.of(ALLIUM);

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
