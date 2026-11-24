package codes.castled.allium.economy;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Terminal lifecycle result reported by {@link PaidActionService} after reservation, delivery,
 * and settlement (or pre-settlement failure) complete.
 */
public record PaidActionResult(
        Status status,
        UUID workflowId,
        @Nullable UUID captureOperationId,
        @Nullable UUID unknownOperationId,
        String detail,
        @Nullable PaidActionOutcome deliveryOutcome
) {

    public enum Status {
        /** Delivery succeeded and the reservation hold was captured. */
        CAPTURED,
        /** Delivery succeeded (and receipt saved if receipt-backed), but capture failed transiently. */
        CAPTURE_DEFERRED,
        /** Delivery did not occur (refused or interrupted) and the hold was released. */
        RELEASED,
        /** Delivery did not occur and releasing the hold failed transiently (left for recovery). */
        RELEASE_DEFERRED,
        /** Economy is unavailable or the paid-action service is closed. */
        UNAVAILABLE,
        /** Player already has an in-flight paid action or the worker queue is saturated. */
        BUSY,
        /** Player account is missing or lacks sufficient funds. */
        INSUFFICIENT_FUNDS,
        /** Economy rejected the reservation for another policy reason (see {@link #detail()}). */
        RESERVATION_REFUSED,
        /** Reserve commit outcome was uncertain; release has been scheduled and delivery did not start. */
        UNKNOWN_RESERVE,
        /** Storage or scheduling failure prevented reservation or baseline proof. */
        STORAGE_ERROR
    }

    public PaidActionResult {
        detail = detail == null ? "" : detail;
    }

    public boolean captured() {
        return status == Status.CAPTURED;
    }

    public boolean delivered() {
        return status == Status.CAPTURED || status == Status.CAPTURE_DEFERRED;
    }

    public static PaidActionResult immediate(Status status, UUID workflowId, String detail) {
        return new PaidActionResult(status, workflowId, null, null, detail, null);
    }
}
