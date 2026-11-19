package codes.castled.allium.economy;

import java.util.Objects;
import java.util.UUID;

/** Persisted cheque note record; prevents forging and double-redemption across restarts. */
public record ChequeRecord(
        UUID chequeId,
        Money amount,
        Money tax,
        UUID issuerUuid,
        String issuerName,
        boolean adminIssued,
        String state,
        UUID issueOperationId,
        UUID redeemOperationId,
        UUID redeemerUuid,
        long createdAt,
        Long redeemedAt
) {
    public ChequeRecord {
        Objects.requireNonNull(chequeId, "chequeId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(issuerName, "issuerName");
        Objects.requireNonNull(state, "state");
        amount.positive();
        if (tax.minor() < 0) throw new IllegalArgumentException("tax must not be negative");
    }
}
