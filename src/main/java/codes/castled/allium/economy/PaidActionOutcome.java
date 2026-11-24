package codes.castled.allium.economy;

/**
 * Typed delivery outcome for a {@link PaidActionService} execution.
 *
 * <p>Callers report whether the paid effect actually occurred rather than relying on ambiguous
 * dispatch booleans; settlement policy captures only on {@link Delivered} and releases on
 * {@link Refused} or {@link Interrupted}.
 */
public sealed interface PaidActionOutcome {

    /** The paid action succeeded and its reservation should be captured. */
    record Delivered(String detail) implements PaidActionOutcome {
        public static final Delivered OK = new Delivered("");

        public Delivered {
            detail = detail == null ? "" : detail;
        }
    }

    /** The paid action was refused or declined before completing; release the reservation. */
    record Refused(String reason) implements PaidActionOutcome {
        public static final Refused EMPTY = new Refused("");

        public Refused {
            reason = reason == null ? "" : reason;
        }
    }

    /** The paid action was interrupted (disconnect, retirement, close, or failure); release the reservation. */
    record Interrupted(String reason) implements PaidActionOutcome {
        public static final Interrupted DISCONNECTED = new Interrupted("disconnected");
        public static final Interrupted RETIRED = new Interrupted("retired");
        public static final Interrupted CLOSED = new Interrupted("closed");

        public Interrupted {
            reason = reason == null ? "" : reason;
        }
    }

    default boolean shouldCapture() {
        return this instanceof Delivered;
    }

    static PaidActionOutcome delivered() {
        return Delivered.OK;
    }

    static PaidActionOutcome delivered(String detail) {
        return new Delivered(detail);
    }

    static PaidActionOutcome refused(String reason) {
        return new Refused(reason);
    }

    static PaidActionOutcome interrupted(String reason) {
        return new Interrupted(reason);
    }

    static PaidActionOutcome of(boolean delivered) {
        return delivered ? Delivered.OK : Refused.EMPTY;
    }
}
