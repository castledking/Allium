package codes.castled.allium.portable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Abstraction over the active economy ledger on native Fabric and NeoForge servers
 * (Allium's built-in flatfile {@link NativeEconomy} or an optional external provider such as EconomyCraft).
 */
public interface NativeEconomyBackend {
    String providerName();

    boolean supportsDecimals();

    BigDecimal balance(UUID id);

    boolean setBalance(UUID id, String name, BigDecimal amount);

    BigDecimal addBalance(UUID id, String name, BigDecimal delta);

    BigDecimal takeBalance(UUID id, String name, BigDecimal delta);

    BigDecimal resetBalance(UUID id, String name);

    boolean transfer(UUID from, String fromName, UUID to, String toName, BigDecimal amount);

    List<NativeEconomy.AccountEntry> topAccounts();

    String formatMoney(BigDecimal amount);
}
