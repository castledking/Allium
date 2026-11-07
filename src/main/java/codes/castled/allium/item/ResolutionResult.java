package codes.castled.allium.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed outcome of item resolution.
 *
 * <p>Guarantees that {@link ResolutionStatus#RESOLVED} never contains {@code null}
 * or {@link Material#AIR}, and distinguishes unknown IDs from providers that are
 * absent, still loading, or failed.
 */
public final class ResolutionResult {

    private final ResolutionStatus status;
    private final ProviderKey requestedKey;
    @Nullable
    private final ItemStack itemStack;
    private final String diagnosticMessage;

    private ResolutionResult(
            ResolutionStatus status,
            ProviderKey requestedKey,
            @Nullable ItemStack itemStack,
            String diagnosticMessage
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.requestedKey = Objects.requireNonNull(requestedKey, "requestedKey");
        if (status == ResolutionStatus.RESOLVED) {
            if (itemStack == null || itemStack.getType() == Material.AIR || itemStack.getType().isAir()) {
                throw new IllegalArgumentException("Resolved ItemStack must not be null or AIR for " + requestedKey);
            }
        }
        this.itemStack = itemStack;
        this.diagnosticMessage = Objects.requireNonNull(diagnosticMessage, "diagnosticMessage");
    }

    public static ResolutionResult resolved(ProviderKey key, ItemStack itemStack) {
        return new ResolutionResult(ResolutionStatus.RESOLVED, key, itemStack, "Resolved " + key);
    }

    public static ResolutionResult unknownId(ProviderKey key, String message) {
        return new ResolutionResult(ResolutionStatus.UNKNOWN_ID, key, null, message);
    }

    public static ResolutionResult providerAbsent(ProviderKey key, String message) {
        return new ResolutionResult(ResolutionStatus.PROVIDER_ABSENT, key, null, message);
    }

    public static ResolutionResult providerLoading(ProviderKey key, String message) {
        return new ResolutionResult(ResolutionStatus.PROVIDER_LOADING, key, null, message);
    }

    public static ResolutionResult providerFailed(ProviderKey key, String message) {
        return new ResolutionResult(ResolutionStatus.PROVIDER_FAILED, key, null, message);
    }

    public static ResolutionResult rejected(ProviderKey key, String message) {
        return new ResolutionResult(ResolutionStatus.REJECTED, key, null, message);
    }

    public ResolutionStatus status() {
        return status;
    }

    public boolean isResolved() {
        return status == ResolutionStatus.RESOLVED && itemStack != null;
    }

    public ProviderKey requestedKey() {
        return requestedKey;
    }

    public Optional<ItemStack> itemStack() {
        return Optional.ofNullable(itemStack);
    }

    public String diagnosticMessage() {
        return diagnosticMessage;
    }
}
