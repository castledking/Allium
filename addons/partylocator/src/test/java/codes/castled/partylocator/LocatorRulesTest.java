package codes.castled.partylocator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocatorRulesTest {
    /** A plain survival pair in one world, {@code distance} blocks apart, radius 128. */
    private static LocatorRules.Pair pair(double distance) {
        return new LocatorRules.Pair(true, true, false, false, false, 0, false, false, false, false,
                distance * distance, 128);
    }

    private static LocatorRules.Pair with(LocatorRules.Pair p, String change) {
        return switch (change) {
            case "otherWorld" -> new LocatorRules.Pair(false, p.barEnabled(), p.hidden(), p.concealed(), p.sameParty(), p.override(), p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "barOff" -> new LocatorRules.Pair(p.sameWorld(), false, p.hidden(), p.concealed(), p.sameParty(), p.override(), p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "hidden" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), true, p.concealed(), p.sameParty(), p.override(), p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "concealed" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), true, p.sameParty(), p.override(), p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "party" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), p.concealed(), true, p.override(), p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "forceShow" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), p.concealed(), p.sameParty(), 1, p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "forceHide" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), p.concealed(), p.sameParty(), -1, p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "staff" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), p.concealed(), p.sameParty(), p.override(), true, p.viewerSpectator(), p.viewerSeesSpectators(), p.targetSpectator(), p.distanceSquared(), p.radius());
            case "spectatorViewer" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), p.concealed(), p.sameParty(), p.override(), p.staffMode(), true, true, p.targetSpectator(), p.distanceSquared(), p.radius());
            case "spectatorTarget" -> new LocatorRules.Pair(p.sameWorld(), p.barEnabled(), p.hidden(), p.concealed(), p.sameParty(), p.override(), p.staffMode(), p.viewerSpectator(), p.viewerSeesSpectators(), true, p.distanceSquared(), p.radius());
            default -> throw new IllegalArgumentException(change);
        };
    }

    @Test
    void nonMembersShowOnlyWithinTheRadius() {
        assertTrue(LocatorRules.show(pair(128)));
        assertFalse(LocatorRules.show(pair(128.5)));
    }

    @Test
    void partyMembersShowAtAnyDistanceButNotAcrossWorldsOrWhenHidden() {
        assertTrue(LocatorRules.show(with(pair(5000), "party")));
        assertFalse(LocatorRules.show(with(with(pair(10), "party"), "otherWorld")));
        assertFalse(LocatorRules.show(with(with(pair(10), "party"), "hidden")), "vanish beats the party");
        assertFalse(LocatorRules.show(with(with(pair(10), "party"), "concealed")), "sneaking or a head hides the dot, as in vanilla");
        assertFalse(LocatorRules.show(with(with(pair(10), "party"), "barOff")), "the locatorBar game rule wins");
    }

    @Test
    void overridesAndStaffModeWorkAsInLegacy() {
        assertTrue(LocatorRules.show(with(pair(9000), "forceShow")));
        assertFalse(LocatorRules.show(with(pair(5), "forceHide")));
        assertTrue(LocatorRules.show(with(with(pair(5), "forceHide"), "party")), "a party member cannot be forced off");
        assertTrue(LocatorRules.show(with(pair(9000), "staff")));
        assertTrue(LocatorRules.show(with(pair(9000), "spectatorViewer")));
    }

    @Test
    void spectatorsStayOffForViewersWhoCannotSeeSpectators() {
        assertFalse(LocatorRules.show(with(pair(5), "spectatorTarget")));
        assertFalse(LocatorRules.show(with(with(pair(5), "spectatorTarget"), "party")));
        assertFalse(LocatorRules.show(with(with(pair(5), "spectatorTarget"), "staff")));
        assertTrue(LocatorRules.show(with(with(pair(5), "spectatorTarget"), "spectatorViewer")));
    }
}
