package codes.castled.allium.item.library;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.item.spec.GiveSpec;
import codes.castled.allium.item.spec.ItemReference;
import codes.castled.allium.item.spec.MetaToken;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Builds item stacks from a parsed {@link GiveSpec}: resolves each reference through
 * {@link ItemService} (so Allium and provider items get their bound behavior, state and signature)
 * or, for vanilla items with raw data, through {@link ComponentAdapter}; then applies human tokens.
 *
 * <p>Signed items carry a per-instance UUID, so an amount above 1 yields that many separate
 * single stacks, each minted on its own. Skull owners resolve asynchronously; the profile is
 * applied on the server thread at delivery.
 */
public final class ItemLibrary {

    /** Signed items are minted one per stack; this caps how many one request may mint. */
    public static final int MAX_SIGNED_STACKS = 36;

    private final ItemService items;
    private final ComponentAdapter components;
    private final SkullProfiles skulls;
    private final Function<String, Optional<Enchantment>> enchantments;

    public ItemLibrary(ItemService items, ComponentAdapter components, SkullProfiles skulls) {
        this(items, components, skulls, EnchantmentAdapter::find);
    }

    /** @param enchantments enchantment lookup by key (the server registry in production) */
    public ItemLibrary(ItemService items, ComponentAdapter components, SkullProfiles skulls,
                       Function<String, Optional<Enchantment>> enchantments) {
        this.items = Objects.requireNonNull(items, "items");
        this.components = Objects.requireNonNull(components, "components");
        this.skulls = Objects.requireNonNull(skulls, "skulls");
        this.enchantments = Objects.requireNonNull(enchantments, "enchantments");
    }

    /** A built stack; {@code skull} completes with the owner profile to apply, if any. */
    public record PreparedItem(ItemStack stack, @Nullable CompletableFuture<SkullProfiles.Resolved> skull) {
    }

    public record Prepared(List<PreparedItem> items, List<String> warnings) {
        public Prepared {
            items = List.copyOf(items);
            warnings = List.copyOf(warnings);
        }

        public int requested() {
            return items.stream().mapToInt(p -> p.stack().getAmount()).sum();
        }

        /** Whether any skull profile is still being looked up. */
        public boolean async() {
            return items.stream().anyMatch(p -> p.skull() != null && !p.skull().isDone());
        }
    }

    /**
     * @param mayGivePrivileged whether the sender may mint privileged behaviors (handcuffs, ...)
     * @throws ItemBuildException when any reference cannot be built; nothing is returned partially
     */
    public Prepared build(GiveSpec spec, boolean mayGivePrivileged) {
        int amount = spec.amount().orElse(1);
        List<PreparedItem> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ItemReference reference : spec.items()) {
            List<ItemStack> stacks = resolve(reference, amount, mayGivePrivileged);
            for (ItemStack stack : stacks) {
                CompletableFuture<SkullProfiles.Resolved> skull = applyTokens(stack, spec.tokens(), warnings);
                out.add(new PreparedItem(stack, skull));
            }
        }
        return new Prepared(out, warnings);
    }

    private List<ItemStack> resolve(ItemReference reference, int amount, boolean mayGivePrivileged) {
        ProviderKey key = reference.key();
        if (reference.raw().isPresent()) {
            return List.of(components.create(key, reference.raw().get(), amount));
        }
        ResolutionResult first = items.create(new ItemRef(key, null, 1, Map.of()));
        ItemStack sample = unwrap(first, reference);
        Optional<String> behavior = items.identify(sample).flatMap(ItemRef::behaviorId);
        if (behavior.isPresent() && items.isPrivilegedBehavior(behavior.get()) && !mayGivePrivileged) {
            throw new ItemBuildException.Privileged(behavior.get(), reference.text());
        }
        if (behavior.isEmpty() || !items.isSignedBehavior(behavior.get())) {
            sample.setAmount(Math.min(amount, Math.max(1, sample.getMaxStackSize())));
            List<ItemStack> stacks = new ArrayList<>();
            int remaining = amount;
            ItemStack template = sample.clone();
            while (remaining > 0) {
                ItemStack stack = template.clone();
                int size = Math.min(remaining, Math.max(1, template.getMaxStackSize()));
                stack.setAmount(size);
                stacks.add(stack);
                remaining -= size;
            }
            return stacks;
        }
        if (amount > MAX_SIGNED_STACKS) {
            throw new ItemBuildException(reference.text() + " is minted one per stack; give at most " + MAX_SIGNED_STACKS + " at once");
        }
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(sample);
        for (int i = 1; i < amount; i++) {
            stacks.add(unwrap(items.create(new ItemRef(key, null, 1, Map.of())), reference));
        }
        return stacks;
    }

    private static ItemStack unwrap(ResolutionResult result, ItemReference reference) {
        if (!result.isResolved()) {
            throw new ItemBuildException.Unresolved(result.status(), result.diagnosticMessage());
        }
        return result.itemStack().orElseThrow();
    }

    private CompletableFuture<SkullProfiles.Resolved> applyTokens(ItemStack stack, List<MetaToken> tokens, List<String> warnings) {
        if (tokens.isEmpty()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            throw new ItemBuildException(stack.getType().name().toLowerCase() + " cannot carry item metadata");
        }
        CompletableFuture<SkullProfiles.Resolved> skull = null;
        for (MetaToken token : tokens) {
            String value = token.value();
            switch (token.key()) {
                case "name" -> TextTokens.name(meta, required(token), token.quoted());
                case "lore" -> TextTokens.lore(meta, required(token), token.quoted());
                case "potion" -> PotionAdapter.basePotion(meta, required(token));
                case "effect" -> PotionAdapter.effect(meta, required(token));
                case "color" -> PotionAdapter.color(meta, required(token));
                case "unbreakable" -> {
                    if (value != null) throw new ItemBuildException("unbreakable takes no value");
                    meta.setUnbreakable(true);
                }
                case "owner" -> {
                    if (!(meta instanceof SkullMeta) || stack.getType() != Material.PLAYER_HEAD) {
                        throw new ItemBuildException("owner: applies to player_head");
                    }
                    skull = skulls.resolve(required(token));
                }
                default -> {
                    Optional<Enchantment> enchantment = enchantments.apply(token.key());
                    if (enchantment.isEmpty()) {
                        throw new ItemBuildException("unknown token '" + token.key() + "'; use name, lore, owner, potion, effect, "
                                + "color, unbreakable or an enchantment such as sharpness:5");
                    }
                    String warning = EnchantmentAdapter.apply(stack, meta, enchantment.get(), required(token));
                    if (warning != null && !warnings.contains(warning)) {
                        warnings.add(warning);
                    }
                }
            }
        }
        stack.setItemMeta(meta);
        return skull;
    }

    private static String required(MetaToken token) {
        if (token.value() == null) {
            throw new ItemBuildException(token.key() + " needs a value (" + token.key() + ":...)");
        }
        return token.value();
    }
}
