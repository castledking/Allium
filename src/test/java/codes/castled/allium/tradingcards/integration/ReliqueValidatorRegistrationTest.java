package codes.castled.allium.tradingcards.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.darksoulq.relique.core.RelicRegistries;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The validator has to be reachable from the registry Relique actually reads.
 *
 * <p>Relique exposes two of them. {@code RelicValidators.VALIDATORS} is a
 * {@code DeferredRegistry}, which only stages entries until someone calls
 * {@code apply()}; {@code RelicRegistries.VALIDATORS} is the live {@code Registry}
 * that {@code RelicManager.isValid} queries. Registering into the first is a
 * no-op as far as Relique is concerned, and it fails in the worst possible way:
 * {@code isValid} skips a key it cannot resolve without logging anything, so the
 * slot still renders its icon and every equip attempt is silently refused.
 *
 * <p>This pins the seam that actually broke. Checking that the slot JSON names
 * the right validator is not enough — it was correct while the feature was still
 * dead.
 */
class ReliqueValidatorRegistrationTest {

    @Test
    void theValidatorIsVisibleInTheRegistryReliqueReads() {
        ReliqueIntegration.registerValidator(Logger.getLogger("relique-test"));

        assertTrue(RelicRegistries.VALIDATORS.contains("relique:trading_card"),
            "Relique resolves validators from RelicRegistries.VALIDATORS. An entry that "
                + "only ever reaches a DeferredRegistry is never applied, is skipped "
                + "without a log line, and makes every card unequippable.");
        assertNotNull(RelicRegistries.VALIDATORS.get("relique:trading_card"),
            "the slot asks for this key by name; anything else is a silent miss");
    }
}
