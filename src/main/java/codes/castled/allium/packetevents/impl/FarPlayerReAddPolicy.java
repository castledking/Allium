package codes.castled.allium.packetevents.impl;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action;

import java.util.EnumSet;

/**
 * Decides what to keep of a tab entry that another plugin re-adds for a player
 * the client already lists.
 *
 * <p>ExtendedHorizons draws players beyond the tracking range as fake entities
 * and re-sends their tab entry first: an ADD without UPDATE_LISTED or
 * INITIALIZE_CHAT, carrying the server's default display name and list order.
 * It writes that packet past TAB's rewriting handler, so on the client it
 * replaces TAB's formatted name (losing the rank prefix) and TAB's sort order.
 * The client keeps its existing entry for an ADD of a known profile, so only
 * the display name and list order need dropping for TAB's formatting to stay.
 */
public final class FarPlayerReAddPolicy {

    private FarPlayerReAddPolicy() {
    }

    /**
     * @return the actions to send instead, or {@code null} to leave the packet alone
     */
    public static EnumSet<Action> trim(EnumSet<Action> actions) {
        if (actions == null
                || !actions.contains(Action.ADD_PLAYER)
                || actions.contains(Action.UPDATE_LISTED)
                || actions.contains(Action.INITIALIZE_CHAT)) {
            // A server join or showPlayer always lists and initialises chat.
            return null;
        }
        if (!actions.contains(Action.UPDATE_DISPLAY_NAME) && !actions.contains(Action.UPDATE_LIST_ORDER)) {
            return null;
        }
        EnumSet<Action> trimmed = EnumSet.copyOf(actions);
        trimmed.remove(Action.UPDATE_DISPLAY_NAME);
        trimmed.remove(Action.UPDATE_LIST_ORDER);
        return trimmed;
    }
}
