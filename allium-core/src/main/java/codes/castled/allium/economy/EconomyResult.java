package codes.castled.allium.economy;

import java.util.UUID;

/** Storage failure and indeterminate commit outcomes cannot masquerade as empty balances. */
public final class EconomyResult {
    private EconomyResult() {}
    public sealed interface Balance permits Found, Missing, Unavailable {}
    public record Found(Money book, Money reserved, long version) implements Balance {
        public Money available() { return book.minus(reserved); }
    }
    public record Missing() implements Balance {}
    public record Unavailable(String diagnostic) implements Balance, Mutation {}
    public sealed interface Mutation permits Committed, Rejected, Unknown, Unavailable {}
    public record Committed(UUID operationId, Found source, Found recipient, boolean replay) implements Mutation {}
    public record Rejected(UUID operationId, String reason, Found source, Found recipient) implements Mutation {
        public Rejected(UUID operationId,String reason){this(operationId,reason,null,null);}
    }
    public record Unknown(UUID operationId, String diagnostic) implements Mutation {}
}
