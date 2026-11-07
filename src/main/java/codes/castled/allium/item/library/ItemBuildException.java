package codes.castled.allium.item.library;

import codes.castled.allium.item.ResolutionStatus;

/** An item that cannot be built, with a message meant for the command sender. */
public class ItemBuildException extends RuntimeException {

    public ItemBuildException(String message) {
        super(message);
    }

    /** The reference did not resolve; {@link #status()} says why (unknown id, provider absent or loading, ...). */
    public static final class Unresolved extends ItemBuildException {
        private final ResolutionStatus status;

        public Unresolved(ResolutionStatus status, String message) {
            super(message);
            this.status = status;
        }

        public ResolutionStatus status() {
            return status;
        }
    }

    /** The sender may not mint this privileged behavior. */
    public static final class Privileged extends ItemBuildException {
        private final String behavior;

        public Privileged(String behavior, String reference) {
            super(reference + " carries privileged behavior " + behavior);
            this.behavior = behavior;
        }

        public String behavior() {
            return behavior;
        }
    }
}
