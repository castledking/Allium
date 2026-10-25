package codes.castled.allium.tradingcards;

import codes.castled.allium.tradingcards.bonus.BonusSlot;
import codes.castled.allium.tradingcards.bonus.BonusSlotService;
import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.card.CardFactory;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.item.TradingCardKeys;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BonusSlotServiceTest {
    private static TradingCardData card(Tier tier, List<String> bonuses) {
        return new TradingCardData("ALLAY", "allay", tier, 0, 100,
            CardFactory.startingSignatures(tier), bonuses, 0, false);
    }

    private static BonusSlotService service(List<String> pool) {
        var pools = new java.util.EnumMap<Tier, List<String>>(Tier.class);
        for (var tier : Tier.values()) pools.put(tier, pool);
        var catalog = new BoostCatalog.LoadResult(Map.of(), Map.of(), Map.of(), pools, 3, 1, 9, List.of());
        return new BonusSlotService(catalog, TradingCardsConfig.BonusSlotRoll.defaults(),
            5, null, new java.util.Random(1));
    }

    @Test
    void pricesReadPersistedRollHistoryAndFirstEmptySlotIsAlwaysPriced() {
        var service = service(List.of("armor", "haste"));
        var card = card(Tier.SIMPLE, List.of("armor"));
        var stack = new StoredStack();
        assertEquals(500.0, service.costFor(card(Tier.SIMPLE, List.of()), 0, stack));
        service.writeState(stack, List.of(new BonusSlot("armor", 3, 2400, false)));
        var restored = service.slots(stack, card).get(0);
        assertEquals(3, restored.rolls());
        assertEquals(2400, restored.spent());
        assertEquals(TradingCardsConfig.BonusSlotRoll.defaults().costFor(3, Tier.SIMPLE),
            service.costFor(card, 0, stack));
        assertTrue(service.costFor(card, 0, stack) > 500);
    }

    @Test
    void locksSurviveItemMetaCopiesAndBlockBothRollingAndClearing() {
        var service = service(List.of("armor", "haste"));
        var card = card(Tier.SIMPLE, List.of("armor"));
        var stack = new StoredStack();
        service.writeState(stack, List.of(new BonusSlot("armor", 2, 1200, true)));
        assertFalse(service.canRoll(card, 0, stack));
        assertEquals(BonusSlotService.Outcome.SLOT_LOCKED,
            service.roll(UUID.randomUUID(), card, 0, stack).outcome());
        assertEquals(BonusSlotService.Outcome.SLOT_LOCKED, service.clear(card, 0, stack).outcome());
        service.writeState(stack, service.toggleLock(card, 0, stack).slots());
        assertTrue(service.canRoll(card, 0, stack));
        var cleared = service.clear(card, 0, stack).slots().get(0);
        assertTrue(cleared.isEmpty());
        assertEquals(2, cleared.rolls());
        assertEquals(1200, cleared.spent());
    }

    @Test
    void tierLockedSlotsCannotRollAndSignatureIdsCannotEnterTheBonusPool() {
        var service = service(List.of("luck", "health"));
        for (Tier tier : Tier.values()) {
            var card = card(tier, List.of());
            assertEquals(tier.ordinal() + 1, service.unlockedSlots(tier));
            for (int i = tier.ordinal() + 1; i < 5; i++) {
                assertEquals(BonusSlotService.Outcome.TIER_LOCKED,
                    service.roll(UUID.randomUUID(), card, i, null).outcome());
            }
        }
        assertEquals(BonusSlotService.Outcome.NOTHING_TO_ROLL,
            service.roll(UUID.randomUUID(), card(Tier.ULTIMATE, List.of()), 0, null).outcome());
    }

    @Test
    void oldCardsAdoptFixedSignaturesWithoutCollapsingTheirBonusSlots() {
        var stack = new StoredStack();
        stack.values.put(TradingCardKeys.CARD, (byte) 1);
        stack.values.put(TradingCardKeys.CARD_ID, "allay");
        stack.values.put(TradingCardKeys.MOB, "ALLAY");
        stack.values.put(TradingCardKeys.TIER, "FABLED");
        stack.values.put(TradingCardKeys.SIGNATURES, "luck,strength,speed,crit-chance,regeneration,toughness");
        stack.values.put(TradingCardKeys.BONUSES, "-,-,armor,-,haste");
        var restored = TradingCardData.read(stack).orElseThrow();
        assertEquals(CardFactory.startingSignatures(Tier.FABLED), restored.signatures());
        assertEquals(List.of("", "", "armor", "", "haste"), restored.bonuses());
    }

    /** Bukkit returns detached meta. Mutating it only persists after setItemMeta. */
    private static final class StoredStack extends ItemStack {
        private Map<NamespacedKey, Object> values = new HashMap<>();
        StoredStack() { super(); }
        @Override public boolean hasItemMeta() { return true; }
        @Override public Material getType() { return Material.PAPER; }
        @Override public ItemMeta getItemMeta() {
            var copy = new HashMap<>(values);
            var pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> copy.get(args[0]);
                    case "getOrDefault" -> copy.getOrDefault(args[0], args[2]);
                    case "has" -> copy.containsKey(args[0]);
                    case "set" -> { copy.put((NamespacedKey) args[0], args[2]); yield null; }
                    case "remove" -> { copy.remove(args[0]); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> pdc;
                    case "serialize" -> Map.of("values", copy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
        @Override @SuppressWarnings("unchecked") public boolean setItemMeta(ItemMeta meta) {
            values = new HashMap<>((Map<NamespacedKey, Object>) meta.serialize().get("values"));
            return true;
        }
    }
}
