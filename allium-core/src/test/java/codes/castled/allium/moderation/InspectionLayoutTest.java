package codes.castled.allium.moderation;

import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InspectionLayoutTest {

    @Test
    void invseeMappingRoundTripsAll41PlayerSlotsAndLocksPanes() {
        Set<Integer> seenPlayerSlots = new HashSet<>();
        for (int guiSlot = 0; guiSlot < InspectionLayout.INVSEE_GUI_SIZE; guiSlot++) {
            if (guiSlot >= 41) {
                assertTrue(InspectionLayout.isLockedUiSlot(InspectionLayout.InspectionType.INVSEE, guiSlot));
                assertEquals(-1, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, guiSlot));
            } else {
                assertFalse(InspectionLayout.isLockedUiSlot(InspectionLayout.InspectionType.INVSEE, guiSlot));
                int playerSlot = InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, guiSlot);
                assertTrue(playerSlot >= 0 && playerSlot < 41, "guiSlot=" + guiSlot);
                assertTrue(seenPlayerSlots.add(playerSlot), "Duplicate playerSlot=" + playerSlot);
                assertEquals(guiSlot, InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, playerSlot));
            }
        }
        assertEquals(41, seenPlayerSlots.size());

        // Exact layout semantics
        assertEquals(9, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 0));
        assertEquals(35, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 26));
        assertEquals(0, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 27));
        assertEquals(8, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 35));
        assertEquals(39, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 36)); // helmet
        assertEquals(38, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 37)); // chestplate
        assertEquals(37, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 38)); // leggings
        assertEquals(36, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 39)); // boots
        assertEquals(40, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 40)); // offhand
        assertEquals(-1, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, -1));
        assertEquals(-1, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.INVSEE, 45));
    }

    @Test
    void enderchestMappingIs27SlotIdentityWithNoLockedSlots() {
        for (int i = 0; i < InspectionLayout.ENDERCHEST_GUI_SIZE; i++) {
            assertFalse(InspectionLayout.isLockedUiSlot(InspectionLayout.InspectionType.ENDERCHEST, i));
            assertEquals(i, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.ENDERCHEST, i));
            assertEquals(i, InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.ENDERCHEST, i));
        }
        assertEquals(-1, InspectionLayout.toPlayerSlot(InspectionLayout.InspectionType.ENDERCHEST, 27));
        assertEquals(-1, InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.ENDERCHEST, 27));
    }

    @Test
    void auditDetailSanitizesControlCharsAndBoundsTo512Chars() {
        List<InspectionLayout.SlotDelta> deltas = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            deltas.add(new InspectionLayout.SlotDelta(
                    i,
                    new InspectionLayout.ItemSummary("NETHERITE_CHESTPLATE", 1, true),
                    new InspectionLayout.ItemSummary("ENCHANTED_GOLDEN_APPLE", 64, true)
            ));
        }
        String detail = InspectionLayout.formatAuditDetail(
                InspectionLayout.InspectionType.INVSEE,
                InspectionLayout.InspectionMode.OFFLINE,
                true,
                "reason\nwith\r\tcontrol\u0007chars",
                deltas
        );
        assertTrue(detail.length() <= InspectionLayout.MAX_AUDIT_DETAIL_LENGTH, "Length was " + detail.length());
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\r"));
        assertFalse(detail.contains("\t"));
        assertFalse(detail.contains("\u0007"));
        assertTrue(detail.contains("more"));
        assertTrue(detail.startsWith("type=INVSEE mode=OFFLINE editable=true info=reason with controlchars changes="));
    }

    @Test
    void commandSpecsRegisterInvseeAndEnderchestWithVisibilityAndOfflineCompletionGating() {
        assertTrue(CoreCommandSpecs.MODERATION_SPECS.contains(CoreCommandSpecs.INVSEE));
        assertTrue(CoreCommandSpecs.MODERATION_SPECS.contains(CoreCommandSpecs.ENDERCHEST));
        assertEquals(List.of("echest", "ec"), CoreCommandSpecs.ENDERCHEST.aliases());
        assertEquals("allium.invsee", CoreCommandSpecs.INVSEE.basePermission());
        assertEquals("allium.enderchest", CoreCommandSpecs.ENDERCHEST.basePermission());
        assertEquals("allium.enderchest.others", CoreCommandSpecs.ENDERCHEST.othersPermission());

        List<String> visibleOnline = List.of("Alice");
        List<String> knownAll = List.of("Alice", "OfflineBob");

        // 1. Only base permission -> visible online only
        CommandCompletionContext onlineOnly = ctx(Set.of("allium.invsee", "allium.enderchest", "allium.enderchest.others"), visibleOnline, knownAll);
        assertEquals(List.of("Alice"), CoreCommandSpecs.INVSEE.complete(onlineOnly, new String[]{""}));
        assertEquals(List.of("Alice"), CoreCommandSpecs.ENDERCHEST.complete(onlineOnly, new String[]{""}));

        // 2. .offline WITHOUT moderation.bypass -> still visible online only
        CommandCompletionContext offlineNoBypass = ctx(Set.of(
                "allium.invsee", "allium.invsee.offline",
                "allium.enderchest", "allium.enderchest.others", "allium.enderchest.offline"), visibleOnline, knownAll);
        assertEquals(List.of("Alice"), CoreCommandSpecs.INVSEE.complete(offlineNoBypass, new String[]{""}));
        assertEquals(List.of("Alice"), CoreCommandSpecs.ENDERCHEST.complete(offlineNoBypass, new String[]{""}));

        // 3. .offline + moderation.bypass -> includes known offline names
        CommandCompletionContext offlineWithBypass = ctx(Set.of(
                "allium.invsee", "allium.invsee.offline",
                "allium.enderchest", "allium.enderchest.others", "allium.enderchest.offline",
                "allium.moderation.bypass"), visibleOnline, knownAll);
        assertEquals(List.of("Alice", "OfflineBob"), CoreCommandSpecs.INVSEE.complete(offlineWithBypass, new String[]{""}));
        assertEquals(List.of("Alice", "OfflineBob"), CoreCommandSpecs.ENDERCHEST.complete(offlineWithBypass, new String[]{""}));

        // 4. enderchest without .others -> no player completions
        CommandCompletionContext selfOnly = ctx(Set.of("allium.enderchest"), visibleOnline, knownAll);
        assertEquals(List.of(), CoreCommandSpecs.ENDERCHEST.complete(selfOnly, new String[]{""}));
    }

    @Test
    void guiTitleFormattingPreservesOfflineAndModeTagsWithin32Chars() {
        String maxName = "SixteenCharName1";
        for (InspectionLayout.InspectionType type : InspectionLayout.InspectionType.values()) {
            for (InspectionLayout.InspectionMode mode : List.of(InspectionLayout.InspectionMode.ONLINE, InspectionLayout.InspectionMode.OFFLINE)) {
                for (boolean editable : new boolean[]{false, true}) {
                    for (String name : List.of("InspNorm", maxName)) {
                        String title = InspectionLayout.formatGuiTitle(type, mode, editable, name, "\u00a78");
                        assertTrue(title.length() <= InspectionLayout.MAX_GUI_TITLE_LENGTH,
                                "Title too long (" + title.length() + "): " + title);
                        if (editable) {
                            assertTrue(title.endsWith(" (Edit)"), "Missing (Edit) in: " + title);
                        } else {
                            assertTrue(title.endsWith(" (Read-Only)") || title.endsWith(" (RO)"),
                                    "Missing read-only suffix in: " + title);
                        }
                        if (mode == InspectionLayout.InspectionMode.OFFLINE) {
                            assertTrue(title.contains("[Offline]") || title.contains("[Off]"),
                                    "Missing offline indicator in: " + title);
                        }
                    }
                }
            }
        }
    }

    private static CommandCompletionContext ctx(Set<String> perms, List<String> visibleOnline, List<String> known) {
        return new CommandCompletionContext() {
            @Override
            public boolean hasPermission(String permissionNode) {
                return perms.contains(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return visibleOnline;
            }

            @Override
            public List<String> knownPlayers(String prefix) {
                return known;
            }
        };
    }
}
