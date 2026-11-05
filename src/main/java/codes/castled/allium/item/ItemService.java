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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Central S04/S05 item contract service for parsing, creating, signing, identifying,
 * matching, cloning, refreshing, and serializing vanilla, Allium, and external-provider items.
 */
@SuppressWarnings("deprecation")
public final class ItemService {

    public static final String NAMESPACE = "allium";
    public static final String LEGACY_CARDS_NAMESPACE = "alliumcards";

    public static final NamespacedKey KEY_SCHEMA_VERSION = new NamespacedKey(NAMESPACE, "schema_version");
    public static final NamespacedKey KEY_PROVIDER = new NamespacedKey(NAMESPACE, "provider");
    public static final NamespacedKey KEY_ITEM_ID = new NamespacedKey(NAMESPACE, "item_id");
    public static final NamespacedKey KEY_BEHAVIOR_ID = new NamespacedKey(NAMESPACE, "behavior_id");
    public static final NamespacedKey KEY_INSTANCE_UUID = new NamespacedKey(NAMESPACE, "instance_uuid");
    public static final NamespacedKey KEY_SIGNATURE = new NamespacedKey(NAMESPACE, "signature");
    public static final NamespacedKey KEY_LEGACY_CUSTOM_ITEM_ID = new NamespacedKey(NAMESPACE, "custom_item_id");
    public static final String STATE_KEY_PREFIX = "state_";

    private final Map<String, ItemProviderAdapter> providers = new ConcurrentHashMap<>();
    private final Map<String, AlliumItemDefinition> alliumDefinitions = new ConcurrentHashMap<>();
    private final Map<ProviderKey, String> appearanceToBehavior = new ConcurrentHashMap<>();
    private final Map<String, ProviderKey> behaviorPreferredAppearance = new ConcurrentHashMap<>();
    private final Set<String> privilegedBehaviors = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedDiagnostics = ConcurrentHashMap.newKeySet();

    private final Function<Material, ItemStack> itemStackFactory;
    private final ItemHmacSigner hmacSigner;
    private final Consumer<String> diagnosticLogger;

    public ItemService() {
        this(ItemStack::new, ItemHmacSigner.randomEphemeral(), msg -> {});
    }

    public ItemService(ItemHmacSigner hmacSigner, Consumer<String> diagnosticLogger) {
        this(ItemStack::new, hmacSigner, diagnosticLogger);
    }

    public ItemService(Function<Material, ItemStack> itemStackFactory) {
        this(itemStackFactory, ItemHmacSigner.randomEphemeral(), msg -> {});
    }

    public ItemService(
            Function<Material, ItemStack> itemStackFactory,
            ItemHmacSigner hmacSigner,
            Consumer<String> diagnosticLogger
    ) {
        this.itemStackFactory = Objects.requireNonNull(itemStackFactory, "itemStackFactory");
        this.hmacSigner = Objects.requireNonNull(hmacSigner, "hmacSigner");
        this.diagnosticLogger = Objects.requireNonNull(diagnosticLogger, "diagnosticLogger");
        registerProvider(new VanillaItemProviderAdapter());
        registerProvider(new BuiltInAlliumProviderAdapter());
    }

    public ItemHmacSigner getHmacSigner() {
        return hmacSigner;
    }

    public void resetDiagnosticsOnReload() {
        warnedDiagnostics.clear();
        ProviderKey.resetDeprecationWarnings();
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
            if (definition.privileged()) {
                privilegedBehaviors.add(definition.behaviorId());
            }
        }
    }

    public void registerPrivilegedBehavior(String behaviorId) {
        String normalized = ItemRef.normalizeBehaviorId(behaviorId);
        if (normalized != null) {
            privilegedBehaviors.add(normalized);
        }
    }

    public boolean isPrivilegedBehavior(@Nullable String behaviorId) {
        String normalized = ItemRef.normalizeBehaviorId(behaviorId);
        return normalized != null && privilegedBehaviors.contains(normalized);
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
        ProviderKey key = ProviderKey.parse(rawReference, false, this::warnDiagnosticOnce);
        return refForKey(key);
    }

    public ItemRef parseRef(String rawReference, boolean allowDeprecatedCe) {
        ProviderKey key = ProviderKey.parse(rawReference, allowDeprecatedCe, this::warnDiagnosticOnce);
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
     * and stamping authoritative PDC identity, schema version, behavior ID, HMAC signature
     * (including canonical sorted state when privileged), and state metadata.
     */
    public ResolutionResult create(ItemRef ref) {
        return createInternal(ref, null);
    }

    private ResolutionResult createInternal(ItemRef ref, @Nullable String preservedInstanceUuid) {
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

        stampMetadata(stack, ref.schemaVersion(), appearance, effectiveBehaviorId, mergedState, preservedInstanceUuid);
        return ResolutionResult.resolved(appearance, stack);
    }

    /**
     * Identifies an {@link ItemStack} as a canonical {@link ItemRef}.
     *
     * <p>Enforces:
     * <ul>
     *   <li>Display name, lore, and CustomModelData alone never grant item identity or behavior.</li>
     *   <li>External provider adapters are queried via {@link ItemProviderAdapter#identifyItemId(ItemStack)}.
     *       If an external provider's native API and Allium's PDC stamp disagree, an explicit diagnostic
     *       is logged and the ready provider's native API is authoritative for appearance.</li>
     *   <li>Privileged behaviors require a valid keyed HMAC-SHA256 signature over
     *       {@code (provider, itemId, behaviorId, instanceUuid, canonicalState)}; unsigned,
     *       unknown-key, or tampered items resolve as plain vanilla items of their carrier material
     *       and log a diagnostic (with a loud diagnostic for unknown key IDs).</li>
     * </ul>
     */
    public Optional<ItemRef> identify(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir()) {
            return Optional.empty();
        }

        String vanillaId = stack.getType().name().toLowerCase(Locale.ROOT);
        ProviderKey plainVanillaKey = ProviderKey.minecraft(vanillaId);

        int schemaVersion = ItemRef.CURRENT_SCHEMA_VERSION;
        ProviderKey pdcAppearance = null;
        String behaviorId = null;
        String instanceUuid = null;
        String signature = null;
        Map<String, String> state = new LinkedHashMap<>();

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc != null) {
                Integer storedSchema = pdc.get(KEY_SCHEMA_VERSION, PersistentDataType.INTEGER);
                if (storedSchema != null && storedSchema >= 1 && storedSchema <= ItemRef.CURRENT_SCHEMA_VERSION) {
                    schemaVersion = storedSchema;
                }
                String storedProvider = pdc.get(KEY_PROVIDER, PersistentDataType.STRING);
                String storedId = pdc.get(KEY_ITEM_ID, PersistentDataType.STRING);
                if (storedProvider != null && !storedProvider.isBlank()
                        && storedId != null && !storedId.isBlank()) {
                    try {
                        pdcAppearance = ProviderKey.of(storedProvider, storedId);
                    } catch (IllegalArgumentException ignored) {
                        pdcAppearance = null;
                    }
                }
                String storedBehavior = pdc.get(KEY_BEHAVIOR_ID, PersistentDataType.STRING);
                if (storedBehavior != null && !storedBehavior.isBlank()) {
                    behaviorId = ItemRef.normalizeBehaviorId(storedBehavior);
                }
                instanceUuid = pdc.get(KEY_INSTANCE_UUID, PersistentDataType.STRING);
                signature = pdc.get(KEY_SIGNATURE, PersistentDataType.STRING);
                state.putAll(extractStateMap(pdc));
            }
        }

        // Query external provider adapters using their native item-ID APIs
        ProviderKey nativeAppearance = null;
        for (ItemProviderAdapter adapter : providers.values()) {
            String pName = adapter.providerName();
            if (ProviderKey.MINECRAFT.equals(pName) || ProviderKey.ALLIUM.equals(pName)) {
                continue;
            }
            if (adapter.status() == ProviderStatus.READY) {
                Optional<String> externalId = adapter.identifyItemId(stack);
                if (externalId.isPresent() && !externalId.get().isBlank()) {
                    try {
                        nativeAppearance = ProviderKey.of(pName, externalId.get());
                        break;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        ProviderKey appearance;
        if (nativeAppearance != null) {
            if (pdcAppearance != null && !pdcAppearance.equals(nativeAppearance)) {
                warnDiagnosticOnce("Identity mismatch on item stack: provider API reported '"
                        + nativeAppearance + "' while Allium PDC stamp reported '" + pdcAppearance
                        + "'; resolving appearance to authoritative provider API '" + nativeAppearance + "'.");
                // If an unprivileged behavior was bound only to the old PDC appearance, re-resolve for nativeAppearance
                if (behaviorId != null && !isPrivilegedBehavior(behaviorId) && signature == null) {
                    behaviorId = appearanceToBehavior.get(nativeAppearance);
                }
            }
            appearance = nativeAppearance;
        } else if (pdcAppearance != null) {
            ItemProviderAdapter claimedAdapter = providers.get(pdcAppearance.provider());
            if (!pdcAppearance.isVanilla()
                    && !pdcAppearance.isAllium()
                    && claimedAdapter != null
                    && claimedAdapter.status() == ProviderStatus.READY
                    && claimedAdapter.hasAuthoritativeNativeIdCheck()) {
                warnDiagnosticOnce("Identity mismatch on item stack: Allium PDC stamp reported '"
                        + pdcAppearance + "' but ready provider '" + pdcAppearance.provider()
                        + "' did not recognize the stack; resolving as plain " + plainVanillaKey + ".");
                return Optional.of(new ItemRef(
                        ItemRef.CURRENT_SCHEMA_VERSION,
                        plainVanillaKey,
                        null,
                        stack.getAmount(),
                        Collections.emptyMap()
                ));
            }
            appearance = pdcAppearance;
        } else {
            appearance = plainVanillaKey;
        }

        if (behaviorId == null && !appearance.isVanilla()) {
            behaviorId = appearanceToBehavior.get(appearance);
        }

        // Verify signature whenever a privileged behavior is claimed OR a signature is present
        boolean privileged = isPrivilegedBehavior(behaviorId);
        if (privileged || signature != null) {
            ItemHmacSigner.VerificationOutcome outcome = behaviorId == null
                    ? new ItemHmacSigner.VerificationOutcome(ItemHmacSigner.VerificationStatus.MISSING, null)
                    : hmacSigner.verifyDetailed(
                            appearance.provider(),
                            appearance.id(),
                            behaviorId,
                            instanceUuid,
                            state,
                            signature
                    );
            if (!outcome.isValid()) {
                if (outcome.status() == ItemHmacSigner.VerificationStatus.UNKNOWN_KEY_ID) {
                    warnDiagnosticOnce("CRITICAL HMAC KEY MISMATCH: Item claiming behavior '" + behaviorId
                            + "' on appearance '" + appearance + "' is signed with unknown key ID '"
                            + outcome.keyId() + "' (active/retired key IDs: " + hmacSigner.keyIds()
                            + "). If you rotated keys or migrated servers, add 'retired="
                            + outcome.keyId() + ":<hexSecret>' to plugins/Allium/.item-hmac-secret; resolving as plain "
                            + plainVanillaKey);
                } else {
                    String reason = outcome.status() == ItemHmacSigner.VerificationStatus.MISSING
                            ? "missing signature"
                            : "invalid/tampered signature or state";
                    warnDiagnosticOnce("Rejected item claiming behavior '" + behaviorId
                            + "' on appearance '" + appearance + "' (" + reason + "); resolving as plain " + plainVanillaKey);
                }
                return Optional.of(new ItemRef(
                        ItemRef.CURRENT_SCHEMA_VERSION,
                        plainVanillaKey,
                        null,
                        stack.getAmount(),
                        Collections.emptyMap()
                ));
            }
        }

        return Optional.of(new ItemRef(schemaVersion, appearance, behaviorId, stack.getAmount(), state));
    }

    /**
     * Reads legacy {@code allium:custom_item_id} and {@code alliumcards} PDC tags
     * (strictly read-only for the data/world importer).
     */
    public LegacyItemMetadata readLegacyMetadata(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir()) {
            return LegacyItemMetadata.EMPTY;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.getPersistentDataContainer() == null) {
            return LegacyItemMetadata.EMPTY;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String legacyCustomId = pdc.get(KEY_LEGACY_CUSTOM_ITEM_ID, PersistentDataType.STRING);
        Optional<String> customIdOpt = (legacyCustomId != null && !legacyCustomId.isBlank())
                ? Optional.of(legacyCustomId.trim())
                : Optional.empty();

        Map<String, String> cardsTags = new LinkedHashMap<>();
        for (NamespacedKey key : pdc.getKeys()) {
            if (LEGACY_CARDS_NAMESPACE.equals(key.getNamespace())) {
                String val = pdc.get(key, PersistentDataType.STRING);
                if (val != null) {
                    cardsTags.put(key.getKey(), val);
                }
            } else if (NAMESPACE.equals(key.getNamespace()) && key.getKey().startsWith(LEGACY_CARDS_NAMESPACE)) {
                String val = pdc.get(key, PersistentDataType.STRING);
                if (val != null) {
                    cardsTags.put(key.getKey(), val);
                }
            }
        }

        if (customIdOpt.isEmpty() && cardsTags.isEmpty()) {
            return LegacyItemMetadata.EMPTY;
        }
        return new LegacyItemMetadata(customIdOpt, cardsTags);
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
     * Checks whether {@code stack} carries the given canonical behavior ID,
     * matching across all bound appearance providers.
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
     * Updates a persisted state value (e.g. {@code "charges"}) in the stack's server-owned PDC
     * and re-signs the HMAC signature if the item is privileged or signed.
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

            String storedBehavior = pdc.get(KEY_BEHAVIOR_ID, PersistentDataType.STRING);
            String existingSig = pdc.get(KEY_SIGNATURE, PersistentDataType.STRING);
            if (storedBehavior != null && (isPrivilegedBehavior(storedBehavior) || existingSig != null)) {
                String storedProvider = pdc.get(KEY_PROVIDER, PersistentDataType.STRING);
                String storedId = pdc.get(KEY_ITEM_ID, PersistentDataType.STRING);
                if (storedProvider != null && storedId != null) {
                    String instanceUuid = pdc.get(KEY_INSTANCE_UUID, PersistentDataType.STRING);
                    if (instanceUuid == null || instanceUuid.isBlank()) {
                        instanceUuid = UUID.randomUUID().toString();
                        pdc.set(KEY_INSTANCE_UUID, PersistentDataType.STRING, instanceUuid);
                    }
                    Map<String, String> updatedState = extractStateMap(pdc);
                    String newSig = hmacSigner.sign(
                            storedProvider,
                            storedId,
                            ItemRef.normalizeBehaviorId(storedBehavior),
                            instanceUuid,
                            updatedState
                    );
                    pdc.set(KEY_SIGNATURE, PersistentDataType.STRING, newSig);
                }
            }
            stack.setItemMeta(meta);
        }
    }

    /**
     * Clones an {@link ItemStack} with a new stack size while preserving all provider,
     * behavior, signature, and state metadata.
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
     * verified {@code behaviorId}, {@code instanceUuid} (re-signed for the new appearance),
     * stack size, and all persisted {@code allium:state_*} values.
     */
    public ResolutionResult swapOrRefreshAppearance(ItemStack existingStack, ProviderKey newAppearance) {
        Objects.requireNonNull(existingStack, "existingStack");
        Objects.requireNonNull(newAppearance, "newAppearance");
        ItemRef current = identify(existingStack)
                .orElseThrow(() -> new IllegalArgumentException("Cannot refresh empty or AIR stack"));

        String existingInstanceUuid = null;
        ItemMeta meta = existingStack.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer() != null) {
            existingInstanceUuid = meta.getPersistentDataContainer().get(KEY_INSTANCE_UUID, PersistentDataType.STRING);
        }

        // Only carry over bound behavior if the existing stack had a valid behavior or was non-vanilla
        String nextBehavior = current.behaviorId()
                .orElseGet(() -> current.appearance().isVanilla() ? null : appearanceToBehavior.get(newAppearance));

        ItemRef updatedRef = new ItemRef(
                current.schemaVersion(),
                newAppearance,
                nextBehavior,
                existingStack.getAmount(),
                current.stateData()
        );
        return createInternal(updatedRef, existingInstanceUuid);
    }

    private static Map<String, String> extractStateMap(PersistentDataContainer pdc) {
        Map<String, String> state = new LinkedHashMap<>();
        for (NamespacedKey key : pdc.getKeys()) {
            if (NAMESPACE.equals(key.getNamespace()) && key.getKey().startsWith(STATE_KEY_PREFIX)) {
                String stateName = key.getKey().substring(STATE_KEY_PREFIX.length());
                String val = pdc.get(key, PersistentDataType.STRING);
                if (!stateName.isEmpty() && val != null) {
                    state.put(stateName, val);
                }
            }
        }
        return state;
    }

    private void stampMetadata(
            ItemStack stack,
            int schemaVersion,
            ProviderKey appearance,
            @Nullable String behaviorId,
            Map<String, String> stateData,
            @Nullable String preservedInstanceUuid
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
            pdc.set(KEY_SCHEMA_VERSION, PersistentDataType.INTEGER, schemaVersion);
            if (!appearance.isVanilla() || behaviorId != null) {
                pdc.set(KEY_PROVIDER, PersistentDataType.STRING, appearance.provider());
                pdc.set(KEY_ITEM_ID, PersistentDataType.STRING, appearance.id());
            }
            for (Map.Entry<String, String> entry : stateData.entrySet()) {
                NamespacedKey stateKey = new NamespacedKey(NAMESPACE, STATE_KEY_PREFIX + entry.getKey());
                pdc.set(stateKey, PersistentDataType.STRING, entry.getValue());
            }
            if (behaviorId != null && !behaviorId.isBlank()) {
                pdc.set(KEY_BEHAVIOR_ID, PersistentDataType.STRING, behaviorId);
                if (isPrivilegedBehavior(behaviorId)) {
                    String instanceUuid = (preservedInstanceUuid != null && !preservedInstanceUuid.isBlank())
                            ? preservedInstanceUuid
                            : UUID.randomUUID().toString();
                    String signature = hmacSigner.sign(
                            appearance.provider(),
                            appearance.id(),
                            behaviorId,
                            instanceUuid,
                            stateData
                    );
                    pdc.set(KEY_INSTANCE_UUID, PersistentDataType.STRING, instanceUuid);
                    pdc.set(KEY_SIGNATURE, PersistentDataType.STRING, signature);
                }
            }
        }
        stack.setItemMeta(meta);
    }

    private void warnDiagnosticOnce(String message) {
        if (warnedDiagnostics.add(message)) {
            diagnosticLogger.accept(message);
        }
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
            if (id == null || id.isBlank() || id.contains(":")) {
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
