package codes.castled.allium.economy;

import java.util.UUID;

/** Persisted imposed-tax audit entry, linked atomically to its ledger operations. */
public record TaxHistoryRecord(
        long id,
        UUID operationId,
        UUID playerUuid,
        String playerName,
        Money amount,
        int percentageBps,
        Money balanceBefore,
        String imposer,
        long createdAt,
        boolean refunded,
        Money refundAmount,
        int refundPercentageBps,
        String refunder,
        UUID refundOperationId,
        Long refundedAt
) {
}
