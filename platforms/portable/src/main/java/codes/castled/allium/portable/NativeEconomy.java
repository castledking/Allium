package codes.castled.allium.portable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Atomic flatfile economy engine shared by native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3) adapters.
 * Persists balances, known account names, and payment preferences in {@code <world>/allium/economy.properties}.
 */
public final class NativeEconomy implements NativeEconomyBackend {
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    public static final BigDecimal MAX_BALANCE = new BigDecimal("100000000000000.00");
    public static final long CONFIRM_WINDOW_MILLIS = 30_000L;

    public static final class AccountEntry {
        public final UUID id;
        public final String name;
        public final BigDecimal balance;

        public AccountEntry(UUID id, String name, BigDecimal balance) {
            this.id = id;
            this.name = name;
            this.balance = balance;
        }
    }

    public static final class PendingConfirmations {
        private static final class Pending {
            final UUID recipient;
            final BigDecimal amount;
            final long expiresAt;

            Pending(UUID recipient, BigDecimal amount, long expiresAt) {
                this.recipient = recipient;
                this.amount = amount;
                this.expiresAt = expiresAt;
            }
        }

        private final Map<UUID, Pending> byPayer = new ConcurrentHashMap<UUID, Pending>();
        private final LongSupplier clock;
        private final long windowMillis;

        public PendingConfirmations() {
            this(System::currentTimeMillis, CONFIRM_WINDOW_MILLIS);
        }

        public PendingConfirmations(LongSupplier clock, long windowMillis) {
            this.clock = clock;
            this.windowMillis = windowMillis;
        }

        /**
         * Returns {@code true} if the payer already has a matching unexpired pending payment (and consumes it);
         * otherwise stores a new pending payment and returns {@code false}.
         */
        public boolean confirmOrArm(UUID payer, UUID recipient, BigDecimal amount) {
            long now = clock.getAsLong();
            Pending existing = byPayer.get(payer);
            if (existing != null && now < existing.expiresAt
                    && existing.recipient.equals(recipient)
                    && existing.amount.compareTo(amount) == 0) {
                byPayer.remove(payer);
                return true;
            }
            byPayer.put(payer, new Pending(recipient, amount, now + windowMillis));
            return false;
        }

        public void clear(UUID payer) {
            byPayer.remove(payer);
        }
    }

    private static final Object LOCK = new Object();
    private final Path file;

    public NativeEconomy(Path dataDirectory) {
        this.file = dataDirectory.resolve("economy.properties");
    }

    public static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.startsWith("$")) s = s.substring(1);
        BigDecimal mult = BigDecimal.ONE;
        if (s.endsWith("k")) {
            mult = new BigDecimal("1000");
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = new BigDecimal("1000000");
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("b")) {
            mult = new BigDecimal("1000000000");
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("t")) {
            mult = new BigDecimal("1000000000000");
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return null;
        try {
            BigDecimal base = new BigDecimal(s).multiply(mult).setScale(2, RoundingMode.HALF_UP);
            if (base.compareTo(BigDecimal.ZERO) < 0 || base.compareTo(MAX_BALANCE) > 0) {
                return null;
            }
            return base;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public static String format(BigDecimal amount) {
        BigDecimal safe = amount == null ? ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.US, "$%,.2f", safe);
    }

    @Override
    public String providerName() {
        return "Allium";
    }

    @Override
    public boolean supportsDecimals() {
        return true;
    }

    @Override
    public String formatMoney(BigDecimal amount) {
        return format(amount);
    }

    public void recordName(UUID id, String name) {
        if (id == null || name == null || name.isEmpty()) return;
        synchronized (LOCK) {
            try {
                Properties props = read();
                if (!name.equals(props.getProperty("name." + id))) {
                    props.setProperty("name." + id, name);
                    save(props);
                }
            } catch (IOException ignored) {
            }
        }
    }

    public BigDecimal startingBalance() {
        synchronized (LOCK) {
            try {
                return readStartingBalance(read());
            } catch (IOException failure) {
                return ZERO;
            }
        }
    }

    public BigDecimal balance(UUID id) {
        synchronized (LOCK) {
            try {
                Properties props = read();
                return readBalance(props, id);
            } catch (IOException failure) {
                return ZERO;
            }
        }
    }

    public UUID findKnownId(String name) {
        if (name == null || name.isEmpty()) return null;
        synchronized (LOCK) {
            try {
                Properties props = read();
                for (String key : props.stringPropertyNames()) {
                    if (!key.startsWith("name.")) continue;
                    String stored = props.getProperty(key);
                    if (name.equalsIgnoreCase(stored)) {
                        try {
                            return UUID.fromString(key.substring("name.".length()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                return null;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public String nameOf(UUID id) {
        synchronized (LOCK) {
            try {
                String n = read().getProperty("name." + id);
                return n == null || n.isEmpty() ? id.toString() : n;
            } catch (IOException failure) {
                return id.toString();
            }
        }
    }

    public boolean setBalance(UUID id, String name, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0 || amount.compareTo(MAX_BALANCE) > 0) {
            return false;
        }
        synchronized (LOCK) {
            try {
                Properties props = read();
                props.setProperty("bal." + id, amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                save(props);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public BigDecimal addBalance(UUID id, String name, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) <= 0) return null;
        synchronized (LOCK) {
            try {
                Properties props = read();
                BigDecimal next = readBalance(props, id).add(delta).setScale(2, RoundingMode.HALF_UP);
                if (next.compareTo(MAX_BALANCE) > 0) return null;
                props.setProperty("bal." + id, next.toPlainString());
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                save(props);
                return next;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public BigDecimal takeBalance(UUID id, String name, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) <= 0) return null;
        synchronized (LOCK) {
            try {
                Properties props = read();
                BigDecimal current = readBalance(props, id);
                if (current.compareTo(delta) < 0) return null;
                BigDecimal next = current.subtract(delta).setScale(2, RoundingMode.HALF_UP);
                props.setProperty("bal." + id, next.toPlainString());
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                save(props);
                return next;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public BigDecimal resetBalance(UUID id, String name) {
        synchronized (LOCK) {
            try {
                Properties props = read();
                BigDecimal start = readStartingBalance(props);
                props.setProperty("bal." + id, start.toPlainString());
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                save(props);
                return start;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public boolean transfer(UUID from, String fromName, UUID to, String toName, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return false;
        synchronized (LOCK) {
            try {
                Properties props = read();
                BigDecimal fromBal = readBalance(props, from);
                if (fromBal.compareTo(amount) < 0) return false;
                BigDecimal toBal = readBalance(props, to).add(amount).setScale(2, RoundingMode.HALF_UP);
                if (toBal.compareTo(MAX_BALANCE) > 0) return false;
                props.setProperty("bal." + from, fromBal.subtract(amount).setScale(2, RoundingMode.HALF_UP).toPlainString());
                props.setProperty("bal." + to, toBal.toPlainString());
                if (fromName != null && !fromName.isEmpty()) props.setProperty("name." + from, fromName);
                if (toName != null && !toName.isEmpty()) props.setProperty("name." + to, toName);
                save(props);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public boolean acceptsPayments(UUID id) {
        synchronized (LOCK) {
            try {
                return !Boolean.parseBoolean(read().getProperty("paydisabled." + id, "false"));
            } catch (IOException failure) {
                return true;
            }
        }
    }

    public boolean setAcceptsPayments(UUID id, String name, boolean accepts) {
        synchronized (LOCK) {
            try {
                Properties props = read();
                if (accepts) props.remove("paydisabled." + id);
                else props.setProperty("paydisabled." + id, "true");
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                save(props);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public boolean requiresPayConfirm(UUID id) {
        synchronized (LOCK) {
            try {
                return Boolean.parseBoolean(read().getProperty("payconfirm." + id, "false"));
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public boolean setRequiresPayConfirm(UUID id, String name, boolean confirm) {
        synchronized (LOCK) {
            try {
                Properties props = read();
                if (confirm) props.setProperty("payconfirm." + id, "true");
                else props.remove("payconfirm." + id);
                if (name != null && !name.isEmpty()) props.setProperty("name." + id, name);
                save(props);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public List<AccountEntry> topAccounts() {
        synchronized (LOCK) {
            try {
                Properties props = read();
                List<AccountEntry> entries = new ArrayList<AccountEntry>();
                for (String key : props.stringPropertyNames()) {
                    if (!key.startsWith("bal.")) continue;
                    String rawId = key.substring("bal.".length());
                    UUID id;
                    try {
                        id = UUID.fromString(rawId);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    BigDecimal bal = parseAmount(props.getProperty(key));
                    if (bal == null) bal = ZERO;
                    String name = props.getProperty("name." + id, rawId);
                    entries.add(new AccountEntry(id, name, bal));
                }
                Collections.sort(entries, new Comparator<AccountEntry>() {
                    @Override
                    public int compare(AccountEntry a, AccountEntry b) {
                        int c = b.balance.compareTo(a.balance);
                        return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
                    }
                });
                return entries;
            } catch (IOException failure) {
                return Collections.emptyList();
            }
        }
    }

    public List<String> knownNames() {
        synchronized (LOCK) {
            try {
                Properties props = read();
                List<String> names = new ArrayList<String>();
                for (String key : props.stringPropertyNames()) {
                    if (key.startsWith("name.")) {
                        String n = props.getProperty(key);
                        if (n != null && !n.isEmpty()) names.add(n);
                    }
                }
                Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                return names;
            } catch (IOException failure) {
                return Collections.emptyList();
            }
        }
    }

    private static BigDecimal readStartingBalance(Properties props) {
        BigDecimal parsed = parseAmount(props.getProperty("starting-balance"));
        return parsed == null ? ZERO : parsed;
    }

    private static BigDecimal readBalance(Properties props, UUID id) {
        String raw = props.getProperty("bal." + id);
        if (raw == null) return readStartingBalance(props);
        BigDecimal parsed = parseAmount(raw);
        return parsed == null ? ZERO : parsed;
    }

    private Properties read() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException missing) {
            return props;
        }
        return props;
    }

    private void save(Properties props) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "economy-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(writer, null);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
