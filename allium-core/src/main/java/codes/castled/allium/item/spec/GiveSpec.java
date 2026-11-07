package codes.castled.allium.item.spec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A parsed {@code <item-spec> [amount] [tokens | flags...]} request.
 *
 * @param items    one or more references (a comma-separated batch)
 * @param amount   the explicit amount, applied to every item in the batch
 * @param tokens   human metadata tokens, applied to every item in the batch
 * @param equip    {@code -e} / {@code -equip}: wear armor into empty slots
 * @param overflow {@code --overflow=<policy>} override, unvalidated
 */
public record GiveSpec(List<ItemReference> items, OptionalInt amount, List<MetaToken> tokens, boolean equip, Optional<String> overflow) {

    public GiveSpec {
        items = List.copyOf(items);
        tokens = List.copyOf(tokens);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(overflow, "overflow");
    }
}
