package codes.castled.allium.item;

/**
 * Lifecycle readiness state of an item provider.
 */
public enum ProviderStatus {
    READY,
    ABSENT,
    LOADING,
    FAILED
}
