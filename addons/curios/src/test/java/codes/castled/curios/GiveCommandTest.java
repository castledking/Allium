package codes.castled.curios;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class GiveCommandTest {
    private static GiveCommand give;
    private static final Predicate<String> ONLINE = Set.of("ggpots", "ze_flash")::contains;
    private static final List<String> PLAYERS = List.of("ggpots", "ze_flash");

    @BeforeAll
    static void load() throws Exception {
        try (var in = GiveCommandTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            give = new GiveCommand(new Catalog(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8))));
        }
    }

    private static GiveCommand.Request ok(GiveCommand.Scope scope, String... args) {
        GiveCommand.Result result = give.parse("cards", args, scope, ONLINE);
        assertNull(result.error(), () -> "unexpected error: " + result.error());
        return result.request();
    }

    private static String error(GiveCommand.Scope scope, String... args) {
        GiveCommand.Result result = give.parse("cards", args, scope, ONLINE);
        assertNull(result.request());
        return result.error();
    }

    @Test
    void scopeFollowsTheLabelIncludingNamespacedOnes() {
        assertEquals(GiveCommand.Scope.CARDS, GiveCommand.Scope.of("cards"));
        assertEquals(GiveCommand.Scope.CARDS, GiveCommand.Scope.of("curios:tradingcards"));
        assertEquals(GiveCommand.Scope.RELICS, GiveCommand.Scope.of("reliques"));
        assertEquals(GiveCommand.Scope.ALL, GiveCommand.Scope.of("curios"));
    }

    @Test
    void entityAloneGivesALegacyDefaultSimpleCardToTheSender() {
        var request = ok(GiveCommand.Scope.CARDS, "axolotl");
        assertNull(request.target());
        assertEquals("card_axolotl_simple", request.entry().id());
        assertEquals("0", request.state().get("level"));
        assertEquals("100", request.state().get("quality"));
    }

    @Test
    void playerEntityRarityLevelAndQualityAreAllHonoured() {
        var request = ok(GiveCommand.Scope.CARDS, "ggpots", "Wither_Skeleton", "FABLED", "40", "77");
        assertEquals("ggpots", request.target());
        assertEquals("card_wither_skeleton_fabled", request.entry().id());
        assertEquals("40", request.state().get("level"));
        assertEquals("77", request.state().get("quality"));
    }

    @Test
    void cardsLabelRefusesRelicsAndDefinitionIds() {
        assertEquals("Unknown card entity 'piglin_signet'.", error(GiveCommand.Scope.CARDS, "piglin_signet"));
        assertEquals("Unknown card entity 'card_axolotl_simple'.", error(GiveCommand.Scope.CARDS, "ggpots", "card_axolotl_simple"));
        assertEquals("Unknown relic 'zombie'.", error(GiveCommand.Scope.RELICS, "zombie"));
    }

    @Test
    void relicsTakeAnOptionalLevelOnly() {
        var request = ok(GiveCommand.Scope.RELICS, "ze_flash", "piglin_signet", "3");
        assertEquals("ze_flash", request.target());
        assertEquals("piglin_signet", request.entry().id());
        assertEquals("3", request.state().get("level"));
        assertTrue(error(GiveCommand.Scope.RELICS, "piglin_signet", "1", "2").startsWith("Usage: "));
        assertEquals("piglin_signet", ok(GiveCommand.Scope.ALL, "piglin_signet").entry().id());
    }

    @Test
    void invalidRarityLevelAndQualityAreExplained() {
        assertTrue(error(GiveCommand.Scope.CARDS, "zombie", "mythic").startsWith("Unknown rarity 'mythic'."));
        assertEquals("Level must be 0..100.", error(GiveCommand.Scope.CARDS, "zombie", "elite", "101"));
        assertEquals("Quality must be 1..100.", error(GiveCommand.Scope.CARDS, "zombie", "elite", "5", "0"));
        assertTrue(error(GiveCommand.Scope.CARDS, "ggpots").startsWith("Usage: /cards give [player] <entity>"));
        assertTrue(error(GiveCommand.Scope.CARDS).startsWith("Usage: "));
    }

    @Test
    void completionListsEntitiesNotEveryDefinitionUnderCards() {
        List<String> first = give.complete(new String[]{""}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS);
        assertTrue(first.containsAll(List.of("ggpots", "ze_flash", "allay", "zombie")));
        assertFalse(first.contains("piglin_signet"));
        assertTrue(first.stream().noneMatch(name -> name.startsWith("card_")));
        assertEquals(36 + 2, first.size());

        assertEquals(List.of("piglin_signet"), give.complete(new String[]{"pig"}, GiveCommand.Scope.RELICS, ONLINE, PLAYERS));
        assertEquals(List.of("pig", "piglin", "piglin_brute"), give.complete(new String[]{"ggpots", "pig"}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS));
    }

    @Test
    void completionWalksRarityLevelAndQualityAfterTheEntity() {
        assertEquals(List.of("simple", "elite", "ultimate", "legendary", "fabled"),
                give.complete(new String[]{"ggpots", "fox", ""}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS));
        assertEquals(List.of("elite"), give.complete(new String[]{"fox", "e"}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS));
        assertEquals(List.of("0", "25", "50", "100"), give.complete(new String[]{"fox", "elite", ""}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS));
        assertEquals(List.of("1", "50", "85", "100"), give.complete(new String[]{"ggpots", "fox", "elite", "0", ""}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS));
        assertEquals(List.of(), give.complete(new String[]{"fox", "elite", "0", "100", ""}, GiveCommand.Scope.CARDS, ONLINE, PLAYERS));
    }
}
