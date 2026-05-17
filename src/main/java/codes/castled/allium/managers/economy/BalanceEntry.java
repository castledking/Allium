package codes.castled.allium.managers.economy;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.math.BigDecimal;
import java.util.UUID;

import codes.castled.allium.managers.core.Text;

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
