package codes.castled.partylocator;

/**
 * Who appears on whose locator bar, as legacy Allium's PartyManager decided it.
 *
 * <p>Party members always see each other; staff in staff locator mode, and spectators allowed to
 * see spectators, see everyone; a per-viewer override forces a dot on or off until it expires;
 * everyone else is shown only within the viewer's radius in the same world. A spectator is never
 * shown to a viewer who may not see spectators. On top of that, vanilla's own ways of hiding a dot
 * still apply, because the addon sends positions vanilla would not: a hidden or vanished player, a
 * sneaking or invisible one, or one wearing a carved pumpkin or a mob head never shows.
 */
final class LocatorRules {
    /** Everything the decision reads about one viewer and one target. */
    record Pair(boolean sameWorld, boolean barEnabled, boolean hidden, boolean concealed,
                boolean sameParty, int override, boolean staffMode,
                boolean viewerSpectator, boolean viewerSeesSpectators, boolean targetSpectator,
                double distanceSquared, double radius) {}

    private LocatorRules() {}

    static boolean show(Pair pair) {
        if (!pair.sameWorld() || !pair.barEnabled() || pair.hidden() || pair.concealed()) return false;
        boolean spectatorHidden = pair.targetSpectator() && !pair.viewerSeesSpectators();
        if (pair.sameParty()) return !spectatorHidden;
        if (pair.override() > 0) return !spectatorHidden;
        if (pair.override() < 0) return false;
        if (pair.viewerSpectator() && pair.viewerSeesSpectators()) return true;
        if (pair.staffMode()) return !spectatorHidden;
        if (spectatorHidden) return false;
        return pair.distanceSquared() <= pair.radius() * pair.radius();
    }
}
