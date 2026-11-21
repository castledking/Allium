package codes.castled.allium.worth;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * How many to sell, as Essentials reads {@code /sell <item> [-][amount][s]}: nothing (or 0) sells
 * all of the item, {@code 10} sells ten, {@code -10} all but ten, and a trailing {@code s} counts
 * stacks ({@code 2s} is two stacks, {@code -1s} all but one stack).
 */
public record SaleQuantity(int count, boolean stacks) {

    public static final SaleQuantity ALL = new SaleQuantity(0, false);

    /** The amount to sell, or why there is none. */
    public sealed interface Result permits Sell, NotEnough, MustStack {
    }

    public record Sell(int amount) implements Result {
    }

    /** Fewer than asked for, or none at all; {@code available} is how many there are. */
    public record NotEnough(int available) implements Result {
    }

    /** A trade-in-stacks item was asked for in single items. */
    public record MustStack(int stack) implements Result {
    }

    /** @throws IllegalArgumentException when {@code text} is not {@code [-]digits[s]} */
    public static SaleQuantity parse(@Nullable String text) {
        if (text == null) {
            return ALL;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        boolean stacks = value.endsWith("s");
        if (stacks) value = value.substring(0, value.length() - 1);
        boolean negative = value.startsWith("-");
        if (negative) value = value.substring(1);
        if (value.isEmpty() || value.length() > 7 || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("amount must be a whole number, such as 10, -10 or 2s");
        }
        int count = Integer.parseInt(value);
        return new SaleQuantity(negative ? -count : count, stacks);
    }

    public boolean all() {
        return count == 0;
    }

    /**
     * How many of {@code available} to sell. A trade-in-stacks item ({@code stacksOnly}) sells only
     * whole stacks: asked for all, its whole stacks sell; asked for single items, it is refused.
     */
    public Result resolve(int available, int maxStack, boolean stacksOnly) {
        int stack = Math.max(1, maxStack);
        if (stacksOnly && !stacks && count != 0) {
            return new MustStack(stack);
        }
        long amount = stacks ? (long) count * stack : count;
        if (amount < 1) amount += available;
        if (stacksOnly) amount -= amount % stack;
        if (amount < 1 || amount > available) {
            return new NotEnough(available);
        }
        return new Sell((int) amount);
    }
}
