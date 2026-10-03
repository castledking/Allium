package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/**
 * Reading a card's xp back, whatever number type it was left as.
 *
 * <p>An equipped card is stored by Relique through AbyssalLib's YAML codec,
 * which reads every number back as an int tag. Paper will not read an int tag
 * as a double, so a card that had been in the slot threw on its next read — and
 * the read is what {@code /cards reload} and the right-click menu start with.
 */
class CardXpStorageTest {

    @Test
    void xpWrittenAsAStringReadsBackExactly() {
        assertEquals(12.75, TradingCardData.readXp(pdc(PersistentDataType.STRING, "12.75")));
    }

    @Test
    void aCardThatWentThroughReliqueReadsItsTruncatedInt() {
        // The bug: an IntTag where a double was written.
        assertEquals(12.0, TradingCardData.readXp(pdc(PersistentDataType.INTEGER, 12)));
    }

    @Test
    void aCardWrittenBeforeTheChangeStillReadsItsDouble() {
        assertEquals(3.5, TradingCardData.readXp(pdc(PersistentDataType.DOUBLE, 3.5)));
    }

    @Test
    void noXpAndUnreadableXpBothReadAsZero() {
        assertEquals(0.0, TradingCardData.readXp(pdc(null, null)));
        assertEquals(0.0, TradingCardData.readXp(pdc(PersistentDataType.STRING, "lots")));
    }

    /**
     * A container holding one value under the xp key, answering {@code has} and
     * {@code get} only for the type it was stored as — which is how Paper's
     * behaves, and the whole reason the read has to try each type.
     */
    private static PersistentDataContainer pdc(PersistentDataType<?, ?> type, Object value) {
        Map<NamespacedKey, Object> values = new HashMap<>();
        if (type != null) {
            values.put(TradingCardKeys.XP, value);
        }
        return (PersistentDataContainer) Proxy.newProxyInstance(
            PersistentDataContainer.class.getClassLoader(),
            new Class<?>[] {PersistentDataContainer.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ((name.equals("has") || name.equals("get")) && args != null && args.length == 2) {
                    Object stored = values.get(args[0]);
                    boolean matches = stored != null
                        && ((PersistentDataType<?, ?>) args[1]).getPrimitiveType().isInstance(stored);
                    return name.equals("has") ? matches : (matches ? stored : null);
                }
                throw new UnsupportedOperationException(name);
            });
    }
}
