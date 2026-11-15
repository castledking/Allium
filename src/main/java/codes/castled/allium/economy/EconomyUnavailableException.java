package codes.castled.allium.economy;

/** Vault primitive reads cannot represent unavailable storage as an ordinary zero/false. */
public final class EconomyUnavailableException extends RuntimeException {
    public EconomyUnavailableException(String message){super(message);}
    public EconomyUnavailableException(String message,Throwable cause){super(message,cause);}
}
