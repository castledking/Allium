package codes.castled.allium.portable;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Pure Java 8 reflective bridge to EconomyCraft ({@code com.reazip.economycraft.api.v1.EconomyCraftApi}).
 *
 * <p>Requires no compile-time dependency on EconomyCraft and safely returns {@code null} when
 * EconomyCraft is absent or its API cannot be initialized, allowing Allium to fall back cleanly to
 * its built-in {@link NativeEconomy} flatfile store.
 */
public final class EconomyCraftBridge implements NativeEconomyBackend {
    private static final String API_CLASS = "com.reazip.economycraft.api.v1.EconomyCraftApi";

    private final Object api;
    private final Object balances;
    private final Object leaderboard;
    private final Method getBalance;
    private final Method addMoney;
    private final Method removeMoney;
    private final Method setMoney;
    private final Method pay;
    private final Method formatMoney;
    private final Method formatMoneyChat;
    private final Method getLeaderboardEntries;
    private final NativeEconomy localCache;

    private EconomyCraftBridge(Object api, Object balances, Object leaderboard,
                               Method getBalance, Method addMoney, Method removeMoney,
                               Method setMoney, Method pay, Method formatMoney,
                               Method formatMoneyChat, Method getLeaderboardEntries,
                               NativeEconomy localCache) {
        this.api = api;
        this.balances = balances;
        this.leaderboard = leaderboard;
        this.getBalance = getBalance;
        this.addMoney = addMoney;
        this.removeMoney = removeMoney;
        this.setMoney = setMoney;
        this.pay = pay;
        this.formatMoney = formatMoney;
        this.formatMoneyChat = formatMoneyChat;
        this.getLeaderboardEntries = getLeaderboardEntries;
        this.localCache = localCache;
    }

    /** Attempts to connect to EconomyCraft on the running server; returns {@code null} if unavailable. */
    public static EconomyCraftBridge tryConnect(Object server, NativeEconomy localCache) {
        if (server == null) return null;
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Method getMethod = findSingleArgMethod(apiClass, "get");
            if (getMethod == null) return null;
            Object api = getMethod.invoke(null, server);
            if (api == null) return null;
            return wrap(api, localCache);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Wraps an already-obtained EconomyCraft API instance (also used by unit tests). */
    public static EconomyCraftBridge wrap(Object api, NativeEconomy localCache) {
        if (api == null) return null;
        try {
            Class<?> apiClass = api.getClass();
            Object balances = apiClass.getMethod("balances").invoke(api);
            if (balances == null) return null;
            Object leaderboard = null;
            Method getLeaderboardEntries = null;
            try {
                leaderboard = apiClass.getMethod("leaderboard").invoke(api);
                if (leaderboard != null) {
                    getLeaderboardEntries = leaderboard.getClass().getMethod("getLeaderboardEntries", int.class);
                }
            } catch (Throwable ignored) {
            }
            Class<?> balClass = balances.getClass();
            Method getBalance = balClass.getMethod("getBalance", UUID.class);
            Method addMoney = balClass.getMethod("addMoney", UUID.class, long.class);
            Method removeMoney = balClass.getMethod("removeMoney", UUID.class, long.class);
            Method setMoney = balClass.getMethod("setMoney", UUID.class, long.class);
            Method pay = balClass.getMethod("pay", UUID.class, UUID.class, long.class);
            Method formatMoney = apiClass.getMethod("formatMoney", long.class);
            Method formatMoneyChat = null;
            try {
                formatMoneyChat = apiClass.getMethod("formatMoneyChat", long.class);
            } catch (NoSuchMethodException ignored) {
            }
            return new EconomyCraftBridge(api, balances, leaderboard, getBalance, addMoney,
                    removeMoney, setMoney, pay, formatMoney, formatMoneyChat, getLeaderboardEntries, localCache);
        } catch (Throwable incompatible) {
            return null;
        }
    }

    @Override
    public String providerName() {
        return "EconomyCraft";
    }

    @Override
    public boolean supportsDecimals() {
        return false;
    }

    @Override
    public BigDecimal balance(UUID id) {
        try {
            Object raw = getBalance.invoke(balances, id);
            long value = raw instanceof Number ? ((Number) raw).longValue() : 0L;
            return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
        } catch (Throwable failure) {
            return NativeEconomy.ZERO;
        }
    }

    @Override
    public boolean setBalance(UUID id, String name, BigDecimal amount) {
        Long whole = toWhole(amount, true);
        if (whole == null) return false;
        try {
            boolean ok = isSuccessful(setMoney.invoke(balances, id, whole.longValue()));
            if (ok && localCache != null) localCache.recordName(id, name);
            return ok;
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    public BigDecimal addBalance(UUID id, String name, BigDecimal delta) {
        Long whole = toWhole(delta, false);
        if (whole == null) return null;
        try {
            if (!isSuccessful(addMoney.invoke(balances, id, whole.longValue()))) return null;
            if (localCache != null) localCache.recordName(id, name);
            return balance(id);
        } catch (Throwable failure) {
            return null;
        }
    }

    @Override
    public BigDecimal takeBalance(UUID id, String name, BigDecimal delta) {
        Long whole = toWhole(delta, false);
        if (whole == null) return null;
        try {
            if (!isSuccessful(removeMoney.invoke(balances, id, whole.longValue()))) return null;
            if (localCache != null) localCache.recordName(id, name);
            return balance(id);
        } catch (Throwable failure) {
            return null;
        }
    }

    @Override
    public BigDecimal resetBalance(UUID id, String name) {
        return setBalance(id, name, NativeEconomy.ZERO) ? NativeEconomy.ZERO : null;
    }

    @Override
    public boolean transfer(UUID from, String fromName, UUID to, String toName, BigDecimal amount) {
        Long whole = toWhole(amount, false);
        if (whole == null) return false;
        try {
            boolean ok = isSuccessful(pay.invoke(balances, from, to, whole.longValue()));
            if (ok && localCache != null) {
                localCache.recordName(from, fromName);
                localCache.recordName(to, toName);
            }
            return ok;
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    public List<NativeEconomy.AccountEntry> topAccounts() {
        if (leaderboard == null || getLeaderboardEntries == null) {
            return Collections.emptyList();
        }
        try {
            Object raw = getLeaderboardEntries.invoke(leaderboard, 100);
            if (!(raw instanceof List<?>)) return Collections.emptyList();
            List<?> list = (List<?>) raw;
            List<NativeEconomy.AccountEntry> out = new ArrayList<NativeEconomy.AccountEntry>(list.size());
            for (Object entry : list) {
                if (entry == null) continue;
                UUID id = (UUID) entry.getClass().getMethod("playerId").invoke(entry);
                long bal = ((Number) entry.getClass().getMethod("balance").invoke(entry)).longValue();
                String name = localCache != null ? localCache.nameOf(id) : id.toString();
                out.add(new NativeEconomy.AccountEntry(id, name, BigDecimal.valueOf(bal).setScale(2, RoundingMode.HALF_UP)));
            }
            return out;
        } catch (Throwable failure) {
            return Collections.emptyList();
        }
    }

    @Override
    public String formatMoney(BigDecimal amount) {
        long whole = amount == null ? 0L : amount.setScale(0, RoundingMode.HALF_UP).longValue();
        try {
            if (formatMoneyChat != null) {
                Object formatted = formatMoneyChat.invoke(api, whole);
                if (formatted != null) return formatted.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object formatted = formatMoney.invoke(api, whole);
            if (formatted != null) return formatted.toString();
        } catch (Throwable ignored) {
        }
        return NativeEconomy.format(BigDecimal.valueOf(whole));
    }

    private static Long toWhole(BigDecimal value, boolean allowZero) {
        if (value == null) return null;
        try {
            long whole = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (allowZero ? whole < 0L : whole <= 0L) return null;
            return Long.valueOf(whole);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static boolean isSuccessful(Object result) {
        if (result == null) return false;
        try {
            Object ok = result.getClass().getMethod("successful").invoke(result);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable failure) {
            return false;
        }
    }

    private static Method findSingleArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }
}
