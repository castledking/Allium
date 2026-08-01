package codes.castled.allium.packetevents.impl;

/**
 * Decides how a tab entry should be restored after Bukkit entity visibility changes.
 * Kept separate from the reflective TAB bridge so the packet policy is deterministic
 * and can be regression-tested without a running Minecraft server.
 */
public final class TabListRefreshPolicy {

    public enum EntryOperation {
        ADD_ENTRY,
        UPDATE_LISTED
    }

    public record EntryPlan(
            EntryOperation operation,
            boolean includeDisplayName
    ) {
    }

    private TabListRefreshPolicy() {
    }

    public static EntryPlan entryPlan(boolean entryPresent, boolean animatedTarget) {
        if (entryPresent) {
            // TAB now owns animated frames. Preserve its current display name and
            // only restore the listed flag.
            return new EntryPlan(EntryOperation.UPDATE_LISTED, false);
        }

        // TAB's format is placeholder-cached. Installing it as the forced display
        // name for an animated entry can roll the client back to an older frame.
        return new EntryPlan(EntryOperation.ADD_ENTRY, !animatedTarget);
    }

    public static long[] retryDelays(boolean hidden) {
        // Packet removal interception is the first line of defence. One next-tick
        // reconciliation is enough to restore an entry if another plugin removed it.
        return new long[] { 1L };
    }
}
