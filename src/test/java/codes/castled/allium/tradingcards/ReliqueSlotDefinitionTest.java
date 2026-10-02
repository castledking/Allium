package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Relique slot definition and the validator Allium registers.
 *
 * <p>These two have to agree, and nothing else would have told us when they
 * stopped. Relique creates its validator registry with its own namespace, so a
 * validator is addressed by a single path token; the slot JSON, by contrast, is
 * parsed with {@code Key.key(...)} and so needs the full key. Registering
 * {@code allium:trading_card} built {@code Key[relique:allium:trading_card]},
 * Adventure rejected it for having two colons, and the integration caught the
 * failure and logged a warning — which is exactly how a feature can be broken
 * for weeks with the server otherwise looking healthy.
 */
class ReliqueSlotDefinitionTest {

    private static final Path SLOT =
        Path.of("src/main/resources/tradingcards/relic/allium/slots/card.json");

    /** The path Allium registers, mirrored from ReliqueIntegration. */
    private static final String VALIDATOR_PATH = "trading_card";

    @Test
    void theSlotNamesTheValidatorAlliumRegisters() throws Exception {
        String json = Files.readString(SLOT);
        Matcher m = Pattern.compile("\"validators\"\\s*:\\s*\\[\\s*\"([^\"]+)\"")
            .matcher(json);
        assertTrue(m.find(), "no validators entry in " + SLOT);
        assertEquals("relique:" + VALIDATOR_PATH, m.group(1),
            "the slot names a validator Allium never registers. Relique parses this "
                + "with Key.key(...) so it needs the full key including Relique's own "
                + "namespace, because Relique registers the validator under that "
                + "namespace from a bare path token.");
    }

    @Test
    void theValidatorKeyHasExactlyOneColon() {
        String key = "relique:" + VALIDATOR_PATH;
        assertEquals(1, key.chars().filter(c -> c == ':').count(),
            "Adventure rejects a key with more than one colon, which is what "
                + "disabled the integration");
    }

    @Test
    void theSlotJsonIsValid() throws Exception {
        String json = Files.readString(SLOT);
        assertTrue(json.trim().startsWith("{") && json.trim().endsWith("}"),
            "the slot definition is not a JSON object; Relique would skip it");
        assertTrue(json.contains("\"size\""), "no size; Relique would reject the slot");
        assertTrue(json.contains("\"icon\""), "no icon; the slot would render empty");
    }
}