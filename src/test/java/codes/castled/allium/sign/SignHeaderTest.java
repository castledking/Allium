package codes.castled.allium.sign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignHeaderTest {

    @Test
    void headersAreReadWithoutColorCodes() {
        assertEquals(SignIdentity.Type.REPAIR, SignService.header("[repair]"));
        // Paper keeps the existing line's style when a face is re-edited.
        assertEquals(SignIdentity.Type.REPAIR, SignService.header("§1[repair]"));
        assertEquals(SignIdentity.Type.WARP, SignService.header("§1§l[Warp]"));
        assertNull(SignService.header("§1repair"));
        assertNull(SignService.header(null));
        assertEquals("hand", SignService.plain("§0hand "));
        assertNull(SignService.plain(null));
    }
}
