package codes.castled.allium.managers.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModGuardTranslationProbeEvaluateTest {

    private static final String METEOR_KEY = "key.meteor-client.open-gui";
    private static final String WURST_KEY = "key.wurst.zoom";
    private static final String PREFIX = "allium_probe_";
    private static final String FALLBACK = "allium_probe_1a2b3c";

    @Test
    void meteorRawKeyEchoIsClean() {
        assertResult(ProbeMode.METEOR, METEOR_KEY, METEOR_KEY, ProbeResult.CLEAN);
    }

    @Test
    void meteorFallbackEchoIsClean() {
        assertResult(ProbeMode.METEOR, METEOR_KEY, FALLBACK, ProbeResult.CLEAN);
        assertResult(ProbeMode.METEOR, METEOR_KEY, FALLBACK + " more text", ProbeResult.CLEAN);
    }

    @Test
    void meteorLocalizedResolutionIsDetected() {
        assertResult(ProbeMode.METEOR, METEOR_KEY, "Open GUI", ProbeResult.DETECTED);
    }

    @Test
    void meteorKeyPlusLetterIsClean() {
        assertResult(ProbeMode.METEOR, METEOR_KEY, METEOR_KEY + "W", ProbeResult.CLEAN);
    }

    @Test
    void emptyResolvedIsClean() {
        assertResult(ProbeMode.METEOR, METEOR_KEY, "", ProbeResult.CLEAN);
    }

    @Test
    void keybindRawKeyEchoIsClean() {
        assertResult(ProbeMode.KEYBIND, WURST_KEY, WURST_KEY, ProbeResult.CLEAN);
    }

    @Test
    void keybindSubstringEchoIsClean() {
        assertResult(ProbeMode.KEYBIND, WURST_KEY, "Options: " + WURST_KEY, ProbeResult.CLEAN);
    }

    @Test
    void keybindLocalizedResolutionIsDetected() {
        assertResult(ProbeMode.KEYBIND, WURST_KEY, "Wurst Zoom", ProbeResult.DETECTED);
    }

    @Test
    void keybindExploitPreventerRawEchoIsProtected() {
        assertResult(ProbeMode.KEYBIND, WURST_KEY, WURST_KEY, true, ProbeResult.PROTECTED);
    }

    @Test
    void translateFallbackEchoIsClean() {
        assertResult(ProbeMode.TRANSLATE, METEOR_KEY, FALLBACK, ProbeResult.CLEAN);
    }

    @Test
    void translateRawKeyEchoIsProtected() {
        assertResult(ProbeMode.TRANSLATE, METEOR_KEY, METEOR_KEY, ProbeResult.PROTECTED);
    }

    @Test
    void translateExpectedMatchIsDetected() {
        assertResult(ProbeMode.TRANSLATE, "inventoryprofiles.config.name.open_config_menu",
                "Config", Set.of("Config"), ProbeResult.DETECTED);
    }

    @Test
    void translateExpectedNoMatchIsClean() {
        assertResult(ProbeMode.TRANSLATE, "inventoryprofiles.config.name.open_config_menu",
                "Something Else", Set.of("Config"), ProbeResult.CLEAN);
    }

    @Test
    void translateNoExpectedWithResolutionIsDetected() {
        assertResult(ProbeMode.TRANSLATE, METEOR_KEY, "Open Config Menu", ProbeResult.DETECTED);
    }

    @Test
    void echoOfAnotherProbesFallbackIsForeign() {
        // Live false kick 2026-09-10: vanilla client echoed a zombie probe's sign.
        String[] lines = {"allium_probe_7143b425cdf69ce6", "allium_probe_7143b425cdf69ce6", "allium_probe_7143b425cdf69ce6", "W"};
        List<ProbeMode> modes = List.of(ProbeMode.METEOR, ProbeMode.METEOR, ProbeMode.METEOR);
        assertTrue(ModGuardTranslationProbe.isForeignEcho(lines, modes, "allium_probe_ba54f719e2e7f20e", PREFIX));
    }

    @Test
    void keybindLineEchoingFallbackIsForeign() {
        String[] lines = {FALLBACK, "", "", "W"};
        assertTrue(ModGuardTranslationProbe.isForeignEcho(lines, List.of(ProbeMode.KEYBIND), FALLBACK, PREFIX));
    }

    @Test
    void echoOfCurrentFallbackIsNotForeign() {
        String[] lines = {FALLBACK, WURST_KEY, "", "W"};
        List<ProbeMode> modes = List.of(ProbeMode.METEOR, ProbeMode.KEYBIND);
        assertFalse(ModGuardTranslationProbe.isForeignEcho(lines, modes, FALLBACK, PREFIX));
    }

    @Test
    void localizedResolutionIsNotForeign() {
        String[] lines = {"Open GUI", FALLBACK, "", "W"};
        List<ProbeMode> modes = List.of(ProbeMode.METEOR, ProbeMode.METEOR);
        assertFalse(ModGuardTranslationProbe.isForeignEcho(lines, modes, FALLBACK, PREFIX));
    }

    private static void assertResult(ProbeMode mode, String key, String resolved, ProbeResult expected) {
        assertResult(mode, key, resolved, false, expected);
    }

    private static void assertResult(ProbeMode mode, String key, String resolved,
                                     Set<String> expected, ProbeResult probeResult) {
        ProbeResult actual = ModGuardTranslationProbe.evaluateProbe(mode, key, expected, resolved, FALLBACK, false);
        assertEquals(probeResult, actual);
    }

    private static void assertResult(ProbeMode mode, String key, String resolved,
                                     boolean exploitPreventer, ProbeResult probeResult) {
        ProbeResult actual = ModGuardTranslationProbe.evaluateProbe(mode, key, Set.of(), resolved, FALLBACK, exploitPreventer);
        assertEquals(probeResult, actual);
    }
}