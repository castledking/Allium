package codes.castled.allium.addon;

import codes.castled.allium.api.ApiVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AddonVerdictTest {

    private static final ApiVersion PROVIDED = new ApiVersion(1, 0, 0);

    @Test
    void aMatchingRangeIsAccepted() {
        AddonVerdict verdict = AddonVerdict.evaluate("FunItems", "^1.0", PROVIDED, List.of());
        assertEquals(AddonVerdict.Status.ACCEPTED, verdict.status());
        assertFalse(verdict.refused());
    }

    @Test
    void aRangeThatExcludesThisApiIsRefusedWithWhatToDo() {
        AddonVerdict verdict = AddonVerdict.evaluate("FutureAddon", "^2.0", PROVIDED, List.of());
        assertEquals(AddonVerdict.Status.VERSION_MISMATCH, verdict.status());
        assertTrue(verdict.refused());
        assertTrue(verdict.message().contains("needs Allium API ^2.0 (>=2.0.0 <3.0.0)"), verdict.message());
        assertTrue(verdict.message().contains("provides API 1.0.0"), verdict.message());
        assertEquals(AddonVerdict.Status.VERSION_MISMATCH, AddonVerdict.evaluate("X", "^1.1", PROVIDED, List.of()).status());
    }

    @Test
    void shadingIsRefusedEvenWithAMatchingRange() {
        AddonVerdict verdict = AddonVerdict.evaluate("ShadedAddon", "^1.0", PROVIDED, List.of("codes.castled.allium.api.AlliumApi"));
        assertEquals(AddonVerdict.Status.SHADED_API, verdict.status());
        assertTrue(verdict.message().contains("compileOnly"), verdict.message());
    }

    @Test
    void noDeclarationIsNotAnAddonAndABadOneIsRefused() {
        assertEquals(AddonVerdict.Status.NOT_AN_ADDON, AddonVerdict.evaluate("Vault", null, PROVIDED, List.of()).status());
        assertFalse(AddonVerdict.evaluate("Vault", " ", PROVIDED, List.of()).refused());
        AddonVerdict bad = AddonVerdict.evaluate("Typo", "one point oh", PROVIDED, List.of());
        assertEquals(AddonVerdict.Status.INVALID_DECLARATION, bad.status());
        assertTrue(bad.refused());
    }

    @Test
    void theProvidedApiVersionMatchesTheBuildsApiVersion() throws Exception {
        Properties gradle = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            gradle.load(in);
        }
        assertEquals(ApiVersion.parse(gradle.getProperty("alliumApiVersion")), AlliumApiService.API_VERSION,
                "alliumApiVersion in gradle.properties names the API jar; AlliumApiService.API_VERSION is what addons are checked against");
    }
}
