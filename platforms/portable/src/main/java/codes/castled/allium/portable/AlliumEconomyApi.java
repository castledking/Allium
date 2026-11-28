package codes.castled.allium.portable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stable public reflection entry point for external Fabric/NeoForge mods (such as GriefPrevention3D)
 * to query and mutate Allium's built-in native economy without a compile-time dependency.
 */
public final class AlliumEconomyApi {
    private AlliumEconomyApi() {}

    public static BigDecimal getBalance(Path worldRoot, UUID playerId) {
        return economy(worldRoot).balance(playerId);
    }

    public static long getWholeBalance(Path worldRoot, UUID playerId) {
        return getBalance(worldRoot, playerId).setScale(0, RoundingMode.FLOOR).longValue();
    }

    public static boolean withdraw(Path worldRoot, UUID playerId, String playerName, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) return false;
        if (amount.compareTo(BigDecimal.ZERO) == 0) return true;
        return economy(worldRoot).takeBalance(playerId, playerName, amount) != null;
    }

    public static boolean withdrawWhole(Path worldRoot, UUID playerId, String playerName, long amount) {
        if (amount < 0L) return false;
        if (amount == 0L) return true;
        return withdraw(worldRoot, playerId, playerName, BigDecimal.valueOf(amount));
    }

    public static boolean deposit(Path worldRoot, UUID playerId, String playerName, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) return false;
        if (amount.compareTo(BigDecimal.ZERO) == 0) return true;
        return economy(worldRoot).addBalance(playerId, playerName, amount) != null;
    }

    public static boolean depositWhole(Path worldRoot, UUID playerId, String playerName, long amount) {
        if (amount < 0L) return false;
        if (amount == 0L) return true;
        return deposit(worldRoot, playerId, playerName, BigDecimal.valueOf(amount));
    }

    public static String format(BigDecimal amount) {
        return NativeEconomy.format(amount);
    }

    public static String formatWhole(long amount) {
        return NativeEconomy.format(BigDecimal.valueOf(amount));
    }

    private static NativeEconomy economy(Path worldRoot) {
        return new NativeEconomy(worldRoot.resolve("allium"));
    }
}
