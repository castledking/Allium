package codes.castled.allium.permissions.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCandidatesTest {

    @Test
    void resolvesExcellentShopAuctionModuleForAhOpen() {
        Command command = new StubCommand("auction", List.of("ah"));
        CommandPermissionContext context = context(
                command,
                "ah open",
                "ah",
                List.of("open"),
                "ExcellentShop",
                Set.of(
                        "excellentshop.virtual.command.open",
                        "excellentshop.chestshop.command.open",
                        "excellentshop.auction.command.open"
                )
        );

        List<String> candidates = PermissionCandidates.forContext(context);

        assertEquals("excellentshop.auction.command.open", candidates.getFirst());
    }

    @Test
    void treatsAhWithoutArgumentsAsAnOpenAction() {
        Command command = new StubCommand("auction", List.of("ah"));
        CommandPermissionContext context = context(
                command,
                "ah",
                "ah",
                List.of(),
                "ExcellentShop",
                Set.of(
                        "excellentshop.virtual.command.open",
                        "excellentshop.chestshop.command.open",
                        "excellentshop.auction.command.open"
                )
        );

        List<String> candidates = PermissionCandidates.forContext(context);

        assertEquals("excellentshop.auction.command.open", candidates.getFirst());
    }

    @Test
    void includesModulelessJobsPermissionBeforeWildcards() {
        Command command = new StubCommand("jobs", List.of());
        CommandPermissionContext context = context(
                command,
                "jobs",
                "jobs",
                List.of(),
                "EcoJobs",
                Set.of("ecojobs.command.jobs")
        );

        List<String> candidates = PermissionCandidates.forContext(context);

        assertEquals("ecojobs.command.jobs", candidates.getFirst());
        assertTrue(candidates.indexOf("ecojobs.command.jobs") < candidates.indexOf("ecojobs.*"));
    }

    private CommandPermissionContext context(Command command,
                                             String line,
                                             String label,
                                             List<String> arguments,
                                             String pluginName,
                                             Set<String> permissions) {
        return new CommandPermissionContext(
                player(),
                line,
                label,
                arguments,
                command,
                pluginName,
                permissions
        );
    }

    private Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static class StubCommand extends Command {

        private StubCommand(String name, List<String> aliases) {
            super(name, "", "/" + name, aliases);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return true;
        }
    }
}
