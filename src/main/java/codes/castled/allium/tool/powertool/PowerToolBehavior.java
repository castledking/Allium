package codes.castled.allium.tool.powertool;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.LangMessage;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import codes.castled.allium.tool.ToolDefinitionLoader;
import codes.castled.allium.tool.definition.BuiltInItemSpec;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolDefinitionParser;
import codes.castled.allium.tool.definition.ToolEnvironment;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Runtime coordinator for Allium powertools ({@code allium:powertool} definitions from
 * {@code plugins/Allium/tools/*.yml} and HMAC-signed inline held-item powertools).
 */
public final class PowerToolBehavior {

    public static final String STATE_INLINE_JSON = "pt";
    public static final String STATE_USES = "pt_uses";
    public static final String STATE_RECHARGE_AT = "pt_recharge_at";

    public static final String PERM_GLOBAL_USE = "allium.powertool.use";
    public static final String PERM_GLOBAL_BYPASS = "allium.powertool.bypass";

    @FunctionalInterface
    public interface Notifier {
        void send(Player player, LangMessage message, String... replacements);
    }

    public record ResolvedTool(
            String id,
            String displayName,
            boolean inline,
            @Nullable ToolDefinition definition,
            PowerToolParameters parameters,
            @Nullable String usePermission,
            @Nullable String bypassPermission
    ) {
        public LangMessage message(String slot, Messages fallback) {
            if (definition == null) {
                return LangMessage.of(fallback);
            }
            return new LangMessage(definition.message(slot), fallback);
        }
    }

    public record SaveResult(boolean saved, String message, @Nullable ToolDefinition definition, @Nullable File file) {
        static SaveResult ok(ToolDefinition def, File file) {
            return new SaveResult(true, "Saved", def, file);
        }

        static SaveResult fail(String reason) {
            return new SaveResult(false, reason, null, null);
        }
    }

    public enum EnableOutcome {
        CHANGED,
        ALREADY_IN_STATE,
        NOT_FOUND,
        FAILED
    }

    public record EnableResult(EnableOutcome outcome, String localId, boolean enabled, String message) {
    }

    public record ToolSummary(String localId, boolean enabled) {
    }

    private final ItemService items;
    private final TaskScheduler scheduler;
    private final Notifier notifier;
    private final Consumer<String> diagnostics;
    private final LongSupplier clock;
    private final File toolsDirectory;
    private final Supplier<ToolEnvironment> environmentSupplier;
    private final PowerToolActionRegistry actionRegistry;
    private final PowerToolCooldownTracker cooldowns;
    private final Map<String, ToolDefinition> registryTools = new ConcurrentHashMap<>();
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    private volatile PowerToolSettings settings;

    public PowerToolBehavior(
            ItemService items,
            ProtectionService protection,
            TaskScheduler scheduler,
            Notifier notifier,
            Consumer<String> diagnostics,
            LongSupplier clock,
            RandomGenerator random,
            File toolsDirectory,
            Supplier<ToolEnvironment> environmentSupplier,
            PowerToolSettings initialSettings
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.toolsDirectory = Objects.requireNonNull(toolsDirectory, "toolsDirectory");
        this.environmentSupplier = Objects.requireNonNull(environmentSupplier, "environmentSupplier");
        this.settings = initialSettings == null ? PowerToolSettings.DEFAULT : initialSettings;
        this.cooldowns = new PowerToolCooldownTracker(clock);
        this.actionRegistry = new PowerToolActionRegistry(
                items, protection, scheduler, () -> this.settings, diagnostics, clock, random);
        items.registerPrivilegedBehavior(PowerToolBehaviorType.INLINE_BEHAVIOR_ID);
    }

    public PowerToolActionRegistry actions() {
        return actionRegistry;
    }

    public PowerToolCooldownTracker cooldowns() {
        return cooldowns;
    }

    public PowerToolSettings settings() {
        return settings;
    }

    public void updateSettings(PowerToolSettings next) {
        this.settings = next == null ? PowerToolSettings.DEFAULT : next;
    }

    public boolean isPlayerEnabled(UUID playerId) {
        return !disabledPlayers.contains(playerId);
    }

    /** Toggles personal powertool activation and returns the new enabled state. */
    public boolean togglePlayer(UUID playerId) {
        if (disabledPlayers.remove(playerId)) {
            return true;
        }
        disabledPlayers.add(playerId);
        return false;
    }

    public void cleanupPlayer(UUID playerId) {
        cooldowns.clearPlayer(playerId);
        disabledPlayers.remove(playerId);
    }

    public void disable() {
        actionRegistry.disable();
        cooldowns.clearAll();
        disabledPlayers.clear();
        clearRegistry();
    }

    public void clearRegistry() {
        for (ToolDefinition def : registryTools.values()) {
            if (def.item() != null) {
                items.unregisterAlliumItem(def.localId());
            }
        }
        registryTools.clear();
    }

    // ---- Registry management ----

    public void registerDefinition(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (!PowerToolBehaviorType.ID.equals(definition.behavior())) {
            throw new IllegalArgumentException("Expected behavior " + PowerToolBehaviorType.ID + ", got " + definition.behavior());
        }
        BuiltInItemSpec itemSpec = definition.item();
        if (itemSpec != null) {
            Material material = Material.matchMaterial(itemSpec.material());
            if (material != null && !material.isAir()) {
                items.registerAlliumItem(new AlliumItemDefinition(
                        definition.localId(),
                        material,
                        definition.id(),
                        true, // privileged: powertools are always HMAC-signed
                        itemSpec.customModelData(),
                        itemSpec.name(),
                        Map.of(),
                        true,
                        itemSpec.lore(),
                        itemSpec.enchantments(),
                        itemSpec.unbreakable(),
                        itemSpec.hideFlags()
                ));
            }
        }
        items.registerPrivilegedBehavior(definition.id());
        items.setPreferredAppearanceForBehavior(definition.id(), definition.appearance());
        for (ProviderKey key : definition.boundAppearances()) {
            items.bindAppearanceToBehavior(key, definition.id());
        }
        registerToolPermissions(definition);
        registryTools.put(definition.id(), definition);
    }

    private static void registerToolPermissions(ToolDefinition definition) {
        try {
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            if (server == null) {
                return;
            }
            org.bukkit.plugin.PluginManager pm = server.getPluginManager();
            String useNode = definition.permission(PowerToolBehaviorType.PERM_USE);
            if (pm.getPermission(useNode) == null) {
                pm.addPermission(new org.bukkit.permissions.Permission(
                        useNode, definition.name() + " (use)", org.bukkit.permissions.PermissionDefault.TRUE));
            }
            String bypassNode = definition.permission(PowerToolBehaviorType.PERM_BYPASS);
            if (pm.getPermission(bypassNode) == null) {
                pm.addPermission(new org.bukkit.permissions.Permission(
                        bypassNode, definition.name() + " (bypass)", org.bukkit.permissions.PermissionDefault.OP));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Pure unit-test environments without a live Bukkit Server
        }
    }

    public Optional<ToolDefinition> registryTool(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return Optional.empty();
        }
        String normalized = nameOrId.trim().toLowerCase(Locale.ROOT);
        String fullId = normalized.contains(":") ? normalized : ProviderKey.ALLIUM + ":" + normalized;
        return Optional.ofNullable(registryTools.get(fullId));
    }

    public Collection<ToolDefinition> registryTools() {
        return Collections.unmodifiableCollection(registryTools.values());
    }

    public List<String> registryLocalIds() {
        return registryTools.values().stream().map(ToolDefinition::localId).sorted().toList();
    }

    /**
     * Resolves an item stack to a powertool (either a saved {@code allium:powertool} definition or
     * a signed inline {@code allium:powertool_inline} item). Returns {@code null} if the stack is
     * not a verified powertool.
     */
    @Nullable
    public ResolvedTool resolve(@Nullable ItemStack stack) {
        ItemRef ref = items.identify(stack).orElse(null);
        if (ref == null || ref.behaviorId().isEmpty()) {
            return null;
        }
        String behaviorId = ref.behaviorId().get();
        if (PowerToolBehaviorType.INLINE_BEHAVIOR_ID.equals(behaviorId)) {
            String json = ref.stateData().get(STATE_INLINE_JSON);
            if (json == null || json.isBlank()) {
                return null;
            }
            try {
                PowerToolCodec.DecodedInline decoded = PowerToolCodec.decodeInlineJson(json);
                return new ResolvedTool(
                        PowerToolBehaviorType.INLINE_BEHAVIOR_ID,
                        itemDisplayName(stack, "PowerTool"),
                        true,
                        null,
                        decoded.parameters(),
                        decoded.usePermission(),
                        PERM_GLOBAL_BYPASS
                );
            } catch (RuntimeException e) {
                diagnostics.accept("Failed to decode inline powertool state: " + e.getMessage());
                return null;
            }
        }
        ToolDefinition def = registryTools.get(behaviorId);
        if (def == null) {
            return null;
        }
        return new ResolvedTool(
                def.id(),
                def.name(),
                false,
                def,
                def.parameters(PowerToolParameters.class),
                def.permission(PowerToolBehaviorType.PERM_USE),
                def.permission(PowerToolBehaviorType.PERM_BYPASS)
        );
    }

    // ---- Execution ----

    /**
     * Handles one trigger firing on {@code item}. Returns {@code true} when a matching binding
     * existed and was processed (whether it executed, refused, or was guarded).
     */
    public boolean handleTrigger(
            Player player,
            ItemStack item,
            String hand,
            PowerToolTrigger trigger,
            @Nullable Block block,
            @Nullable Entity targetEntity,
            @Nullable Cancellable cancellable,
            @Nullable Event causeEvent
    ) {
        if (!settings.enabled() || !isPlayerEnabled(player.getUniqueId())) {
            return false;
        }
        if (trigger == PowerToolTrigger.ON_BREAK && actionRegistry.isBreakingFor(player.getUniqueId())) {
            return false;
        }
        ResolvedTool tool = resolve(item);
        if (tool == null) {
            return false;
        }
        PowerToolBinding binding = tool.parameters().binding(trigger);
        if (binding == null || binding.actions().isEmpty()) {
            return false;
        }
        if (!binding.matchesHand(hand)) {
            return false;
        }
        if (!binding.matchesSneak(player.isSneaking())) {
            return false;
        }
        if (player.getWorld() != null && !binding.matchesWorld(player.getWorld().getName())) {
            return false;
        }

        // Permissions
        if (!player.hasPermission(PERM_GLOBAL_USE)) {
            notifier.send(player, tool.message(PowerToolBehaviorType.MSG_NO_PERMISSION, Messages.NO_PERMISSION));
            if (cancellable != null && binding.cancelInteraction()) {
                cancellable.setCancelled(true);
            }
            return true;
        }
        if (tool.usePermission() != null && !player.hasPermission(tool.usePermission())) {
            notifier.send(player, tool.message(PowerToolBehaviorType.MSG_NO_PERMISSION, Messages.NO_PERMISSION));
            if (cancellable != null && binding.cancelInteraction()) {
                cancellable.setCancelled(true);
            }
            return true;
        }
        if (binding.hasPermission() && !player.hasPermission(binding.requirePermission())) {
            notifier.send(player, tool.message(PowerToolBehaviorType.MSG_NO_PERMISSION, Messages.NO_PERMISSION));
            if (cancellable != null && binding.cancelInteraction()) {
                cancellable.setCancelled(true);
            }
            return true;
        }

        // Click guard debounce
        if (!cooldowns.tryAcquireClickGuard(player.getUniqueId(), trigger, tool.parameters().clickGuardMillis())) {
            if (cancellable != null && binding.cancelInteraction()) {
                cancellable.setCancelled(true);
            }
            return true;
        }

        boolean bypass = player.hasPermission(PERM_GLOBAL_BYPASS)
                || (tool.bypassPermission() != null && player.hasPermission(tool.bypassPermission()));
        String instanceUuid = items.instanceUuid(item).orElse(null);

        // Uses / charges pre-check
        PowerToolUses usesSpec = tool.parameters().uses();
        boolean spendUse = usesSpec.isLimited() && binding.consumeUse() && !bypass;
        int currentUses = -1;
        long now = clock.getAsLong();
        if (usesSpec.isLimited()) {
            if (usesSpec.requireSingleItem() && item.getAmount() > 1) {
                notifier.send(player, tool.message(PowerToolBehaviorType.MSG_SINGLE_ITEM, Messages.POWERTOOL_SINGLE_ITEM));
                if (cancellable != null && binding.cancelInteraction()) {
                    cancellable.setCancelled(true);
                }
                return true;
            }
            UsesState state = readUsesState(item, usesSpec, now);
            if (state.settled()) {
                items.updateState(item, state.toMap());
            }
            if (!bypass && state.recharging(now)) {
                notifier.send(player, tool.message(PowerToolBehaviorType.MSG_RECHARGING, Messages.POWERTOOL_RECHARGING),
                        "{time}", PowerToolDurations.formatMillis(state.rechargeAt() - now));
                if (cancellable != null && binding.cancelInteraction()) {
                    cancellable.setCancelled(true);
                }
                return true;
            }
            if (!bypass && state.uses() <= 0) {
                notifier.send(player, tool.message(PowerToolBehaviorType.MSG_NO_USES, Messages.POWERTOOL_NO_USES));
                if (cancellable != null && binding.cancelInteraction()) {
                    cancellable.setCancelled(true);
                }
                return true;
            }
            currentUses = state.uses();
        }

        // Binding-level cooldown check
        if (!bypass && binding.hasCooldown()) {
            long rem = cooldowns.remainingMillis(
                    player.getUniqueId(), tool.id(), instanceUuid, trigger, "binding",
                    binding.cooldownScope(), binding.cooldownId());
            if (rem > 0L) {
                sendCooldownFeedback(player, tool, binding, rem);
                if (cancellable != null && binding.cancelInteraction()) {
                    cancellable.setCancelled(true);
                }
                return true;
            }
        }

        if (cancellable != null && binding.cancelInteraction()) {
            cancellable.setCancelled(true);
        }

        PowerToolContext ctx = new PowerToolContext(
                player, tool.id(), instanceUuid, item, hand, trigger, block, targetEntity, causeEvent);

        boolean anyRan = false;
        long longestActionCooldown = 0L;
        List<PowerToolAction> actionList = binding.actions();
        for (int i = 0; i < actionList.size(); i++) {
            PowerToolAction action = actionList.get(i);
            if (action.hasPermission() && !player.hasPermission(action.requirePermission())) {
                continue;
            }
            String slotId = "a" + i;
            if (!bypass && action.hasCooldown()) {
                long rem = cooldowns.remainingMillis(
                        player.getUniqueId(), tool.id(), instanceUuid, trigger, slotId,
                        action.cooldownScope(), action.cooldownId());
                if (rem > 0L) {
                    longestActionCooldown = Math.max(longestActionCooldown, rem);
                    continue;
                }
            }
            PowerToolActionHandler handler = actionRegistry.get(action.type());
            if (handler == null) {
                diagnostics.accept("PowerTool '" + tool.id() + "' references unregistered action type '" + action.type() + "'");
                continue;
            }
            if (action.hasDelay()) {
                if (!bypass && action.hasCooldown()) {
                    cooldowns.setCooldown(player.getUniqueId(), tool.id(), instanceUuid, trigger, slotId,
                            action.cooldownScope(), action.cooldownId(), action.cooldownMillis());
                }
                long delayMs = action.delayTicks() * 50L;
                scheduler.runOnServerLater(() -> {
                    if (player.isOnline() && !player.isDead()) {
                        handler.execute(ctx, action);
                    }
                }, delayMs);
                anyRan = true;
            } else {
                boolean ran = handler.execute(ctx, action);
                if (ran) {
                    if (!bypass && action.hasCooldown()) {
                        cooldowns.setCooldown(player.getUniqueId(), tool.id(), instanceUuid, trigger, slotId,
                                action.cooldownScope(), action.cooldownId(), action.cooldownMillis());
                    }
                    anyRan = true;
                }
            }
        }

        if (!anyRan && longestActionCooldown > 0L) {
            sendCooldownFeedback(player, tool, binding, longestActionCooldown);
            return true;
        }
        if (!anyRan) {
            return true;
        }

        // Apply binding cooldown + visual cooldown
        if (!bypass && binding.hasCooldown()) {
            cooldowns.setCooldown(player.getUniqueId(), tool.id(), instanceUuid, trigger, "binding",
                    binding.cooldownScope(), binding.cooldownId(), binding.cooldownMillis());
            if (binding.visualCooldown()) {
                int ticks = (int) Math.min(Integer.MAX_VALUE, PowerToolDurations.millisToTicks(binding.cooldownMillis()));
                player.setCooldown(item.getType(), ticks);
            }
        }

        // Spend use if limited
        if (spendUse) {
            spendUse(player, item, hand, usesSpec, currentUses, now);
        }
        return true;
    }

    private void sendCooldownFeedback(Player player, ResolvedTool tool, PowerToolBinding binding, long remainingMillis) {
        String formatted = PowerToolDurations.formatMillis(remainingMillis);
        if (binding.cooldownMessage() != null && !binding.cooldownMessage().isBlank()) {
            String raw = binding.cooldownMessage().replace("{time}", formatted);
            if (raw.regionMatches(true, 0, "[actionbar]", 0, "[actionbar]".length())) {
                String body = raw.substring("[actionbar]".length()).stripLeading();
                TextSender.sendActionBar(player, TextFormatter.parse(body));
            } else {
                TextSender.send(player, TextFormatter.parse(raw));
            }
            return;
        }
        notifier.send(player, tool.message(PowerToolBehaviorType.MSG_COOLDOWN, Messages.POWERTOOL_COOLDOWN),
                "{time}", formatted);
    }

    private record UsesState(int uses, long rechargeAt, boolean settled) {
        boolean recharging(long now) {
            return rechargeAt > now;
        }

        Map<String, String> toMap() {
            return Map.of(
                    STATE_USES, Integer.toString(uses),
                    STATE_RECHARGE_AT, Long.toString(rechargeAt)
            );
        }
    }

    private UsesState readUsesState(ItemStack stack, PowerToolUses spec, long now) {
        int uses = items.getState(stack, STATE_USES).map(s -> parseInt(s, spec.max())).orElse(spec.max());
        long rechargeAt = items.getState(stack, STATE_RECHARGE_AT).map(s -> parseLong(s, 0L)).orElse(0L);
        if (rechargeAt > 0L && now >= rechargeAt) {
            return new UsesState(spec.max(), 0L, true);
        }
        return new UsesState(Math.min(uses, spec.max()), rechargeAt, false);
    }

    private void spendUse(Player player, ItemStack item, String hand, PowerToolUses spec, int currentUses, long now) {
        int nextUses = Math.max(0, currentUses - 1);
        if (nextUses == 0 && spec.consumeOnEmpty()) {
            if (item.getAmount() <= 1) {
                setHandItem(player, hand, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                setHandItem(player, hand, item);
            }
            return;
        }
        long nextRechargeAt = (nextUses == 0 && spec.rechargeSeconds() > 0) ? (now + spec.rechargeMillis()) : 0L;
        items.updateState(item, Map.of(
                STATE_USES, Integer.toString(nextUses),
                STATE_RECHARGE_AT, Long.toString(nextRechargeAt)
        ));
        setHandItem(player, hand, item);
    }

    private static void setHandItem(Player player, String hand, @Nullable ItemStack stack) {
        if (PowerToolBinding.HAND_OFF.equalsIgnoreCase(hand)) {
            player.getInventory().setItemInOffHand(stack);
        } else {
            player.getInventory().setItemInMainHand(stack);
        }
    }

    // ---- Inline editing & saving to plugins/Allium/tools/<name>.yml ----

    /**
     * Reads editable parameters from a held stack (inline state, or a mutable copy of a bound
     * registry tool's parameters, or {@link PowerToolParameters#EMPTY} for a plain item).
     */
    public PowerToolCodec.DecodedInline readEditable(ItemStack stack) {
        ResolvedTool resolved = resolve(stack);
        if (resolved == null) {
            return new PowerToolCodec.DecodedInline(PowerToolParameters.EMPTY, null);
        }
        return new PowerToolCodec.DecodedInline(resolved.parameters(), resolved.usePermission());
    }

    public boolean writeInline(ItemStack stack, PowerToolParameters parameters, @Nullable String usePermission) {
        if (!parameters.hasBindings() && !parameters.uses().isLimited()) {
            items.clearSignedBehavior(stack);
            return true;
        }
        String json = PowerToolCodec.encodeInlineJson(parameters, usePermission);
        Map<String, String> state = new LinkedHashMap<>();
        state.put(STATE_INLINE_JSON, json);
        if (parameters.uses().isLimited()) {
            state.put(STATE_USES, Integer.toString(parameters.uses().max()));
            state.put(STATE_RECHARGE_AT, "0");
        }
        return items.bindSignedBehavior(stack, PowerToolBehaviorType.INLINE_BEHAVIOR_ID, state);
    }

    public boolean clearHeld(ItemStack stack) {
        return items.clearSignedBehavior(stack);
    }

    public boolean bindRegistryToHeld(ItemStack stack, ToolDefinition definition) {
        PowerToolParameters params = definition.parameters(PowerToolParameters.class);
        Map<String, String> state = new LinkedHashMap<>();
        if (params.uses().isLimited()) {
            state.put(STATE_USES, Integer.toString(params.uses().max()));
            state.put(STATE_RECHARGE_AT, "0");
        }
        return items.bindSignedBehavior(stack, definition.id(), state);
    }

    /**
     * Saves the held item's powertool configuration and visual appearance to
     * {@code plugins/Allium/tools/<localId>.yml}, validates it with {@link ToolDefinitionParser},
     * registers it live immediately, and binds the held item to the saved {@code allium:<localId>}.
     */
    public SaveResult saveHeldAsTool(String rawName, ItemStack held, boolean overwrite) {
        String localId = PowerToolSecurity.normalizeToolName(rawName);
        if (!PowerToolSecurity.isValidToolName(localId)) {
            return SaveResult.fail("Tool name must be 2-48 characters using a-z, 0-9 and _.");
        }
        ResolvedTool resolved = resolve(held);
        if (resolved == null || !resolved.parameters().hasBindings()) {
            return SaveResult.fail("Hold an item with at least one powertool action before saving.");
        }

        File targetFile = new File(toolsDirectory, localId + ".yml");
        if (targetFile.exists() && !overwrite) {
            return SaveResult.fail("tools/" + localId + ".yml already exists. Pass --overwrite to replace it.");
        }

        // Determine appearance: external provider item if present, else a built-in item from held stack.
        Optional<ItemRef> identified = items.identify(held);
        ProviderKey externalKey = identified.map(ItemRef::appearance)
                .filter(k -> !k.isVanilla() && !k.isAllium())
                .orElse(null);

        ProviderKey defaultAppearance = externalKey != null ? externalKey : ProviderKey.allium(localId);
        BuiltInItemSpec builtIn = externalKey != null ? null : extractBuiltInItem(held);
        String displayName = itemDisplayName(held, localId);
        String usePerm = resolved.usePermission();
        String bypassPerm = resolved.definition() != null
                ? resolved.definition().permission(PowerToolBehaviorType.PERM_BYPASS)
                : null;

        Map<String, Object> doc = PowerToolCodec.toDefinitionDocument(
                localId, displayName, builtIn, defaultAppearance, resolved.parameters(), usePerm, bypassPerm);

        Map<String, ToolBehaviorType<?>> behaviors = Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType());
        ToolDefinitionParser.Result parsed = ToolDefinitionParser.parse(
                ToolDefinitionLoader.DIRECTORY + "/" + localId + ".yml", doc, behaviors, environmentSupplier.get());
        if (!parsed.valid()) {
            return SaveResult.fail(String.join("; ", parsed.errors()));
        }

        if (!toolsDirectory.isDirectory() && !toolsDirectory.mkdirs()) {
            return SaveResult.fail("Could not create " + toolsDirectory.getPath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        writeMapToYaml(yaml, doc);
        try {
            yaml.save(targetFile);
        } catch (IOException e) {
            return SaveResult.fail("Could not write " + targetFile.getName() + ": " + e.getMessage());
        }

        ToolDefinition savedDef = parsed.definition();
        registerDefinition(savedDef);
        bindRegistryToHeld(held, savedDef);
        return SaveResult.ok(savedDef, targetFile);
    }

    /** Deletes a saved {@code allium:powertool} file and unregisters it live. */
    public SaveResult deleteSavedTool(String rawName) {
        String localId = PowerToolSecurity.normalizeToolName(rawName);
        ToolDefinition def = registryTool(localId).orElse(null);
        if (def == null) {
            return SaveResult.fail("No powertool named '" + localId + "' is loaded.");
        }
        File file = new File(toolsDirectory, localId + ".yml");
        if (file.isFile() && !file.delete()) {
            return SaveResult.fail("Could not delete " + file.getName());
        }
        registryTools.remove(def.id());
        if (def.item() != null) {
            items.unregisterAlliumItem(def.localId());
        }
        return SaveResult.ok(def, file);
    }

    /**
     * Lists every valid tool definition file in {@code tools/} with its current {@code enabled}
     * flag (both {@code allium:powertool} and staple tools such as {@code lazy_axe}).
     */
    public List<ToolSummary> listAllToolFiles() {
        if (!toolsDirectory.isDirectory()) {
            return List.of();
        }
        File[] files = toolsDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return List.of();
        }
        Map<String, ToolSummary> byId = new java.util.TreeMap<>();
        for (File file : files) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (Exception e) {
                continue;
            }
            String id = yaml.getString("id");
            if (id == null || id.isBlank()) {
                continue;
            }
            String localId = id.contains(":") ? id.substring(id.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT) : id.trim().toLowerCase(Locale.ROOT);
            boolean enabled = yaml.getBoolean("enabled", true);
            byId.put(localId, new ToolSummary(localId, enabled));
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Enables or disables any tool definition file in {@code plugins/Allium/tools/*.yml}
     * (preserving YAML comments), then invokes {@code afterUpdate} (or reloads powertools when null)
     * so the change takes effect immediately without restarting.
     */
    public EnableResult setToolEnabled(String rawName, boolean enabled, @Nullable Runnable afterUpdate) {
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(ProviderKey.ALLIUM + ":")) {
            normalized = normalized.substring((ProviderKey.ALLIUM + ":").length());
        }
        File match = findToolFile(normalized);
        if (match == null) {
            return new EnableResult(EnableOutcome.NOT_FOUND, normalized, enabled, "No tool named '" + normalized + "' found in tools/.");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(match);
        } catch (Exception e) {
            return new EnableResult(EnableOutcome.FAILED, normalized, enabled, "Could not read " + match.getName() + ": " + e.getMessage());
        }
        String rawId = yaml.getString("id", normalized);
        String localId = rawId.contains(":") ? rawId.substring(rawId.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT) : rawId.trim().toLowerCase(Locale.ROOT);
        boolean current = yaml.getBoolean("enabled", true);
        if (current == enabled) {
            return new EnableResult(EnableOutcome.ALREADY_IN_STATE, localId, enabled, "Already " + (enabled ? "enabled" : "disabled"));
        }
        try {
            String content = java.nio.file.Files.readString(match.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            String updated;
            java.util.regex.Pattern enabledLine = java.util.regex.Pattern.compile("(?m)^enabled\\s*:.*$");
            if (enabledLine.matcher(content).find()) {
                updated = enabledLine.matcher(content).replaceFirst("enabled: " + enabled);
            } else {
                java.util.regex.Pattern behaviorLine = java.util.regex.Pattern.compile("(?m)^behavior\\s*:.*$");
                java.util.regex.Matcher bm = behaviorLine.matcher(content);
                if (bm.find()) {
                    updated = content.substring(0, bm.end()) + "\nenabled: " + enabled + content.substring(bm.end());
                } else {
                    updated = "enabled: " + enabled + "\n" + content;
                }
            }
            java.nio.file.Files.writeString(match.toPath(), updated, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new EnableResult(EnableOutcome.FAILED, localId, enabled, "Could not write " + match.getName() + ": " + e.getMessage());
        }
        if (afterUpdate != null) {
            afterUpdate.run();
        } else {
            reloadPowerTools();
        }
        return new EnableResult(EnableOutcome.CHANGED, localId, enabled, enabled ? "Enabled" : "Disabled");
    }

    @Nullable
    private File findToolFile(String localId) {
        if (!toolsDirectory.isDirectory()) {
            return null;
        }
        File direct = new File(toolsDirectory, localId + ".yml");
        if (direct.isFile()) {
            return direct;
        }
        File[] files = toolsDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return null;
        }
        String fullId = ProviderKey.ALLIUM + ":" + localId;
        for (File file : files) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (Exception e) {
                continue;
            }
            String id = yaml.getString("id");
            if (id != null && (id.equalsIgnoreCase(fullId) || id.equalsIgnoreCase(localId))) {
                return file;
            }
        }
        return null;
    }

    /** Hot-reloads all {@code allium:powertool} definitions from {@code plugins/Allium/tools/*.yml}. */
    public int reloadPowerTools() {
        for (ToolDefinition def : new ArrayList<>(registryTools.values())) {
            if (def.item() != null) {
                items.unregisterAlliumItem(def.localId());
            }
        }
        registryTools.clear();
        if (!toolsDirectory.isDirectory()) {
            return 0;
        }
        File[] files = toolsDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        Map<String, ToolBehaviorType<?>> behaviors = Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType());
        ToolEnvironment env = environmentSupplier.get();
        int count = 0;
        for (File file : files) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (Exception e) {
                continue;
            }
            if (!PowerToolBehaviorType.ID.equals(yaml.getString("behavior"))) {
                continue;
            }
            ToolDefinitionParser.Result res = ToolDefinitionParser.parse(
                    ToolDefinitionLoader.DIRECTORY + "/" + file.getName(),
                    ToolDefinitionLoader.toMap(yaml),
                    behaviors,
                    env
            );
            if (!res.valid()) {
                diagnostics.accept("PowerTool definition " + res.file() + " is invalid: " + String.join("; ", res.errors()));
                continue;
            }
            ToolDefinition def = res.definition();
            if (def.enabled()) {
                registerDefinition(def);
                count++;
            }
        }
        return count;
    }

    /** Creates a freshly signed {@link ItemStack} for any saved tool ID (powertool or staple tool). */
    public ResolutionResult createToolItem(String rawName, int amount) {
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        String behaviorId = normalized.contains(":") ? normalized : ProviderKey.ALLIUM + ":" + normalized;
        return items.createForBehavior(behaviorId, Math.max(1, amount));
    }

    // ---- Helpers ----

    @SuppressWarnings("deprecation")
    private static BuiltInItemSpec extractBuiltInItem(ItemStack stack) {
        String material = stack.getType().name().toLowerCase(Locale.ROOT);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return new BuiltInItemSpec(material, null, null);
        }
        String name = meta.hasDisplayName() ? meta.getDisplayName() : null;
        Integer cmd = meta.hasCustomModelData() ? meta.getCustomModelData() : null;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? List.copyOf(meta.getLore()) : List.of();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
            String key = e.getKey().getKey().getNamespace().equals("minecraft")
                    ? e.getKey().getKey().getKey()
                    : e.getKey().getKey().toString();
            enchants.put(key, e.getValue());
        }
        boolean unbreakable = meta.isUnbreakable();
        List<String> hideFlags = new ArrayList<>();
        for (ItemFlag flag : meta.getItemFlags()) {
            hideFlags.add(flag.name());
        }
        return new BuiltInItemSpec(material, name, cmd, lore, enchants, unbreakable, hideFlags);
    }

    @SuppressWarnings("deprecation")
    private static String itemDisplayName(@Nullable ItemStack stack, String fallback) {
        if (stack != null && stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return TextFormatter.stripFormatting(meta.getDisplayName());
            }
        }
        return fallback;
    }

    private static void writeMapToYaml(YamlConfiguration yaml, Map<String, Object> doc) {
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue());
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
