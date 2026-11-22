package codes.castled.allium.worth;

import codes.castled.allium.economy.Money;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a {@code /sell} (or {@code /worth}) covers: for each kind of item, how many and for how much,
 * and the storage slots it empties. Items of one kind are alike in every way ({@link ItemStack#isSimilar}),
 * as Essentials sells them. Only the 36 storage slots are sold from, never armour or the off hand.
 */
public record SalePlan(List<Line> lines, Map<Integer, ItemStack> before, Map<Integer, ItemStack> after,
                       List<ItemStack> removed, Money total, int count) {

    public enum Scope { HAND, ITEM, INVENTORY, BLOCKS }

    /** Why an item cannot be sold. */
    public enum Refusal { NOT_SELLABLE, WORTHLESS, NAMED, CUSTOM, CREATIVE, CONTAINER }

    public record Line(ItemStack sample, int amount, Money each, Money total) {
        public Material material() {
            return sample.getType();
        }
    }

    /** The plan, or why there is none. */
    public sealed interface Outcome permits Planned, Refused, Short, Stacks, Nothing {
    }

    public record Planned(SalePlan plan) implements Outcome {
    }

    public record Refused(ItemStack item, Refusal refusal) implements Outcome {
    }

    public record Short(ItemStack item, int available) implements Outcome {
    }

    public record Stacks(ItemStack item, int stack) implements Outcome {
    }

    /** Nothing in a bulk sale can be sold. */
    public record Nothing() implements Outcome {
    }

    /**
     * @param storage    the 36 storage slots
     * @param target     the item to sell (HAND and ITEM); one like it, never changed
     * @param refusal    why an item cannot be sold whatever its price, or null
     * @param multiplier the seller's price multiplier
     */
    public static Outcome plan(ItemStack[] storage, int heldSlot, Scope scope, @Nullable ItemStack target, SaleQuantity quantity,
                               WorthSettings settings, BigDecimal multiplier, Function<ItemStack, Refusal> refusal,
                               Function<ItemStack, Optional<BigDecimal>> prices) {
        boolean bulk = scope == Scope.INVENTORY || scope == Scope.BLOCKS;
        // Kinds in slot order; the held slot leads when selling the held item.
        Map<ItemStack, List<Integer>> kinds = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>();
        if (scope == Scope.HAND && heldSlot >= 0 && heldSlot < storage.length) order.add(heldSlot);
        for (int slot = 0; slot < storage.length; slot++) {
            if (slot != heldSlot || scope != Scope.HAND) order.add(slot);
        }
        for (int slot : order) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.getType().isAir() || stack.getAmount() < 1) continue;
            if (!bulk && !stack.isSimilar(target)) continue;
            if (scope == Scope.BLOCKS && !stack.getType().isBlock()) continue;
            ItemStack kind = null;
            for (ItemStack known : kinds.keySet()) {
                if (known.isSimilar(stack)) {
                    kind = known;
                    break;
                }
            }
            if (kind == null) {
                kind = stack.clone();
                kind.setAmount(1);
                kinds.put(kind, new ArrayList<>());
            }
            kinds.get(kind).add(slot);
        }
        if (!bulk && kinds.isEmpty()) {
            return new Short(target, 0);
        }
        List<Line> lines = new ArrayList<>();
        Map<Integer, ItemStack> before = new LinkedHashMap<>();
        Map<Integer, ItemStack> after = new LinkedHashMap<>();
        List<ItemStack> removed = new ArrayList<>();
        Money total = new Money(0);
        int count = 0;
        for (var kind : kinds.entrySet()) {
            ItemStack sample = kind.getKey();
            Refusal refused = refusal.apply(sample);
            BigDecimal base = prices.apply(sample).orElse(null);
            if (refused == null && base == null) refused = Refusal.NOT_SELLABLE;
            if (refused == null && base.signum() == 0) refused = Refusal.WORTHLESS;
            if (refused != null) {
                if (bulk) continue;
                return new Refused(sample, refused);
            }
            int available = 0;
            for (int slot : kind.getValue()) available += storage[slot].getAmount();
            SaleQuantity.Result result = (bulk ? SaleQuantity.ALL : quantity)
                    .resolve(available, sample.getMaxStackSize(), settings.stacksOnly(sample.getType()));
            if (!(result instanceof SaleQuantity.Sell sell)) {
                if (bulk) continue;
                return result instanceof SaleQuantity.MustStack must ? new Stacks(sample, must.stack()) : new Short(sample, available);
            }
            Money each = Money.exact(base.multiply(multiplier).setScale(2, RoundingMode.HALF_UP));
            if (each.minor() < 1) {
                if (bulk) continue;
                return new Refused(sample, Refusal.WORTHLESS);
            }
            Money lineTotal = new Money(Math.multiplyExact(each.minor(), (long) sell.amount()));
            int left = sell.amount();
            for (int slot : kind.getValue()) {
                if (left == 0) break;
                ItemStack stack = storage[slot];
                int take = Math.min(left, stack.getAmount());
                left -= take;
                before.put(slot, stack.clone());
                ItemStack rest = null;
                if (take < stack.getAmount()) {
                    rest = stack.clone();
                    rest.setAmount(stack.getAmount() - take);
                }
                after.put(slot, rest);
                ItemStack gone = stack.clone();
                gone.setAmount(take);
                removed.add(gone);
            }
            lines.add(new Line(sample, sell.amount(), each, lineTotal));
            total = total.plus(lineTotal);
            count += sell.amount();
        }
        if (lines.isEmpty()) {
            return new Nothing();
        }
        return new Planned(new SalePlan(List.copyOf(lines), before, after, List.copyOf(removed), total, count));
    }
}
