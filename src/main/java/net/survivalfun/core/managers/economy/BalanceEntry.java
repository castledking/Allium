package net.survivalfun.core.managers.economy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents an entry in the balance top list
 */
public class BalanceEntry {
    private final UUID playerUUID;
    private final String playerName;
    private final BigDecimal balance;

    public BalanceEntry(UUID playerUUID, String playerName, BigDecimal balance) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.balance = balance;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerName() {
        return playerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}
