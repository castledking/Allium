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
import java.util.OptionalInt;
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

    private LegacyItemDatabase database;
    public void database(LegacyItemDatabase database){this.database=database;}
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

    /** Resolves legacy item aliases (if loaded) to the canonical provider key used for build and policy checks. */
    public ProviderKey canonicalKey(ItemReference reference) {
        if (database == null) {
            return reference.key();
        }
        String raw = reference.text();
        int open = codes.castled.allium.item.spec.ItemSpecLexer.indexOfOpenGroup(raw);
        String alias = database.lookup(open < 0 ? raw : raw.substring(0, open));
        if (alias == null) {
            return reference.key();
        }
        try {
            var expanded = new codes.castled.allium.item.spec.ItemSpecParser(true)
                    .parse(alias + (reference.raw().isPresent() ? reference.raw().get().text() : ""));
            return expanded.items().get(0).key();
        } catch (codes.castled.allium.item.spec.ItemSpecException ignored) {
            return reference.key();
        }
    }

    /**
     * @param mayGivePrivileged whether the sender may mint privileged behaviors (handcuffs, ...)
     * @throws ItemBuildException when any reference cannot be built; nothing is returned partially
     */
    public Prepared build(GiveSpec spec,boolean mayGivePrivileged){return build(spec,mayGivePrivileged,false);}
    public Prepared build(GiveSpec spec, boolean mayGivePrivileged,boolean allowUnsafe) {
        return build(spec, mayGivePrivileged, allowUnsafe, OptionalInt.of(1));
    }

    public Prepared buildForGive(GiveSpec spec, boolean mayGivePrivileged, boolean allowUnsafe) {
        return build(spec, mayGivePrivileged, allowUnsafe, OptionalInt.empty());
    }

    private Prepared build(GiveSpec spec, boolean mayGivePrivileged, boolean allowUnsafe,
                           OptionalInt defaultAmount) {
        OptionalInt amount = spec.amount().isPresent() ? spec.amount() : defaultAmount;
        List<PreparedItem> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ItemReference reference : spec.items()) {
            var localTokens=new ArrayList<MetaToken>();
            OptionalInt count = reference.amount().isPresent() ? reference.amount() : amount;
            if(database!=null){String raw=reference.text();int open=codes.castled.allium.item.spec.ItemSpecLexer.indexOfOpenGroup(raw);String alias=database.lookup(open<0?raw:raw.substring(0,open));if(alias!=null){if(reference.raw().isPresent()&&alias.contains(";"))throw new ItemBuildException("raw data cannot override a complex item alias");var expanded=new codes.castled.allium.item.spec.ItemSpecParser(true).parse(alias+(reference.raw().isPresent()?reference.raw().get().text():""));reference=expanded.items().get(0);localTokens.addAll(expanded.tokens());}}
            localTokens.addAll(spec.tokens());
            List<ItemStack> stacks = resolve(reference, count, mayGivePrivileged);
            for (ItemStack stack : stacks) {
                if(reference.raw().isPresent())for(String notice:EnchantmentAdapter.validateRaw(stack,allowUnsafe))if(!warnings.contains(notice))warnings.add(notice);
                CompletableFuture<SkullProfiles.Resolved> skull = applyTokens(stack, localTokens, warnings,allowUnsafe);
                out.add(new PreparedItem(stack, skull));
            }
        }
        return new Prepared(out, warnings);
    }

    private List<ItemStack> resolve(ItemReference reference, OptionalInt requested, boolean mayGivePrivileged) {
        ProviderKey key = reference.key();
        if (reference.raw().isPresent()) {
            return split(components.create(key, reference.raw().get(), 1), requested);
        }
        ResolutionResult first = items.create(new ItemRef(key, null, 1, Map.of()));
        ItemStack sample = unwrap(first, reference);
        Optional<String> behavior = items.identify(sample).flatMap(ItemRef::behaviorId);
        if (behavior.isPresent() && items.isPrivilegedBehavior(behavior.get()) && !mayGivePrivileged) {
            throw new ItemBuildException.Privileged(behavior.get(), reference.text());
        }
        if (behavior.isEmpty() || !items.isSignedBehavior(behavior.get())) {
            return split(sample, requested);
        }
        int amount = requested.orElse(1);
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

    private static List<ItemStack> split(ItemStack template, OptionalInt requested) {
        int limit = Math.max(1, template.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = requested.orElse(limit);
        while (remaining > 0) {
            int amount = Math.min(remaining, limit);
            ItemStack stack = template.clone();
            stack.setAmount(amount);
            stacks.add(stack);
            remaining -= amount;
        }
        return stacks;
    }

    private static ItemStack unwrap(ResolutionResult result, ItemReference reference) {
        if (!result.isResolved()) {
            throw new ItemBuildException.Unresolved(result.status(), result.diagnosticMessage());
        }
        return result.itemStack().orElseThrow();
    }

    private CompletableFuture<SkullProfiles.Resolved> applyTokens(ItemStack stack, List<MetaToken> tokens, List<String> warnings,boolean allowUnsafe) {
        if (tokens.isEmpty()) {
            return null;
        }
        for (MetaToken token : tokens) {
            if (token.key().equals("variant") && !items.applyVariant(stack, required(token).toLowerCase(java.util.Locale.ROOT))) {
                throw new ItemBuildException("unknown or unsupported item variant: " + required(token));
            }
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            throw new ItemBuildException(stack.getType().name().toLowerCase() + " cannot carry item metadata");
        }
        CompletableFuture<SkullProfiles.Resolved> skull = null;
        for (MetaToken token : tokens) {
            String value = token.value();
            switch (token.key()) {
                case "variant" -> { /* Applied before acquiring the item meta. */ }
                case "name" -> TextTokens.name(meta, required(token), token.quoted());
                case "lore" -> TextTokens.lore(meta, required(token), token.quoted());
                case "spawner" -> {
                    if(!(meta instanceof org.bukkit.inventory.meta.BlockStateMeta block)||!(block.getBlockState() instanceof org.bukkit.block.CreatureSpawner spawner))throw new ItemBuildException("spawner: needs a spawner item");
                    try{var type=org.bukkit.entity.EntityType.valueOf(required(token).toUpperCase(java.util.Locale.ROOT).replace("MINECRAFT:",""));if(!type.isSpawnable()||!type.isAlive())throw new IllegalArgumentException();spawner.setSpawnedType(type);block.setBlockState(spawner);}catch(IllegalArgumentException invalid){throw new ItemBuildException("unknown or unsupported spawner entity: "+required(token));}
                }
                case "legacy-effect" -> PotionAdapter.legacyEffect(meta,required(token),stack.getType());
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
                    String warning = EnchantmentAdapter.apply(stack, meta, enchantment.get(), required(token),allowUnsafe);
                    if (warning != null && !warnings.contains(warning)) {
                        warnings.add(warning);
                    }
                }
            }
        }
        if (tokens.stream().anyMatch(t -> t.key().equals("effect"))) PotionAdapter.nameFromEffects(meta, stack.getType());
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
