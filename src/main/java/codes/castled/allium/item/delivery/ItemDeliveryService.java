package codes.castled.allium.item.delivery;

import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.library.SkullProfiles;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Delivers prepared items to a player and reports a {@link DeliveryOutcome}.
 *
 * <p>Async preparation (skull profiles) completes off the server thread; the delivery then hops
 * to the server thread, revalidates that the target is still online, fires the cancellable
 * {@link ItemGiveEvent}, equips armor into empty slots when asked ({@code -e} never overwrites),
 * and inserts the rest under the {@link OverflowPolicy}. {@link OverflowPolicy#REFUSE} is decided
 * from an {@link InventoryFit} plan before anything changes.
 */
public final class ItemDeliveryService {

    private final Executor serverThread;
    private final BooleanSupplier onServerThread;
    private final Function<UUID, Player> players;
    private final PluginManager events;
    private final java.util.function.Supplier<SkullFallback> skullFallback;

    public ItemDeliveryService(Executor serverThread, BooleanSupplier onServerThread, Function<UUID, Player> players, PluginManager events) {
        this(serverThread, onServerThread, players, events, () -> SkullFallback.UNTEXTURED);
    }

    public ItemDeliveryService(Executor serverThread, BooleanSupplier onServerThread, Function<UUID, Player> players, PluginManager events,
                               java.util.function.Supplier<SkullFallback> skullFallback) {
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.onServerThread = Objects.requireNonNull(onServerThread, "onServerThread");
        this.players = Objects.requireNonNull(players, "players");
        this.events = Objects.requireNonNull(events, "events");
        this.skullFallback = Objects.requireNonNull(skullFallback, "skullFallback");
    }

    public CompletableFuture<DeliveryOutcome> deliver(CommandSender source, Player target, ItemLibrary.Prepared prepared,
                                                      boolean equip, OverflowPolicy policy) {
        UUID targetId = target.getUniqueId();
        List<CompletableFuture<SkullProfiles.Resolved>> pending = new ArrayList<>();
        for (ItemLibrary.PreparedItem item : prepared.items()) {
            if (item.skull() != null) {
                pending.add(item.skull());
            }
        }
        if (pending.isEmpty() && onServerThread.getAsBoolean()) {
            return CompletableFuture.completedFuture(finish(source, targetId, prepared, equip, policy));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> finish(source, targetId, prepared, equip, policy), serverThread);
    }

    /** A mail delivery never drops overflow. The caller journals the remainder before applying. */
    public record MailPlan(ItemStack[] before, ItemStack[] after, List<ItemStack> remaining, List<Integer> delivered) {
        public MailPlan {
            before = copy(before);
            after = copy(after);
            remaining = remaining.stream().map(ItemStack::clone).toList();
            delivered = List.copyOf(delivered);
        }
        @Override public ItemStack[] before() { return copy(before); }
        @Override public ItemStack[] after() { return copy(after); }
        @Override public List<ItemStack> remaining() { return remaining.stream().map(ItemStack::clone).toList(); }
        public int given() { return delivered.stream().mapToInt(Integer::intValue).sum(); }
    }

    /** Server thread only. Null means a plugin cancelled or changed the inventory during its callback. */
    public MailPlan planMail(CommandSender source, Player target, List<ItemStack> items) {
        if (!onServerThread.getAsBoolean()) throw new IllegalStateException("mail delivery requires the server thread");
        ItemStack[] before = copy(target.getInventory().getStorageContents());
        List<ItemStack> inserts = items.stream().map(ItemStack::clone).toList();
        ItemGiveEvent event = new ItemGiveEvent(source, target, inserts, false);
        events.callEvent(event);
        if (event.isCancelled() || !java.util.Arrays.equals(before, target.getInventory().getStorageContents())) return null;
        ItemStack[] after = copy(before);
        List<ItemStack> remaining = new ArrayList<>();
        List<Integer> delivered = new ArrayList<>();
        for (ItemStack input : inserts) {
            int left = input.getAmount();
            int cap = Math.max(1, Math.min(input.getMaxStackSize(), target.getInventory().getMaxStackSize()));
            for (int i = 0; i < after.length && left > 0; i++) {
                ItemStack slot = after[i];
                if (!isEmpty(slot) && slot.isSimilar(input)) {
                    int moved = Math.min(left, Math.max(0, cap - slot.getAmount()));
                    slot.setAmount(slot.getAmount() + moved);
                    left -= moved;
                }
            }
            for (int i = 0; i < after.length && left > 0; i++) {
                if (isEmpty(after[i])) {
                    int moved = Math.min(left, cap);
                    after[i] = input.clone();
                    after[i].setAmount(moved);
                    left -= moved;
                }
            }
            delivered.add(input.getAmount() - left);
            if (left > 0) {
                ItemStack rest = input.clone();
                rest.setAmount(left);
                remaining.add(rest);
            }
        }
        return new MailPlan(before, after, remaining, delivered);
    }

    /** Revalidates the exact storage snapshot immediately before the journalled inventory mutation. */
    public boolean applyMail(Player target, MailPlan plan) {
        if (!onServerThread.getAsBoolean()) throw new IllegalStateException("mail delivery requires the server thread");
        if (!target.isOnline() || !java.util.Arrays.equals(plan.before(), target.getInventory().getStorageContents())) return false;
        target.getInventory().setStorageContents(plan.after());
        return true;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        return java.util.Arrays.stream(items).map(s -> s == null ? null : s.clone()).toArray(ItemStack[]::new);
    }

    /** Runs on the server thread. */
    DeliveryOutcome finish(CommandSender source, UUID targetId, ItemLibrary.Prepared prepared, boolean equip, OverflowPolicy policy) {
        List<String> warnings = new ArrayList<>(prepared.warnings());
        int requested = prepared.requested();
        Player target = players.apply(targetId);
        if (target == null || !target.isOnline()) {
            return DeliveryOutcome.nothing(DeliveryOutcome.Status.TARGET_OFFLINE, requested, warnings);
        }
        List<ItemStack> stacks = new ArrayList<>();
        boolean degraded = false;
        for (ItemLibrary.PreparedItem item : prepared.items()) {
            ItemStack stack = item.stack();
            if (item.skull() != null) {
                SkullProfiles.Resolved resolved = item.skull().join();
                degraded |= resolved.degraded();
                if (stack.getItemMeta() instanceof SkullMeta skull) {
                    skull.setOwnerProfile(resolved.profile());
                    stack.setItemMeta(skull);
                }
                if (resolved.warning() != null && !warnings.contains(resolved.warning())) {
                    warnings.add(resolved.warning());
                }
            }
            stacks.add(stack);
        }
        if (degraded && skullFallback.get() == SkullFallback.REFUSE) {
            return DeliveryOutcome.nothing(DeliveryOutcome.Status.REFUSED_UNRESOLVED, requested, warnings);
        }
        ItemGiveEvent event = new ItemGiveEvent(source, target, stacks, equip);
        events.callEvent(event);
        if (event.isCancelled()) {
            return DeliveryOutcome.nothing(DeliveryOutcome.Status.CANCELLED_BY_EVENT, requested, warnings);
        }

        PlayerInventory inventory = target.getInventory();
        List<ItemStack> toEquip = new ArrayList<>();
        List<ItemStack> toInsert = new ArrayList<>();
        List<EquipmentSlot> claimed = new ArrayList<>();
        for (ItemStack stack : stacks) {
            EquipmentSlot slot = equip ? armorSlot(stack.getType()) : null;
            if (slot != null && !claimed.contains(slot) && isEmpty(inventory.getItem(slot)) && stack.getAmount() == 1) {
                claimed.add(slot);
                toEquip.add(stack);
            } else {
                toInsert.add(stack);
            }
        }

        InventoryFit.Plan plan = plan(inventory.getStorageContents(), toInsert);
        OverflowPolicy.Decision decision = policy.decide(plan);
        if (decision == OverflowPolicy.Decision.REFUSE) {
            return DeliveryOutcome.nothing(DeliveryOutcome.Status.REFUSED_FULL, requested, warnings);
        }
        int given = 0;
        for (int i = 0; i < toEquip.size(); i++) {
            inventory.setItem(claimed.get(i), toEquip.get(i));
            given += toEquip.get(i).getAmount();
        }
        int leftover = 0;
        int dropped = 0;
        if (!toInsert.isEmpty()) {
            Map<Integer, ItemStack> rest = inventory.addItem(toInsert.toArray(ItemStack[]::new));
            int notPlaced = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += toInsert.stream().mapToInt(ItemStack::getAmount).sum() - notPlaced;
            if (decision == OverflowPolicy.Decision.INSERT_THEN_DROP || (decision == OverflowPolicy.Decision.INSERT && notPlaced > 0
                    && policy == OverflowPolicy.DROP)) {
                Location at = target.getLocation();
                for (ItemStack stack : rest.values()) {
                    target.getWorld().dropItemNaturally(at, stack);
                    dropped += stack.getAmount();
                }
            } else {
                leftover = notPlaced;
            }
        }
        DeliveryOutcome.Status status = leftover > 0 ? DeliveryOutcome.Status.PARTIAL
                : degraded ? DeliveryOutcome.Status.DEGRADED : DeliveryOutcome.Status.DELIVERED;
        return new DeliveryOutcome(status, requested, given, dropped, leftover, warnings);
    }

    static InventoryFit.Plan plan(ItemStack[] storage, Collection<ItemStack> inserts) {
        List<InventoryFit.Stack<ItemStack>> slots = new ArrayList<>();
        for (ItemStack slot : storage) {
            slots.add(isEmpty(slot) ? null : new InventoryFit.Stack<>(slot, slot.getAmount(), Math.max(1, slot.getMaxStackSize())));
        }
        List<InventoryFit.Stack<ItemStack>> items = new ArrayList<>();
        for (ItemStack stack : inserts) {
            items.add(new InventoryFit.Stack<>(stack, stack.getAmount(), Math.max(1, stack.getMaxStackSize())));
        }
        return InventoryFit.plan(slots, items, ItemStack::isSimilar);
    }

    private static EquipmentSlot armorSlot(Material material) {
        EquipmentSlot slot = material.getEquipmentSlot();
        return switch (slot) {
            case HEAD, CHEST, LEGS, FEET -> slot;
            default -> null;
        };
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
