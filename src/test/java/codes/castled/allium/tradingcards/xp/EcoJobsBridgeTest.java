package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class EcoJobsBridgeTest {
    @Test void cancelledZeroAndNonFiniteJobAwardsDoNotBecomeCardXp() throws Exception {
        BridgeTestContext context = new BridgeTestContext();
        context.plugins.put("EcoJobs", context.plugin("EcoJobs"));
        List<Double> work = new ArrayList<>();
        EcoJobsBridge bridge = new EcoJobsBridge(Logger.getAnonymousLogger());
        assertTrue(bridge.register(context.owner, (player, job, amount) -> {
            assertEquals("fisherman", job);
            work.add(amount);
        }));
        Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> null);
        for (double amount : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 20}) {
            context.fire(PlayerJobExpGainEvent.class, new PlayerJobExpGainEvent(player, amount));
        }
        PlayerJobExpGainEvent cancelled = new PlayerJobExpGainEvent(player, 100);
        cancelled.setCancelled(true);
        context.fire(PlayerJobExpGainEvent.class, cancelled);
        assertEquals(List.of(20.0), work);
    }
}
