package codes.castled.allium.permissions.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.cacheddata.Result;
import net.luckperms.api.node.Node;
import net.luckperms.api.platform.PlayerAdapter;
import net.luckperms.api.util.Tristate;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPermissionAdaptersTest {

    @Test
    void explicitBukkitPermissionExplainsJobsDenialWithoutHeuristics() {
        Command command = new StubCommand("jobs");
        command.setPermission("ecojobs.command.jobs");
        CommandPermissionContext context = context(command, playerWith(Map.of(
                "ecojobs.command.jobs", false
        )));

        PermissionResult result = new BukkitCommandPermissionAdapter().resolve(context).orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.PLUGIN_METADATA, result.type());
        assertEquals("ecojobs.command.jobs", result.matchedPermission());
    }

    @Test
    void overriddenBukkitPermissionTestIsAuthoritative() {
        Command command = new OverridingCommand("framework", false);

        PermissionResult result = new BukkitCommandPermissionAdapter()
                .resolve(context(command, playerWith(Map.of())))
                .orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.BUKKIT, result.type());
    }

    @Test
    void evaluatesBrigadierRootRequirementInsteadOfBlindlyAllowingWrapper() {
        CommandNode<Object> node = LiteralArgumentBuilder.<Object>literal("secure")
                .requires(source -> false)
                .build();
        Command command = new TestVanillaCommandWrapper(node, "Plugin");

        PermissionResult result = new BrigadierCommandPermissionAdapter()
                .resolve(context(command, playerWith(Map.of())))
                .orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.BRIGADIER, result.type());
    }

    @Test
    void evaluatesBrigadierLiteralSubcommandRequirement() {
        CommandNode<Object> node = LiteralArgumentBuilder.<Object>literal("auction")
                .then(LiteralArgumentBuilder.<Object>literal("open").requires(source -> false))
                .build();
        Command command = new TestVanillaCommandWrapper(node, "ExcellentShop");
        CommandPermissionContext context = new CommandPermissionContext(
                playerWith(Map.of()),
                "ah OPEN",
                "ah",
                List.of("OPEN"),
                command,
                "ExcellentShop",
                Set.of("excellentshop.auction.command.open")
        );

        PermissionResult result = new BrigadierCommandPermissionAdapter()
                .resolve(context)
                .orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.BRIGADIER, result.type());
    }

    @Test
    void asksNightCoreLiteralNodeForItsPermission() {
        NightCoreLikeNode root = new NightCoreLikeNode("auction", null, true, null);
        root.addChild(new NightCoreLikeNode(
                "open",
                "excellentshop.auction.command.open",
                true,
                new Object()
        ));
        Command command = new NightCoreLikeCommand(root);
        CommandPermissionContext context = new CommandPermissionContext(
                playerWith(Map.of("excellentshop.auction.command.open", false)),
                "ah open",
                "ah",
                List.of("open"),
                command,
                "ExcellentShop",
                Set.of("excellentshop.auction.command.open")
        );

        PermissionResult result = new NightCoreCommandPermissionAdapter()
                .resolve(context)
                .orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.NIGHTCORE, result.type());
        assertEquals("excellentshop.auction.command.open", result.matchedPermission());
    }

    @Test
    void mapsNightCoreDefaultHubExecutorToItsOpenNode() {
        NightCoreLikeNode root = new NightCoreLikeNode("auction", null, true, new Object());
        root.addChild(new NightCoreLikeNode(
                "open",
                "excellentshop.auction.command.open",
                true,
                new Object()
        ));
        Command command = new NightCoreLikeCommand(root);
        CommandPermissionContext context = new CommandPermissionContext(
                playerWith(Map.of("excellentshop.auction.command.open", false)),
                "ah",
                "ah",
                List.of(),
                command,
                "ExcellentShop",
                Set.of("excellentshop.auction.command.open")
        );

        PermissionResult result = new NightCoreCommandPermissionAdapter()
                .resolve(context)
                .orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.NIGHTCORE, result.type());
        assertEquals("excellentshop.auction.command.open", result.matchedPermission());
    }

    @Test
    void vanillaBukkitPermissionBeatsOpLevelBrigadierRequirement() {
        CommandNode<Object> node = LiteralArgumentBuilder.<Object>literal("kill")
                .requires(source -> false) // vanilla source.hasPermission(2)
                .build();
        Command command = new TestVanillaCommandWrapper(node, "minecraft");
        command.setPermission("minecraft.command.kill");
        CommandPermissionContext context = new CommandPermissionContext(
                playerWith(Map.of("minecraft.command.kill", true)),
                "minecraft:kill ggpots",
                "kill",
                List.of("ggpots"),
                command,
                "minecraft",
                Set.of("minecraft.command.kill")
        );

        PermissionResult result = new BrigadierCommandPermissionAdapter().resolve(context).orElseThrow();

        assertTrue(result.allowed());
        assertEquals(ResolutionType.VANILLA, result.type());
        assertEquals("minecraft.command.kill", result.matchedPermission());
    }

    @Test
    void reportsUnknownWhenBrigadierWrapperCannotBeInspected() {
        Command command = new BrokenVanillaCommandWrapper();

        PermissionResult result = new BrigadierCommandPermissionAdapter()
                .resolve(context(command, playerWith(Map.of())))
                .orElseThrow();

        assertTrue(result.allowed());
        assertEquals(ResolutionType.UNKNOWN, result.type());
    }

    @Test
    void asksPaperBasicCommandCanUsePredicate() {
        Command command = new PaperWrappedCommand(new BasicCommand() {
            @Override
            public void execute(CommandSourceStack commandSourceStack, String[] args) {
            }

            @Override
            public boolean canUse(CommandSender sender) {
                return false;
            }

            @Override
            public String permission() {
                return "paper.basic.secure";
            }
        });

        PermissionResult result = new PaperBasicCommandPermissionAdapter()
                .resolve(context(command, playerWith(Map.of())))
                .orElseThrow();

        assertFalse(result.allowed());
        assertEquals(ResolutionType.PAPER_BASIC, result.type());
        assertEquals("paper.basic.secure", result.matchedPermission());
    }

    @Test
    void luckPermsReportsTheWildcardNodeThatCausedTheDecision() throws ReflectiveOperationException {
        Command command = new StubCommand("auction", List.of("ah"));
        Player player = playerWith(Map.of());
        CommandPermissionContext context = new CommandPermissionContext(
                player,
                "ah open",
                "ah",
                List.of("open"),
                command,
                "ExcellentShop",
                Set.of("excellentshop.auction.command.open")
        );

        Node causingNode = proxy(Node.class, (method, args) -> switch (method.getName()) {
            case "getKey" -> "excellentshop.*";
            case "getValue" -> false;
            default -> defaultValue(method.getReturnType());
        });
        Result<Tristate, Node> denied = proxy(Result.class, (method, args) -> switch (method.getName()) {
            case "result" -> Tristate.FALSE;
            case "node" -> causingNode;
            default -> defaultValue(method.getReturnType());
        });
        Result<Tristate, Node> undefined = proxy(Result.class, (method, args) -> switch (method.getName()) {
            case "result" -> Tristate.UNDEFINED;
            case "node" -> null;
            default -> defaultValue(method.getReturnType());
        });
        CachedPermissionData permissionData = proxy(CachedPermissionData.class, (method, args) -> {
            if (method.getName().equals("queryPermission")) {
                return "excellentshop.auction.command.open".equals(args[0]) ? denied : undefined;
            }
            return defaultValue(method.getReturnType());
        });
        PlayerAdapter<Player> playerAdapter = proxy(PlayerAdapter.class, (method, args) ->
                method.getName().equals("getPermissionData")
                        ? permissionData
                        : defaultValue(method.getReturnType())
        );
        LuckPerms luckPerms = proxy(LuckPerms.class, (method, args) ->
                method.getName().equals("getPlayerAdapter")
                        ? playerAdapter
                        : defaultValue(method.getReturnType())
        );

        Field providerInstance = LuckPermsProvider.class.getDeclaredField("instance");
        providerInstance.setAccessible(true);
        Object previous = providerInstance.get(null);
        try {
            providerInstance.set(null, luckPerms);

            PermissionResult result = new LuckPermsCommandPermissionAdapter()
                    .resolve(context)
                    .orElseThrow();

            assertFalse(result.allowed());
            assertEquals(ResolutionType.LUCKPERMS, result.type());
            assertEquals("excellentshop.*", result.matchedPermission());
        } finally {
            providerInstance.set(null, previous);
        }
    }

    private CommandPermissionContext context(Command command, Player player) {
        return new CommandPermissionContext(
                player,
                command.getName(),
                command.getName(),
                List.of(),
                command,
                "TestPlugin",
                Set.of()
        );
    }

    private Player playerWith(Map<String, Boolean> permissions) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args != null && args.length == 1
                            && args[0] instanceof String permission) {
                        return permissions.getOrDefault(permission, false);
                    }
                    if (method.getName().equals("isPermissionSet") && args != null && args.length == 1
                            && args[0] instanceof String permission) {
                        return permissions.containsKey(permission);
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyAnswer answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> answer.answer(method, args)
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

    @FunctionalInterface
    private interface ProxyAnswer {
        Object answer(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static class StubCommand extends Command {

        private StubCommand(String name) {
            super(name);
        }

        private StubCommand(String name, List<String> aliases) {
            super(name, "", "/" + name, aliases);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return true;
        }
    }

    private static final class OverridingCommand extends StubCommand {

        private final boolean allowed;

        private OverridingCommand(String name, boolean allowed) {
            super(name);
            this.allowed = allowed;
        }

        @Override
        public boolean testPermissionSilent(CommandSender target) {
            return allowed;
        }
    }

    private static final class TestVanillaCommandWrapper extends StubCommand {

        public final CommandNode<Object> vanillaCommand;
        public final String helpCommandNamespace;

        private TestVanillaCommandWrapper(CommandNode<Object> vanillaCommand, String helpCommandNamespace) {
            super("secure");
            this.vanillaCommand = vanillaCommand;
            this.helpCommandNamespace = helpCommandNamespace;
        }

        public static Object getListener(CommandSender sender) {
            return sender;
        }
    }

    private static final class BrokenVanillaCommandWrapper extends StubCommand {

        private BrokenVanillaCommandWrapper() {
            super("broken");
        }
    }

    private static final class PaperWrappedCommand extends StubCommand {

        private final BasicCommand basicCommand;

        private PaperWrappedCommand(BasicCommand basicCommand) {
            super("paperbasic");
            this.basicCommand = basicCommand;
        }
    }

    private static final class NightCoreLikeCommand extends StubCommand {

        private final NightCoreLikeNode root;

        private NightCoreLikeCommand(NightCoreLikeNode root) {
            super("auction", List.of("ah"));
            this.root = root;
        }

        public NightCoreLikeNode getRoot() {
            return root;
        }
    }

    private static final class NightCoreLikeNode {

        private final String name;
        private final String permission;
        private final boolean requirementsAllowed;
        private final Object executor;
        private final Map<String, NightCoreLikeNode> children = new LinkedHashMap<>();

        private NightCoreLikeNode(String name,
                                  String permission,
                                  boolean requirementsAllowed,
                                  Object executor) {
            this.name = name;
            this.permission = permission;
            this.requirementsAllowed = requirementsAllowed;
            this.executor = executor;
        }

        public boolean hasPermission(CommandSender sender) {
            return permission == null || sender.hasPermission(permission);
        }

        public boolean canUse(CommandSender sender) {
            return requirementsAllowed;
        }

        public String getPermission() {
            return permission;
        }

        public NightCoreLikeNode getChild(String childName) {
            return children.get(childName);
        }

        public Collection<NightCoreLikeNode> getChildren() {
            return children.values();
        }

        public List<Object> getRequirements() {
            return requirementsAllowed ? List.of() : List.of(new Object());
        }

        public Object getExecutor() {
            return executor;
        }

        private void addChild(NightCoreLikeNode child) {
            children.put(child.name, child);
        }
    }
}
