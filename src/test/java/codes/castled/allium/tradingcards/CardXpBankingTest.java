package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import codes.castled.allium.tradingcards.boost.BoostService;
import codes.castled.allium.tradingcards.boost.CardProgression;
import codes.castled.allium.tradingcards.boost.EquippedCardTracker;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.xp.CardXpService;
import codes.castled.allium.tradingcards.xp.XpAntiFarmStore;
import codes.castled.allium.tradingcards.xp.XpConfig;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Xp that does not finish a level still has to stay on the card.
 *
 * <p>It did not: an award short of a level returned without writing anything,
 * so the next award started from the same stored xp and a card only levelled
 * when a single award covered a whole level by itself. Kill xp is 4 and level 1
 * costs 25, so in practice no equipped card ever levelled.
 */
class CardXpBankingTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void killsShortOfALevelAddUpOnTheCard() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(new File("src/main/resources/tradingcards/config.yml"));
        XpConfig xpConfig = XpConfig.load(yaml).config();

        Logger logger = Logger.getLogger("test");
        EquippedCardTracker tracker = new EquippedCardTracker();
        CardProgression progression = new CardProgression(new BoostService(logger, null), tracker,
            new CardProgression.ProgressRules(100, 1.0, 1), logger);
        progression.curve(xpConfig::xpForLevel);
        tracker.set(PLAYER, new TradingCardData("PIG", "pig", Tier.SIMPLE, 0, 50,
            List.of("luck"), List.of(), 0.0, 0, false), List.of());

        File folder = Files.createTempDirectory("xp").toFile();
        CardXpService service = new CardXpService(xpConfig, new XpAntiFarmStore(folder), progression,
            id -> Optional.ofNullable(tracker.card(id)), System::currentTimeMillis);
        List<TradingCardData> written = new ArrayList<>();
        service.writer((id, card) -> written.add(card));

        double perKill = xpConfig.source("kill-mob").xp();
        for (int i = 0; i < 3; i++) {
            service.award(player(), "kill-mob", 1.0, "PIG", null);
        }

        assertEquals(3 * perKill, tracker.card(PLAYER).xp(), 1e-9, "the tracker keeps the running total");
        assertEquals(0, tracker.card(PLAYER).level());
        assertEquals(3, written.size(), "each award is written to the card in the slot");
        assertEquals(3 * perKill, written.get(2).xp(), 1e-9);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER;
                case "isOnline" -> true;
                case "hashCode" -> PLAYER.hashCode();
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }
}
