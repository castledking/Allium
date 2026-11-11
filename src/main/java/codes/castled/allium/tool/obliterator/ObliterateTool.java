package codes.castled.allium.tool.obliterator;

import codes.castled.allium.message.LangMessage;
import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolSound;
import codes.castled.allium.tool.disarmer.ClickGuard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** One loaded {@code allium:obliterate_phantoms} definition at runtime, with its per-player state. */
public final class ObliterateTool {

    private final ToolDefinition definition;
    private final ObliterateParameters parameters;
    private final Map<String, LangMessage> messages;
    private final ClickGuard clickGuard;
    private final Map<UUID, Long> lastStatus = new ConcurrentHashMap<>();

    public ObliterateTool(ToolDefinition definition, LongSupplier nanoClock) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (!ObliterateBehaviorType.ID.equals(definition.behavior())) {
            throw new IllegalArgumentException(definition.id() + " binds " + definition.behavior() + ", not " + ObliterateBehaviorType.ID);
        }
        this.parameters = definition.parameters(ObliterateParameters.class);
        Map<String, LangMessage> resolved = new LinkedHashMap<>();
        new ObliterateBehaviorType().messageSlots().forEach((slot, defaultKey) -> {
            Messages fallback = Messages.fromKey(defaultKey);
            if (fallback == null) {
                throw new IllegalStateException("message slot " + slot + " has no built-in entry " + defaultKey);
            }
            resolved.put(slot, new LangMessage(definition.message(slot), fallback));
        });
        this.messages = Map.copyOf(resolved);
        this.clickGuard = new ClickGuard(parameters.clickGuardMillis(), nanoClock);
    }

    public String id() {
        return definition.id();
    }

    public String name() {
        return definition.name();
    }

    public ToolDefinition definition() {
        return definition;
    }

    public ObliterateParameters parameters() {
        return parameters;
    }

    public String usePermission() {
        return definition.permission(ObliterateBehaviorType.PERMISSION_USE);
    }

    public String bypassPermission() {
        return definition.permission(ObliterateBehaviorType.PERMISSION_BYPASS);
    }

    public LangMessage message(String slot) {
        LangMessage message = messages.get(slot);
        if (message == null) {
            throw new IllegalArgumentException("unknown message slot " + slot);
        }
        return message;
    }

    public ToolSound sound(String slot) {
        return definition.sound(slot);
    }

    public List<String> particles(String slot) {
        return definition.particles(slot);
    }

    ClickGuard clickGuard() {
        return clickGuard;
    }

    boolean admitStatus(UUID playerId, long now) {
        Long last = lastStatus.get(playerId);
        if (last != null && now - last < parameters.statusCooldownMillis()) {
            return false;
        }
        lastStatus.put(playerId, now);
        return true;
    }

    void forget(UUID playerId) {
        clickGuard.forget(playerId);
        lastStatus.remove(playerId);
    }
}
