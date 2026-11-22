package codes.castled.allium.tool.powertool;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.pack.Json;
import codes.castled.allium.tool.definition.BuiltInItemSpec;
import codes.castled.allium.tool.definition.ConfigReader;
import codes.castled.allium.tool.definition.ToolDefinitionParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link PowerToolParameters} deterministically to:
 * <ul>
 *   <li>compact JSON (for HMAC-signed inline held-item PDC state), and</li>
 *   <li>plain nested YAML maps matching {@code plugins/Allium/tools/<name>.yml} ({@code schema: 1}).</li>
 * </ul>
 */
public final class PowerToolCodec {

    private PowerToolCodec() {
    }

    /** Compact deterministic JSON for signed inline item state. */
    public static String encodeInlineJson(PowerToolParameters parameters, @Nullable String usePermission) {
        Map<String, Object> root = new LinkedHashMap<>();
        writeParameters(root, parameters);
        if (usePermission != null && !usePermission.isBlank()) {
            root.put("use-permission", usePermission.trim());
        }
        return Json.write(root);
    }

    public record DecodedInline(PowerToolParameters parameters, @Nullable String usePermission) {
    }

    public static DecodedInline decodeInlineJson(String json) {
        Map<String, Object> map = Json.parseObject(json);
        String usePerm = null;
        Object rawPerm = map.get("use-permission");
        if (rawPerm instanceof String s && !s.isBlank()) {
            usePerm = s.trim();
        }
        Map<String, Object> clean = new LinkedHashMap<>(map);
        clean.remove("use-permission");
        ConfigReader reader = ConfigReader.of(clean);
        PowerToolParameters params = PowerToolBehaviorType.parseParameters(reader);
        if (reader.hasErrors()) {
            throw new IllegalArgumentException("Invalid inline powertool state: " + String.join("; ", reader.errors()));
        }
        return new DecodedInline(params, usePerm);
    }

    /**
     * Builds a complete {@code schema: 1} tool definition document ready to write to
     * {@code plugins/Allium/tools/<localId>.yml}.
     */
    public static Map<String, Object> toDefinitionDocument(
            String localId,
            String displayName,
            @Nullable BuiltInItemSpec item,
            ProviderKey defaultAppearance,
            PowerToolParameters parameters,
            @Nullable String usePermission,
            @Nullable String bypassPermission
    ) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", ToolDefinitionParser.SCHEMA);
        doc.put("id", ProviderKey.ALLIUM + ":" + localId);
        doc.put("name", displayName);
        doc.put("behavior", PowerToolBehaviorType.ID);
        doc.put("enabled", true);

        if (item != null) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("material", item.material());
            if (item.name() != null && !item.name().isEmpty()) {
                itemMap.put("name", item.name());
            }
            if (!item.lore().isEmpty()) {
                itemMap.put("lore", new ArrayList<>(item.lore()));
            }
            if (item.customModelData() != null) {
                itemMap.put("custom-model-data", item.customModelData());
            }
            if (!item.enchantments().isEmpty()) {
                itemMap.put("enchantments", new LinkedHashMap<>(item.enchantments()));
            }
            if (item.unbreakable()) {
                itemMap.put("unbreakable", true);
            }
            if (!item.hideFlags().isEmpty()) {
                itemMap.put("hide-flags", new ArrayList<>(item.hideFlags()));
            }
            doc.put("item", itemMap);
        }

        Map<String, Object> appearance = new LinkedHashMap<>();
        appearance.put("default", defaultAppearance.asString());
        appearance.put("aliases", List.of());
        doc.put("appearance", appearance);

        writeParameters(doc, parameters);

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put(PowerToolBehaviorType.PERM_USE,
                usePermission != null && !usePermission.isBlank() ? usePermission : "allium.tool." + localId + ".use");
        permissions.put(PowerToolBehaviorType.PERM_BYPASS,
                bypassPermission != null && !bypassPermission.isBlank() ? bypassPermission : "allium.tool." + localId + ".bypass");
        doc.put("permissions", permissions);
        doc.put("messages", Map.of());
        return doc;
    }

    private static void writeParameters(Map<String, Object> target, PowerToolParameters parameters) {
        PowerToolUses uses = parameters.uses();
        if (uses.isLimited() || uses.consumeOnEmpty() || uses.rechargeSeconds() > 0) {
            Map<String, Object> usesMap = new LinkedHashMap<>();
            usesMap.put("max", uses.max());
            usesMap.put("consume-on-empty", uses.consumeOnEmpty());
            usesMap.put("recharge-seconds", uses.rechargeSeconds());
            usesMap.put("require-single-item", uses.requireSingleItem());
            if (!uses.bypassable()) {
                usesMap.put("bypassable", false);
            }
            target.put("uses", usesMap);
        }

        if (parameters.clickGuardMillis() != PowerToolParameters.DEFAULT_CLICK_GUARD_MILLIS) {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("click-guard-millis", parameters.clickGuardMillis());
            target.put("timing", timing);
        }

        Map<String, Object> bindingsMap = new LinkedHashMap<>();
        for (Map.Entry<PowerToolTrigger, PowerToolBinding> entry : parameters.bindings().entrySet()) {
            PowerToolBinding b = entry.getValue();
            if (b.actions().isEmpty()) {
                continue;
            }
            Map<String, Object> bm = new LinkedHashMap<>();
            if (b.hasPermission()) {
                bm.put("require-permission", b.requirePermission());
            }
            bm.put("cancel-interaction", b.cancelInteraction());
            if (!PowerToolBinding.HAND_MAIN.equals(b.hand())) {
                bm.put("hand", b.hand());
            }
            if (!PowerToolBinding.SNEAK_ANY.equals(b.sneak())) {
                bm.put("sneak", b.sneak());
            }
            if (!b.worlds().isEmpty()) {
                bm.put("worlds", new ArrayList<>(b.worlds()));
            }
            if (!b.fishing().isEmpty()) {
                bm.put("fishing", new ArrayList<>(b.fishing()));
            }
            if (b.hasCooldown()) {
                bm.put("cooldown", PowerToolDurations.formatMillis(b.cooldownMillis()));
                if (b.cooldownScope() != PowerToolCooldownScope.PLAYER) {
                    bm.put("cooldown-scope", b.cooldownScope().id());
                }
                if (b.cooldownId() != null) {
                    bm.put("cooldown-id", b.cooldownId());
                }
            }
            if (b.cooldownMessage() != null) {
                bm.put("cooldown-message", b.cooldownMessage());
            }
            if (b.visualCooldown()) {
                bm.put("visual-cooldown", true);
            }
            if (!b.consumeUse()) {
                bm.put("consume-use", false);
            }

            List<Map<String, Object>> actionsList = new ArrayList<>();
            for (PowerToolAction a : b.actions()) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("type", a.type());
                if (a.value() != null && !a.value().isEmpty()) {
                    am.put("value", a.value());
                }
                if (a.isConsoleExecution() && !PowerToolAction.TYPE_CONSOLE.equals(a.type())) {
                    am.put("as", "console");
                }
                if (a.hasCooldown()) {
                    am.put("cooldown", PowerToolDurations.formatMillis(a.cooldownMillis()));
                    if (a.cooldownScope() != PowerToolCooldownScope.PLAYER) {
                        am.put("cooldown-scope", a.cooldownScope().id());
                    }
                    if (a.cooldownId() != null) {
                        am.put("cooldown-id", a.cooldownId());
                    }
                }
                if (a.hasDelay()) {
                    am.put("delay", PowerToolDurations.formatTicks(a.delayTicks()));
                }
                if (a.hasPermission()) {
                    am.put("require-permission", a.requirePermission());
                }
                if (a.required()) {
                    am.put("required", true);
                }
                if (!a.params().isEmpty()) {
                    am.put("params", new LinkedHashMap<>(a.params()));
                }
                actionsList.add(am);
            }
            bm.put("actions", actionsList);
            bindingsMap.put(entry.getKey().id(), bm);
        }
        if (!bindingsMap.isEmpty()) {
            target.put("bindings", bindingsMap);
        }
    }
}
