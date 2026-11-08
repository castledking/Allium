package codes.castled.allium.api;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** The outcome of {@link AddonItems#create}: a stack, or a status and message saying why not. Never an air fallback. */
public record ItemResult(Status status, Optional<ItemStack> stack, String message) {

    public enum Status {
        CREATED,
        INVALID_REFERENCE,
        UNKNOWN_ID,
        PROVIDER_ABSENT,
        PROVIDER_LOADING,
        PROVIDER_FAILED,
        REJECTED
    }

    public boolean created() {
        return status == Status.CREATED && stack.isPresent();
    }
}
