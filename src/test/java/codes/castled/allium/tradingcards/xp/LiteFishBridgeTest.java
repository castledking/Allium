package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import dev.nekomadev.liteFish.api.CatchEvent;
import dev.nekomadev.litecore.managers.LiteEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class LiteFishBridgeTest {
    @Test void unweightedSpeciesStillPayAndSharedEventsDoNotBecomeCatches() throws Exception {
        BridgeTestContext context = new BridgeTestContext();
        context.plugins.put("LiteFish", context.plugin("LiteFish"));
        LiteFishBridge bridge = new LiteFishBridge(Logger.getAnonymousLogger());
        List<String> catches = new ArrayList<>();
        assertTrue(bridge.register(context.owner, (p, species, weight) -> catches.add(species + ":" + weight)));
        Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> null);
        context.fire(CatchEvent.class, new CatchEvent(player,
            List.of(new TestFishStack("COD_1", null)), "SUCCESS"));
        context.fire(CatchEvent.class, new CatchEvent(player,
            List.of(new TestFishStack("SALMON_1", 12.4)), "SUCCESS"));
        context.fire(CatchEvent.class, new CatchEvent(player,
            List.of(new TestFishStack("COD_1", 40.0)), "FAIL"));
        context.fire(CatchEvent.class, new LiteEvent());
        assertEquals(List.of("COD_1:0.0", "SALMON_1:12.4"), catches);
    }
}
