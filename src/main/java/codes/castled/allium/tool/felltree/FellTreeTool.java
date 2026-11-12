package codes.castled.allium.tool.felltree;

import codes.castled.allium.message.LangMessage;
import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One loaded {@code allium:fell_tree} definition at runtime. */
public final class FellTreeTool {

    private final ToolDefinition definition;
    private final FellTreeParameters parameters;
    private final Map<String, LangMessage> messages;

    public FellTreeTool(ToolDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (!FellTreeBehaviorType.ID.equals(definition.behavior())) {
            throw new IllegalArgumentException(definition.id() + " binds " + definition.behavior() + ", not " + FellTreeBehaviorType.ID);
        }
        this.parameters = definition.parameters(FellTreeParameters.class);
        Map<String, LangMessage> resolved = new LinkedHashMap<>();
        new FellTreeBehaviorType().messageSlots().forEach((slot, defaultKey) -> {
            Messages fallback = Messages.fromKey(defaultKey);
            if (fallback == null) {
                throw new IllegalStateException("message slot " + slot + " has no built-in entry " + defaultKey);
            }
            resolved.put(slot, new LangMessage(definition.message(slot), fallback));
        });
        this.messages = Map.copyOf(resolved);
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

    public FellTreeParameters parameters() {
        return parameters;
    }

    public String usePermission() {
        return definition.permission(FellTreeBehaviorType.PERMISSION_USE);
    }

    public LangMessage message(String slot) {
        LangMessage message = messages.get(slot);
        if (message == null) {
            throw new IllegalArgumentException("unknown message slot " + slot);
        }
        return message;
    }
}
