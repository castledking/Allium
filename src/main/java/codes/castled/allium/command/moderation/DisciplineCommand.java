package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.moderation.DisciplineService;

import java.util.Objects;

/** Each registered spec supplies its canonical action, including when invoked through an alias. */
public final class DisciplineCommand implements SpecCommandHandler {
    private final DisciplineService service;
    private final CommandSpec spec;

    public DisciplineCommand(PluginStart plugin, DisciplineService service, CommandSpec spec) {
        Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override public CommandSpec spec() { return spec; }

    @Override public void execute(BukkitCommandContext context) {
        service.dispatch(context.sender(), context.spec().canonicalName(), context.args());
    }
}
