package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;
import org.junit.jupiter.api.Test;

class FishingXpListenerTest {
    private final Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> UUID.randomUUID());
    private final RecordingXp xp = new RecordingXp();
    private final FishingXpListener listener = new FishingXpListener(xp, 1, true);

    @Test void liteFishCatchHasPositiveEcoJobsTriggerValue() {
        PlayerFishEvent event = caught("COD_1");
        assertEquals(0, event.getExpToDrop(), "LiteFish constructs the event without setting XP");
        listener.prepareEcoJobsCatch(event);
        assertEquals(1, event.getExpToDrop());
        assertEquals(20.0, event.getExpToDrop() * 20.0, "default fisherman counter now receives work");
    }

    @Test void positiveValuesVanillaEventsAndCancelledEventsArePreserved() {
        PlayerFishEvent existing = caught("COD_1");
        existing.setExpToDrop(5);
        listener.prepareEcoJobsCatch(existing);
        assertEquals(5, existing.getExpToDrop());
        PlayerFishEvent vanilla = caught(null);
        listener.prepareEcoJobsCatch(vanilla);
        assertEquals(0, vanilla.getExpToDrop());
        PlayerFishEvent cancelled = caught("COD_1");
        cancelled.setCancelled(true);
        listener.prepareEcoJobsCatch(cancelled);
        assertEquals(0, cancelled.getExpToDrop());
        new FishingXpListener(xp, 0, true).prepareEcoJobsCatch(caught("COD_1"));
    }

    @Test void vanillaCatchPaysButLiteFishEchoAndCancelledCatchDoNotPayAgain() {
        listener.onCatch(caught(null));
        listener.onCatch(caught("COD_1"));
        PlayerFishEvent cancelled = caught(null);
        cancelled.setCancelled(true);
        listener.onCatch(cancelled);
        assertEquals(1, xp.awards);
    }

    @Test void unavailableLiteFishHookCanFallBackToBukkitCatch() {
        new FishingXpListener(xp, 1, false).onCatch(caught("COD_1"));
        assertEquals(1, xp.awards);
    }

    private PlayerFishEvent caught(String species) {
        Item item = BridgeTestContext.proxy(Item.class, (p, m, a) -> new TestFishStack(species, null));
        FishHook hook = BridgeTestContext.proxy(FishHook.class, (p, m, a) -> null);
        return new PlayerFishEvent(player, item, hook, PlayerFishEvent.State.CAUGHT_FISH);
    }

    private static class RecordingXp extends CardXpService {
        int awards;
        RecordingXp() { super(null, null, null, null, null); }
        @Override public Award award(Player player, String source, double amount, String mob, String claim) {
            assertEquals("fish-catch", source);
            awards++;
            return Award.progress(source, 4, 0, 0);
        }
    }
}
