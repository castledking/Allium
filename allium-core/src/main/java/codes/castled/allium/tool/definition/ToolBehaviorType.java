package codes.castled.allium.tool.definition;

import java.util.Map;

/**
 * A core behavior that tool definitions can bind to, such as {@code allium:disarm}. The behavior
 * owns how the tool acts; a definition only supplies the parameters it declares here. Slot maps
 * keep their declaration order, which diagnostics and docs use.
 *
 * @param <P> the validated parameters for one definition
 */
public interface ToolBehaviorType<P extends ToolParameters> {

    /** The behavior ID definitions name in {@code behavior:}. */
    String id();

    /** Permission slots (e.g. {@code use}, {@code bypass}) and who holds an undeclared node. */
    Map<String, PermissionDefault> permissionSlots();

    /** Message slots (e.g. {@code disarmed}) and the lang key each uses by default. */
    Map<String, String> messageSlots();

    /** Sound slots (e.g. {@code disarm}) and their defaults. */
    Map<String, ToolSound> soundSlots();

    /**
     * Reads this behavior's own settings from the definition's root section. Problems are
     * recorded on the reader; the returned value is only used when there are none.
     */
    P parse(ConfigReader root, ToolEnvironment environment);
}
