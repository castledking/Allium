package codes.castled.allium.command;

import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CommandUsage;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handler for a command family backed by a platform-neutral {@link CommandSpec}.
 *
 * <p>Command families own their own alias argument rewrites ({@link #transformExecutionArgs},
 * {@link #transformCompletionArgs}) and optional custom completions ({@link #complete}) so
 * {@link BukkitCommandRenderer} stays free of command-specific special cases.
 */
public interface SpecCommandHandler {

    /** Result of rewriting arguments for a family-owned shorthand alias (e.g. {@code /gmc}, {@code /rain}). */
    sealed interface AliasTransform permits AliasTransform.Pass, AliasTransform.NoCompletion, AliasTransform.InvalidUsage {
        record Pass(String[] args) implements AliasTransform {}
        record NoCompletion() implements AliasTransform {
            public static final NoCompletion INSTANCE = new NoCompletion();
        }
        record InvalidUsage(List<CommandUsage> usages) implements AliasTransform {}
    }

    /**
     * Returns the platform-neutral specification for this command family.
     */
    CommandSpec spec();

    /**
     * Executes the command after base permission and disabled-family checks have passed.
     */
    void execute(BukkitCommandContext context);

    /**
     * Transforms raw arguments for shorthand aliases owned by this command family prior to execution.
     */
    default AliasTransform transformExecutionArgs(String label, String[] args) {
        return new AliasTransform.Pass(args);
    }

    /**
     * Transforms raw arguments for shorthand aliases owned by this command family prior to completion.
     */
    default AliasTransform transformCompletionArgs(String label, String[] args) {
        return transformExecutionArgs(label, args);
    }

    /**
     * Optional custom tab completion for this command family. Returning {@link java.util.Optional#empty()}
     * falls back to {@link CommandSpec#complete}.
     */
    default java.util.Optional<List<String>> customCompletions(CommandSender sender, String label, String[] args) {
        return java.util.Optional.empty();
    }

    /** Utility for command families that prepend a fixed sub-argument for shorthand aliases. */
    static String[] prepend(String first, String[] args) {
        String[] expanded = new String[args.length + 1];
        expanded[0] = first;
        System.arraycopy(args, 0, expanded, 1, args.length);
        return expanded;
    }

    /** A command whose synchronous effect can be confirmed by a paid sign. */
    interface Immediate extends SpecCommandHandler {
        boolean executeWithResult(BukkitCommandContext context);

        @Override
        default void execute(BukkitCommandContext context) {
            executeWithResult(context);
        }
    }
}
