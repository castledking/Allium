package codes.castled.allium.permissions.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import org.bukkit.command.Command;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;

final class PaperBasicCommandPermissionAdapter implements CommandPermissionAdapter {

    @Override
    public Optional<PermissionResult> resolve(CommandPermissionContext context) {
        BasicCommand basicCommand = findBasicCommand(context.command());
        if (basicCommand == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new PermissionResult(
                    basicCommand.canUse(context.player()),
                    ResolutionType.PAPER_BASIC,
                    basicCommand.permission(),
                    context.command()
            ));
        } catch (Throwable ignored) {
            // The command is a Paper BasicCommand, but its predicate is not safely inspectable.
            return Optional.of(PermissionResult.unknown(true, context.command()));
        }
    }

    private BasicCommand findBasicCommand(Command command) {
        for (Class<?> type = command.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !BasicCommand.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(command);
                    if (value instanceof BasicCommand basicCommand) {
                        return basicCommand;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
