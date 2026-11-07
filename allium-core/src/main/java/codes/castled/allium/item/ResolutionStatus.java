package codes.castled.allium.item;

/**
 * Distinct outcomes of resolving an {@link ItemRef} or {@link ProviderKey}.
 */
public enum ResolutionStatus {
    RESOLVED,
    UNKNOWN_ID,
    PROVIDER_ABSENT,
    PROVIDER_LOADING,
    PROVIDER_FAILED,
    /** The source stack carried Allium identity claims that failed verification, or the
     *  operation would mint behavior the source stack never had. Nothing was changed. */
    REJECTED
}
