package codes.castled.allium.item;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Central S04 item contract service for parsing, creating, identifying, matching,
 * cloning, refreshing, and serializing vanilla, Allium, and external-provider items.
 *
 * <p>Enforces:
 * <ul>
 *   <li>No silent fallback to {@link Material#AIR} or a vanilla carrier material.</li>
 *   <li>Strict separation of {@link ItemRef#behaviorId()} from {@link ItemRef#appearance()}.</li>
 *   <li>Server-owned PDC identity ({@code allium:provider}, {@code allium:item_id},
 *       {@code allium:behavior_id}, {@code allium:state_<key>}); display names, lore,
 *       and CustomModelData alone never grant item identity or behavior.</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class ItemService {

    public static final String NAMESPACE = "allium";
    public static final NamespacedKey KEY_PROVIDER = new NamespacedKey(NAMESPACE, "provider");
    public static final NamespacedKey KEY_ITEM_ID = new NamespacedKey(NAMESPACE, "item_id");
    public static final NamespacedKey KEY_BEHAVIOR_ID = new NamespacedKey(NAMESPACE, "behavior_id");
    public static final String STATE_KEY_PREFIX = "state_";

    private final Map<String, ItemProviderAdapter> providers = new ConcurrentHashMap<>();
    private final Map<String, AlliumItemDefinition> alliumDefinitions = new ConcurrentHashMap<>();
    private final Map<ProviderKey, String> appearanceToBehavior = new ConcurrentHashMap<>();
    private final Map<String, ProviderKey> behaviorPreferredAppearance = new ConcurrentHashMap<>();
    private final Function<Material, ItemStack> itemStackFactory;

    public ItemService() {
        this(ItemStack::new);
    }

    /**
     * Constructor allowing tests to supply an {@link ItemStack} factory with pre-wired mock {@link ItemMeta}.
     */
    public ItemService(Function<Material, ItemStack> itemStackFactory) {
        this.itemStackFactory = Objects.requireNonNull(itemStackFactory, "itemStackFactory");
        registerProvider(new VanillaItemProviderAdapter());
        registerProvider(new BuiltInAlliumProviderAdapter());
    }

    public void registerProvider(ItemProviderAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        providers.put(adapter.providerName().trim().toLowerCase(Locale.ROOT), adapter);
    }

    public Optional<ItemProviderAdapter> getProvider(String providerName) {
        if (providerName == null) return Optional.empty();
        return Optional.ofNullable(providers.get(providerName.trim().toLowerCase(Locale.ROOT)));
    }

    public void registerAlliumItem(AlliumItemDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        alliumDefinitions.put(definition.id(), definition);
        if (definition.behaviorId() != null) {
            appearanceToBehavior.put(definition.providerKey(), definition.behaviorId());
            behaviorPreferredAppearance.putIfAbsent(definition.behaviorId(), definition.providerKey());
        }
    }

    public Optional<AlliumItemDefinition> getAlliumItem(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(alliumDefinitions.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Binds an external or Allium appearance (e.g. {@code nexo:mob_disarmer}) to a canonical
     * Allium behavior ID (e.g. {@code allium:mob_disarmer}).
     */
    public void bindAppearanceToBehavior(ProviderKey appearance, String behaviorId) {
        Objects.requireNonNull(appearance, "appearance");
        String normalizedBehavior = ItemRef.normalizeBehaviorId(behaviorId);
        if (normalizedBehavior == null) {
            throw new IllegalArgumentException("behaviorId must not be null or blank");
        }
        appearanceToBehavior.put(appearance, normalizedBehavior);
    }

    /**
     * Configures the preferred visual appearance for a canonical behavior ID.
     */
    public void setPreferredAppearanceForBehavior(String behaviorId, ProviderKey appearance) {
        String normalizedBehavior = ItemRef.normalizeBehaviorId(behaviorId);
        if (normalizedBehavior == null) {
            throw new IllegalArgumentException("behaviorId must not be null or blank");
        }
        Objects.requireNonNull(appearance, "appearance");
        behaviorPreferredAppearance.put(normalizedBehavior, appearance);
        appearanceToBehavior.put(appearance, normalizedBehavior);
    }

    public Optional<String> resolveBoundBehaviorId(ProviderKey appearance) {
        if (appearance == null) return Optional.empty();
        return Optional.ofNullable(appearanceToBehavior.get(appearance));
    }

    /**
     * Parses a canonical reference string into an {@link ItemRef}, applying any registered
     * behavior binding and default state for that item.
     */
    public ItemRef parseRef(String rawReference) {
        ProviderKey key = ProviderKey.parse(rawReference);
        return refForKey(key);
    }

    public ItemRef refForKey(ProviderKey key) {
        Objects.requireNonNull(key, "key");
        String boundBehavior = appearanceToBehavior.get(key);
        Map<String, String> defaultState = Collections.emptyMap();

        if (key.isAllium()) {
            AlliumItemDefinition def = alliumDefinitions.get(key.id());
            if (def != null) {
                if (boundBehavior == null) {
                    boundBehavior = def.behaviorId();
                }
                defaultState = def.defaultState();
            } else if (boundBehavior == null) {
                boundBehavior = key.asString();
            }
        } else if (boundBehavior != null && boundBehavior.startsWith(ProviderKey.ALLIUM + ":")) {
            String localId = boundBehavior.substring((ProviderKey.ALLIUM + ":").length());
            AlliumItemDefinition def = alliumDefinitions.get(localId);
            if (def != null) {
                defaultState = def.defaultState();
            }
        }

        return new ItemRef(key, boundBehavior, 1, defaultState);
    }

    /**
     * Creates an {@link ItemStack} for a canonical behavior ID using its preferred
     * configured appearance provider (or {@code allium:<id>} by default).
     */
    public ResolutionResult createForBehavior(String behaviorId, int amount) {
        String normalized = ItemRef.normalizeBehaviorId(behaviorId);
        if (normalized == null) {
            throw new IllegalArgumentException("behaviorId must not be null or blank");
        }
        ProviderKey preferred = behaviorPreferredAppearance.getOrDefault(
                normalized,
                ProviderKey.parse(normalized)
        );
        ItemRef ref = refForKey(preferred).withBehaviorId(normalized).withAmount(amount);
        return create(ref);
    }

    /**
     * Creates an {@link ItemStack} for the given {@link ItemRef}, checking provider readiness
     * and stamping authoritative PDC identity, behavior ID, and state metadata.
     */
    public ResolutionResult create(ItemRef ref) {
        Objects.requireNonNull(ref, "ref");
        ProviderKey appearance = ref.appearance();
        ItemProviderAdapter adapter = providers.get(appearance.provider());

        if (adapter == null || adapter.status() == ProviderStatus.ABSENT) {
            return ResolutionResult.providerAbsent(
                    appearance,
                    "Item provider '" + appearance.provider() + "' is not installed or enabled");
        }

        if (adapter.status() == ProviderStatus.LOADING) {
            return ResolutionResult.providerLoading(
                    appearance,
                    "Item provider '" + appearance.provider() + "' is still loading its item registry");
        }

        if (adapter.status() == ProviderStatus.FAILED) {
            return ResolutionResult.providerFailed(
                    appearance,
                    "Item provider '" + appearance.provider() + "' failed to initialize");
        }

        ResolutionResult baseResult = adapter.createItem(appearance.id());
        if (!baseResult.isResolved()) {
            return baseResult;
        }

        ItemStack stack = baseResult.itemStack().orElseThrow();
        if (stack.getType() == Material.AIR || stack.getType().isAir()) {
            return ResolutionResult.unknownId(appearance, "Provider returned AIR for " + appearance);
        }

        stack.setAmount(ref.amount());

        // Determine effective behaviorId and default state if not explicitly set on ref
        String effectiveBehaviorId = ref.behaviorId().orElseGet(() -> appearanceToBehavior.get(appearance));
        Map<String, String> mergedState = new LinkedHashMap<>();
        if (effectiveBehaviorId != null && effectiveBehaviorId.startsWith(ProviderKey.ALLIUM + ":")) {
            String localBehaviorName = effectiveBehaviorId.substring((ProviderKey.ALLIUM + ":").length());
            AlliumItemDefinition def = alliumDefinitions.get(localBehaviorName);
            if (def != null) {
                mergedState.putAll(def.defaultState());
            }
        }
        mergedState.putAll(ref.stateData());

        stampMetadata(stack, appearance, effectiveBehaviorId, mergedState);
        return ResolutionResult.resolved(appearance, stack);
    }

    /**
     * Identifies an {@link ItemStack} as a canonical {@link ItemRef}.
     *
     * <p>Never trusts display name, lore, or CustomModelData alone for custom item or
     * behavior identification; requires server-owned PDC or an active provider adapter match.
     */
    public Optional<ItemRef> identify(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir()) {
            return Optional.empty();
        }

        ProviderKey appearance = null;
        String behaviorId = null;
        Map<String, String> state = new LinkedHashMap<>();

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc != null) {
                String storedProvider = pdc.get(KEY_PROVIDER, PersistentDataType.STRING);
                String storedId = pdc.get(KEY_ITEM_ID, PersistentDataType.STRING);
                if (storedProvider != null && !storedProvider.isBlank()
                        && storedId != null && !storedId.isBlank()) {
                    appearance = ProviderKey.of(storedProvider, storedId);
                }
                String storedBehavior = pdc.get(KEY_BEHAVIOR_ID, PersistentDataType.STRING);
                if (storedBehavior != null && !storedBehavior.isBlank()) {
                    behaviorId = ItemRef.normalizeBehaviorId(storedBehavior);
                }
                for (NamespacedKey key : pdc.getKeys()) {
                    if (NAMESPACE.equals(key.getNamespace()) && key.getKey().startsWith(STATE_KEY_PREFIX)) {
                        String stateName = key.getKey().substring(STATE_KEY_PREFIX.length());
                        String val = pdc.get(key, PersistentDataType.STRING);
                        if (!stateName.isEmpty() && val != null) {
                            state.put(stateName, val);
                        }
                    }
                }
            }
        }

        // If not stamped in PDC, query external provider adapters (never vanilla/allium by visual heuristics)
        if (appearance == null) {
            for (ItemProviderAdapter adapter : providers.values()) {
                String pName = adapter.providerName();
                if (ProviderKey.MINECRAFT.equals(pName) || ProviderKey.ALLIUM.equals(pName)) {
                    continue;
                }
                if (adapter.status() == ProviderStatus.READY) {
                    Optional<String> externalId = adapter.identifyItemId(stack);
                    if (externalId.isPresent()) {
                        appearance = ProviderKey.of(pName, externalId.get());
                        break;
                    }
                }
            }
        }

        if (appearance == null) {
            String vanillaId = stack.getType().name().toLowerCase(Locale.ROOT);
            appearance = ProviderKey.minecraft(vanillaId);
        }

        // Only bind behavior if appearance is non-vanilla or explicitly stamped in server-owned PDC
        if (behaviorId == null && !appearance.isVanilla()) {
            behaviorId = appearanceToBehavior.get(appearance);
        }

        return Optional.of(new ItemRef(appearance, behaviorId, stack.getAmount(), state));
    }

    /**
     * Returns the authoritative Allium behavior ID (e.g. {@code "allium:mob_disarmer"})
     * for the given stack, regardless of which appearance provider supplied its model.
     */
    public Optional<String> identifyBehavior(@Nullable ItemStack stack) {
        return identify(stack).flatMap(ItemRef::behaviorId);
    }

    /**
     * Strictly checks whether {@code stack} matches {@code target}.
     *
     * <p>A provider or Allium item <b>never</b> matches as its vanilla carrier material,
     * and a vanilla item <b>never</b> matches a provider/Allium reference even if renamed
     * or given matching CustomModelData.
     */
    public boolean matches(@Nullable ItemStack stack, ItemRef target) {
        Objects.requireNonNull(target, "target");
        Optional<ItemRef> identifiedOpt = identify(stack);
        if (identifiedOpt.isEmpty()) {
            return false;
        }
        ItemRef actual = identifiedOpt.get();
        if (!actual.appearance().equals(target.appearance())) {
            return false;
        }
        if (target.behaviorId().isPresent() && !target.behaviorId().equals(actual.behaviorId())) {
            return false;
        }
        for (Map.Entry<String, String> reqState : target.stateData().entrySet()) {
            String actualVal = actual.stateData().get(reqState.getKey());
            if (!Objects.equals(reqState.getValue(), actualVal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether {@code stack} carries the given canonical behavior ID
     * (e.g. {@code "allium:mob_disarmer"}), matching across all bound appearance providers.
     */
    public boolean matchesBehavior(@Nullable ItemStack stack, String expectedBehaviorId) {
        String normalized = ItemRef.normalizeBehaviorId(expectedBehaviorId);
        if (normalized == null) {
            return false;
        }
        return identifyBehavior(stack).map(normalized::equals).orElse(false);
    }

    /**
     * Reads a persisted state value (e.g. {@code "charges"}) from the stack's server-owned PDC.
     */
    public Optional<String> getState(@Nullable ItemStack stack, String stateKey) {
        if (stack == null || stateKey == null || stateKey.isBlank()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.getPersistentDataContainer() == null) {
            return Optional.empty();
        }
        NamespacedKey key = new NamespacedKey(NAMESPACE, STATE_KEY_PREFIX + stateKey.trim().toLowerCase(Locale.ROOT));
        return Optional.ofNullable(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    /**
     * Updates a persisted state value (e.g. {@code "charges"}) in the stack's server-owned PDC.
     */
    public void setState(ItemStack stack, String stateKey, String value) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(stateKey, "stateKey");
        Objects.requireNonNull(value, "value");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc != null) {
            NamespacedKey key = new NamespacedKey(NAMESPACE, STATE_KEY_PREFIX + stateKey.trim().toLowerCase(Locale.ROOT));
            pdc.set(key, PersistentDataType.STRING, value);
            stack.setItemMeta(meta);
        }
    }

    /**
     * Clones an {@link ItemStack} with a new stack size while preserving all provider,
     * behavior, and state metadata.
     */
    public ItemStack cloneItem(ItemStack stack, int newAmount) {
        Objects.requireNonNull(stack, "stack");
        if (newAmount < 1) {
            throw new IllegalArgumentException("newAmount must be >= 1");
        }
        ItemStack copy = stack.clone();
        copy.setAmount(newAmount);
        return copy;
    }

    /**
     * Rebuilds an item's visual stack from {@code newAppearance} while preserving its
     * authoritative {@code behaviorId}, stack size, and all persisted {@code allium:state_*} values.
     */
    public ResolutionResult swapOrRefreshAppearance(ItemStack existingStack, ProviderKey newAppearance) {
        Objects.requireNonNull(existingStack, "existingStack");
        Objects.requireNonNull(newAppearance, "newAppearance");
        ItemRef current = identify(existingStack)
                .orElseThrow(() -> new IllegalArgumentException("Cannot refresh empty or AIR stack"));
        ItemRef updatedRef = new ItemRef(
                newAppearance,
                current.behaviorId().orElseGet(() -> appearanceToBehavior.get(newAppearance)),
                existingStack.getAmount(),
                current.stateData()
        );
        return create(updatedRef);
    }

    private void stampMetadata(
            ItemStack stack,
            ProviderKey appearance,
            @Nullable String behaviorId,
            Map<String, String> stateData
    ) {
        if (appearance.isVanilla() && behaviorId == null && stateData.isEmpty()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc != null) {
            if (!appearance.isVanilla()) {
                pdc.set(KEY_PROVIDER, PersistentDataType.STRING, appearance.provider());
                pdc.set(KEY_ITEM_ID, PersistentDataType.STRING, appearance.id());
            }
            if (behaviorId != null && !behaviorId.isBlank()) {
                pdc.set(KEY_BEHAVIOR_ID, PersistentDataType.STRING, behaviorId);
            }
            for (Map.Entry<String, String> entry : stateData.entrySet()) {
                NamespacedKey stateKey = new NamespacedKey(NAMESPACE, STATE_KEY_PREFIX + entry.getKey());
                pdc.set(stateKey, PersistentDataType.STRING, entry.getValue());
            }
        }
        stack.setItemMeta(meta);
    }

    private final class VanillaItemProviderAdapter implements ItemProviderAdapter {

        @Override
        public String providerName() {
            return ProviderKey.MINECRAFT;
        }

        @Override
        public ProviderStatus status() {
            return ProviderStatus.READY;
        }

        @Override
        public ResolutionResult createItem(String id) {
            ProviderKey key = ProviderKey.minecraft(id);
            Material material = resolveVanillaMaterial(id);
            if (material == null || material == Material.AIR || material.isAir()) {
                return ResolutionResult.unknownId(key, "Unknown or disallowed vanilla material: '" + id + "'");
            }
            return ResolutionResult.resolved(key, itemStackFactory.apply(material));
        }

        @Override
        public Optional<String> identifyItemId(ItemStack stack) {
            if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir()) {
                return Optional.empty();
            }
            return Optional.of(stack.getType().name().toLowerCase(Locale.ROOT));
        }

        @Nullable
        private Material resolveVanillaMaterial(String id) {
            if (id == null || id.isBlank()) {
                return null;
            }
            // Reject any legacy prefix or namespace inside local vanilla id
            if (id.contains(":")) {
                return null;
            }
            try {
                return Material.valueOf(id.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private final class BuiltInAlliumProviderAdapter implements ItemProviderAdapter {

        @Override
        public String providerName() {
            return ProviderKey.ALLIUM;
        }

        @Override
        public ProviderStatus status() {
            return ProviderStatus.READY;
        }

        @Override
        public ResolutionResult createItem(String id) {
            ProviderKey key = ProviderKey.allium(id);
            AlliumItemDefinition def = alliumDefinitions.get(id.trim().toLowerCase(Locale.ROOT));
            if (def == null) {
                return ResolutionResult.unknownId(key, "Unknown Allium item: '" + key + "'");
            }
            ItemStack stack = itemStackFactory.apply(def.carrierMaterial());
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (def.customModelData() != null) {
                    meta.setCustomModelData(def.customModelData());
                }
                if (def.displayName() != null) {
                    meta.setDisplayName(def.displayName());
                }
                stack.setItemMeta(meta);
            }
            return ResolutionResult.resolved(key, stack);
        }

        @Override
        public Optional<String> identifyItemId(ItemStack stack) {
            if (stack == null || stack.getItemMeta() == null || stack.getItemMeta().getPersistentDataContainer() == null) {
                return Optional.empty();
            }
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            String provider = pdc.get(KEY_PROVIDER, PersistentDataType.STRING);
            if (!ProviderKey.ALLIUM.equals(provider)) {
                return Optional.empty();
            }
            return Optional.ofNullable(pdc.get(KEY_ITEM_ID, PersistentDataType.STRING));
        }
    }
}
