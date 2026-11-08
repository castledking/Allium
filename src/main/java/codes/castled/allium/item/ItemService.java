package codes.castled.allium.item;

import codes.castled.allium.core.FaultInjection;
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
    private final Set<String> signedBehaviors = ConcurrentHashMap.newKeySet();
    private final Map<String, ModelSelector> modelSelectors = new ConcurrentHashMap<>();
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
            if (definition.signed()) {
                signedBehaviors.add(definition.behaviorId());
            }
        }
    }

    /**
     * Removes an Allium item definition (an addon's, when the addon disables). Existing stacks keep
     * their stamps; they identify again once the definition is registered again.
     */
    public void unregisterAlliumItem(String id) {
        AlliumItemDefinition definition = alliumDefinitions.remove(id.trim().toLowerCase(Locale.ROOT));
        if (definition == null || definition.behaviorId() == null) {
            return;
        }
        appearanceToBehavior.remove(definition.providerKey(), definition.behaviorId());
        behaviorPreferredAppearance.remove(definition.behaviorId(), definition.providerKey());
        signedBehaviors.remove(definition.behaviorId());
        privilegedBehaviors.remove(definition.behaviorId());
    }

    /**
     * The definition whose behavior this is. Built-in behaviors are {@code allium:<id>}; an addon's
     * are {@code <addon>:<id>}, so the lookup goes by behavior, not by namespace.
     */
    @Nullable
    private AlliumItemDefinition definitionForBehavior(String behaviorId) {
        if (behaviorId.startsWith(ProviderKey.ALLIUM + ":")) {
            AlliumItemDefinition def = alliumDefinitions.get(behaviorId.substring((ProviderKey.ALLIUM + ":").length()));
            if (def != null && behaviorId.equals(def.behaviorId())) {
                return def;
            }
        }
        for (AlliumItemDefinition def : alliumDefinitions.values()) {
            if (behaviorId.equals(def.behaviorId())) {
                return def;
            }
        }
        return null;
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

    /**
     * Marks a behavior whose new stacks carry an instance UUID and MAC without requiring one.
     */
    /**
     * Chooses a built-in item's {@code custom_model_data} from its state, so state visuals (for
     * example Mob Disarmer charges) are model selections on the same stack, never item swaps.
     */
    @FunctionalInterface
    public interface ModelSelector {
        /** @return the model data for this state, or {@code null} to leave it unchanged */
        @Nullable Integer select(Map<String, String> state);
    }

    /**
     * Installs or clears ({@code null}) the model selector for {@code allium:<id>}. It runs whenever
     * Allium creates the item or writes its state.
     */
    public void setModelSelector(String alliumItemId, @Nullable ModelSelector selector) {
        String id = alliumItemId.trim().toLowerCase(Locale.ROOT);
        if (selector == null) {
            modelSelectors.remove(id);
        } else {
            modelSelectors.put(id, selector);
        }
    }

    /**
     * Re-applies the installed model selector to a built-in item's current state, for stacks whose
     * model went stale without a state write. Returns whether the stack changed. Model data is a
     * visual only and is not part of the signed state.
     */
    public boolean refreshModelSelection(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta == null ? null : meta.getPersistentDataContainer();
        if (pdc == null) {
            return false;
        }
        ProviderKey appearance = storedAppearanceForStateEdit(pdc);
        if (appearance == null || !ProviderKey.ALLIUM.equals(appearance.provider())) {
            return false;
        }
        ModelSelector selector = modelSelectors.get(appearance.id());
        Integer value = selector == null ? null : selector.select(Collections.unmodifiableMap(extractStateMap(pdc)));
        if (value == null || (meta.hasCustomModelData() && meta.getCustomModelData() == value)) {
            return false;
        }
        meta.setCustomModelData(value);
        stack.setItemMeta(meta);
        return true;
    }

    private void applyModelSelection(ItemMeta meta, @Nullable ProviderKey appearance, Map<String, String> state) {
        if (appearance == null || !ProviderKey.ALLIUM.equals(appearance.provider())) {
            return;
        }
        ModelSelector selector = modelSelectors.get(appearance.id());
        Integer value = selector == null ? null : selector.select(Collections.unmodifiableMap(state));
        if (value != null) {
            meta.setCustomModelData(value);
        }
    }

    public void registerSignedBehavior(String behaviorId) {
        String normalized = ItemRef.normalizeBehaviorId(behaviorId);
        if (normalized != null) {
            signedBehaviors.add(normalized);
        }
    }

    /**
     * True when new stacks of this behavior are stamped with an instance UUID and MAC:
     * every privileged behavior, plus non-privileged behaviors registered as signed.
     */
    public boolean isSignedBehavior(@Nullable String behaviorId) {
        String normalized = ItemRef.normalizeBehaviorId(behaviorId);
        return normalized != null && (privilegedBehaviors.contains(normalized) || signedBehaviors.contains(normalized));
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
     * Read-only snapshot of registered definition, alias and preferred-appearance bindings.
     * Startup provider diagnostics use this to inspect deliberately managed items only.
     */
    public Map<ProviderKey, String> appearanceBindings() {
        return Map.copyOf(appearanceToBehavior);
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
        } else if (boundBehavior != null) {
            AlliumItemDefinition def = definitionForBehavior(boundBehavior);
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
        return createInternal(ref, null, true, false);
    }

    /**
     * @param useAppearanceBinding when the ref names no behavior, fall back to the behavior bound
     *                             to its appearance. Refreshes pass {@code false} so they can
     *                             never mint behavior the source stack did not have.
     * @param preserveSignature    sign the new stack even when its behavior does not require it,
     *                             because the source stack carried a verified MAC
     */
    private ResolutionResult createInternal(
            ItemRef ref,
            @Nullable String preservedInstanceUuid,
            boolean useAppearanceBinding,
            boolean preserveSignature
    ) {
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

        String effectiveBehaviorId = ref.behaviorId()
                .orElseGet(() -> useAppearanceBinding ? appearanceToBehavior.get(appearance) : null);
        Map<String, String> mergedState = new LinkedHashMap<>();
        AlliumItemDefinition stateDefinition = effectiveBehaviorId == null ? null : definitionForBehavior(effectiveBehaviorId);
        if (stateDefinition != null) {
            mergedState.putAll(stateDefinition.defaultState());
        }
        mergedState.putAll(ref.stateData());

        stampMetadata(stack, ref.schemaVersion(), appearance, effectiveBehaviorId, mergedState,
                preservedInstanceUuid, preserveSignature);
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
        Set<ProviderKey> nativeAppearances = new java.util.TreeSet<>(java.util.Comparator.comparing(ProviderKey::asString));
        for (ItemProviderAdapter adapter : providers.values()) {
            String pName = adapter.providerName();
            if (ProviderKey.MINECRAFT.equals(pName) || ProviderKey.ALLIUM.equals(pName)) {
                continue;
            }
            if (adapter.status() == ProviderStatus.READY) {
                Optional<String> externalId = adapter.identifyItemId(stack);
                if (externalId.isPresent() && !externalId.get().isBlank()) {
                    try {
                        nativeAppearances.add(ProviderKey.of(pName, externalId.get()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        ProviderKey nativeAppearance = null;
        if (pdcAppearance != null && nativeAppearances.contains(pdcAppearance)) {
            // A provider's native API confirms this exact stamped appearance. Other providers
            // may still recognize legacy conversion tags; map iteration order is not authority.
            nativeAppearance = pdcAppearance;
            if (nativeAppearances.size() > 1) {
                warnDiagnosticOnce("Multiple native provider identities " + nativeAppearances
                        + "; choosing API-confirmed Allium stamp '" + pdcAppearance + "'. Signature verification still applies.");
            }
        } else if (nativeAppearances.size() == 1) {
            nativeAppearance = nativeAppearances.iterator().next();
        } else if (nativeAppearances.size() > 1) {
            warnDiagnosticOnce("Ambiguous native provider identities " + nativeAppearances
                    + " without an API-confirmed Allium stamp; refusing item/behavior matching. Metadata was preserved.");
            return Optional.empty();
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
                } else if (outcome.status() == ItemHmacSigner.VerificationStatus.UNSUPPORTED_VERSION) {
                    warnDiagnosticOnce("Rejected legacy/unsupported item signature format for '" + behaviorId
                            + "'. Preserve the item and keyring for explicit trusted migration; automatic re-signing is disabled.");
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
     * and re-signs the HMAC signature if the item is privileged or signed. Such items must
     * verify before any metadata is changed; rejected items are left untouched.
     */
    public void setState(ItemStack stack, String stateKey, String value) {
        Objects.requireNonNull(stateKey, "stateKey");
        Objects.requireNonNull(value, "value");
        updateState(stack, Map.of(stateKey, value));
    }

    /**
     * Applies several state values in one metadata write with at most one re-signature.
     * Privileged or signed stacks first verify their original identity and state; on rejection
     * nothing is written and {@code false} is returned. Unsigned non-privileged stacks are
     * written directly.
     *
     * @return {@code true} when every value was written (or {@code changes} is empty)
     */
    public boolean updateState(ItemStack stack, Map<String, String> changes) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(changes, "changes");
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String stateKey = Objects.requireNonNull(change.getKey(), "stateKey").trim().toLowerCase(Locale.ROOT);
            if (stateKey.isEmpty()) {
                throw new IllegalArgumentException("stateKey must not be blank");
            }
            normalized.put(stateKey, Objects.requireNonNull(change.getValue(), "value for " + stateKey));
        }
        if (normalized.isEmpty()) {
            return true;
        }
        if (FaultInjection.trip(FaultInjection.ITEM_STATE_WRITE)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc == null) {
            return false;
        }
        ProviderKey storedAppearance = storedAppearanceForStateEdit(pdc);
        String storedBehavior = ItemRef.normalizeBehaviorId(pdc.get(KEY_BEHAVIOR_ID, PersistentDataType.STRING));
        if (storedBehavior == null && storedAppearance != null) {
            storedBehavior = appearanceToBehavior.get(storedAppearance);
        }
        String existingSig = pdc.get(KEY_SIGNATURE, PersistentDataType.STRING);
        String newSig = null;
        if (isPrivilegedBehavior(storedBehavior) || existingSig != null) {
            String instanceUuid = pdc.get(KEY_INSTANCE_UUID, PersistentDataType.STRING);
            if (storedAppearance == null || storedBehavior == null
                    || instanceUuid == null || instanceUuid.isBlank()) {
                return false;
            }

            // identify verifies the original state and the authoritative native provider identity.
            // Rejection returns a plain vanilla reference, so presence alone is not proof of trust.
            ItemRef verified = identify(stack).orElse(null);
            if (verified == null || !storedAppearance.equals(verified.appearance())
                    || !Optional.of(storedBehavior).equals(verified.behaviorId())) {
                return false;
            }
            Map<String, String> updatedState = new LinkedHashMap<>(verified.stateData());
            updatedState.putAll(normalized);
            newSig = hmacSigner.sign(
                    verified.appearance().provider(),
                    verified.appearance().id(),
                    storedBehavior,
                    instanceUuid,
                    updatedState
            );
        }

        for (Map.Entry<String, String> change : normalized.entrySet()) {
            pdc.set(new NamespacedKey(NAMESPACE, STATE_KEY_PREFIX + change.getKey()), PersistentDataType.STRING, change.getValue());
        }
        if (newSig != null) {
            pdc.set(KEY_SIGNATURE, PersistentDataType.STRING, newSig);
        }
        applyModelSelection(meta, storedAppearance, extractStateMap(pdc));
        stack.setItemMeta(meta);
        return true;
    }

    @Nullable
    private static ProviderKey storedAppearanceForStateEdit(PersistentDataContainer pdc) {
        String provider = pdc.get(KEY_PROVIDER, PersistentDataType.STRING);
        String id = pdc.get(KEY_ITEM_ID, PersistentDataType.STRING);
        if (provider == null || provider.isBlank() || id == null || id.isBlank()) {
            return null;
        }
        try {
            return ProviderKey.of(provider, id);
        } catch (IllegalArgumentException ignored) {
            return null;
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
     * stack size, and all persisted {@code allium:state_*} values. A source stack with a
     * verified MAC stays signed even when its behavior does not require one.
     *
     * <p>Returns {@link ResolutionStatus#REJECTED} without building anything when the source
     * stack carries Allium identity claims that fail verification, or when the refresh would
     * attach a privileged behavior that the source stack did not already have.
     */
    public ResolutionResult swapOrRefreshAppearance(ItemStack existingStack, ProviderKey newAppearance) {
        Objects.requireNonNull(existingStack, "existingStack");
        Objects.requireNonNull(newAppearance, "newAppearance");
        ItemRef current = identify(existingStack)
                .orElseThrow(() -> new IllegalArgumentException("Cannot refresh empty or AIR stack"));

        String existingInstanceUuid = null;
        String existingSignature = null;
        boolean claimsBehavior = false;
        ItemMeta meta = existingStack.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer() != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            existingInstanceUuid = pdc.get(KEY_INSTANCE_UUID, PersistentDataType.STRING);
            existingSignature = pdc.get(KEY_SIGNATURE, PersistentDataType.STRING);
            claimsBehavior = pdc.has(KEY_BEHAVIOR_ID, PersistentDataType.STRING);
        }
        if ((existingSignature != null || claimsBehavior) && current.behaviorId().isEmpty()) {
            return ResolutionResult.rejected(newAppearance,
                    "Refusing to refresh a stack whose Allium identity failed verification");
        }

        // Only carry over bound behavior if the existing stack had a valid behavior or was non-vanilla
        String nextBehavior = current.behaviorId()
                .orElseGet(() -> current.appearance().isVanilla() ? null : appearanceToBehavior.get(newAppearance));
        if (current.behaviorId().isEmpty() && isPrivilegedBehavior(nextBehavior)) {
            return ResolutionResult.rejected(newAppearance,
                    "Refusing to attach privileged behavior '" + nextBehavior + "' through an appearance refresh");
        }

        ItemRef updatedRef = new ItemRef(
                current.schemaVersion(),
                newAppearance,
                nextBehavior,
                existingStack.getAmount(),
                current.stateData()
        );
        boolean verifiedSignature = existingSignature != null && current.behaviorId().isPresent();
        return createInternal(updatedRef, existingInstanceUuid, false, verifiedSignature);
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
            @Nullable String preservedInstanceUuid,
            boolean preserveSignature
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
                if (isSignedBehavior(behaviorId) || preserveSignature) {
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
        applyModelSelection(meta, appearance, stateData);
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
