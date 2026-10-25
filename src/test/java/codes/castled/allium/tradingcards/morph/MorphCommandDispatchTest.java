package codes.castled.allium.tradingcards.morph;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MorphCommandDispatchTest {

    private final List<String> commands = new ArrayList<>();
    private final ConsoleCommandSender console = proxy(ConsoleCommandSender.class, (p, m, a) -> null);
    private boolean commandsAvailable = true;
    private boolean dispatchAccepted = true;
    private boolean dispatchThrows;
    private double health = 20.0;
    private final UUID playerId = UUID.randomUUID();
    private final Player player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
        case "getName" -> "CardHolder";
        case "getUniqueId" -> playerId;
        case "getHealth" -> health;
        default -> throw new UnsupportedOperationException(m.getName());
    });
    private final PluginManager pluginManager = proxy(PluginManager.class, (p, m, a) ->
        m.getName().equals("isPluginEnabled") && a[0].equals("LibsDisguises"));
    private final Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
        case "getPluginManager" -> pluginManager;
        case "getPluginCommand" -> commandsAvailable ? testCommand((String) a[0]) : null;
        case "getConsoleSender" -> console;
        case "dispatchCommand" -> {
            assertSame(console, a[0], "morph commands must run as console");
            commands.add((String) a[1]);
            if (dispatchThrows) throw new CommandException("Command failed");
            yield dispatchAccepted;
        }
        default -> throw new UnsupportedOperationException(m.getName());
    });
    private final Plugin plugin = proxy(Plugin.class, (p, m, a) -> switch (m.getName()) {
        case "getServer" -> server;
        default -> throw new UnsupportedOperationException(m.getName());
    });

    @Test
    void fabledCardTogglesUsingConsoleAndKeepsTargetingOption() {
        MorphService service = service(0, 0.0);
        assertTrue(service.toggle(player, card(Tier.FABLED)).morphed());
        assertEquals(EntityType.ZOMBIE, service.formOf(player));
        assertFalse(service.toggle(player, null).morphed());
        assertEquals(List.of("disguiseplayer CardHolder zombie setSelfDisguiseVisible true",
            "undisguiseplayer CardHolder"), commands);
        assertFalse(service.isMorphed(player));
    }

    @Test
    void missingOrLowerTierCardDoesNotDispatchAnything() {
        MorphService service = service(0, 0.0);
        assertEquals(MorphService.Denial.NO_CARD, service.toggle(player, null).denial());
        assertEquals(MorphService.Denial.TOO_LOW_TIER,
            service.toggle(player, card(Tier.LEGENDARY)).denial());
        assertTrue(commands.isEmpty());
    }

    @Test
    void timerRemovesMorphExactlyOnce() throws InterruptedException {
        MorphService service = service(1, 0.0);
        assertTrue(service.toggle(player, card(Tier.FABLED)).morphed());
        assertFalse(service.tick(player));
        Thread.sleep(1100);
        assertTrue(service.tick(player));
        assertFalse(service.tick(player));
        assertEquals(List.of("disguiseplayer CardHolder zombie setSelfDisguiseVisible true",
            "undisguiseplayer CardHolder"), commands);
    }

    @Test
    void zeroDurationStaysUntilHealthCutoffOrQuit() {
        MorphService service = service(0, 10.0);
        service.toggle(player, card(Tier.FABLED));
        assertFalse(service.tick(player));
        health = 10.0;
        assertTrue(service.tick(player));
        assertEquals("undisguiseplayer CardHolder", commands.getLast());
        health = 20.0;
        service.toggle(player, card(Tier.FABLED));
        service.handleQuit(player);
        assertFalse(service.isMorphed(player));
        assertEquals(4, commands.size());
        assertEquals("undisguiseplayer CardHolder", commands.getLast());
    }

    @Test
    void unavailableCommandsAndFailedDispatchDoNotStartMorph() {
        commandsAvailable = false;
        DisguiseBridge bridge = new DisguiseBridge(plugin, Logger.getAnonymousLogger());
        assertFalse(bridge.initialise());
        assertFalse(bridge.disguise(player, EntityType.ZOMBIE, true));
        assertTrue(commands.isEmpty());
        commandsAvailable = true;
        MorphService service = service(0, 0.0);
        dispatchAccepted = false;
        assertEquals(MorphService.Denial.FAILED, service.toggle(player, card(Tier.FABLED)).denial());
        assertFalse(service.isMorphed(player));
        dispatchThrows = true;
        assertEquals(MorphService.Denial.FAILED, service.toggle(player, card(Tier.FABLED)).denial());
        assertFalse(service.isMorphed(player));
    }

    @Test
    void disguiseCommandExplicitlyEnablesSelfView() {
        DisguiseBridge bridge = new DisguiseBridge(plugin, Logger.getAnonymousLogger());
        assertTrue(bridge.initialise());
        assertTrue(bridge.disguise(player, EntityType.CAVE_SPIDER, false));
        assertEquals(List.of("disguiseplayer CardHolder cave_spider setSelfDisguiseVisible true"), commands);
    }

    private MorphService service(int duration, double healthFloor) {
        DisguiseBridge bridge = new DisguiseBridge(plugin, Logger.getAnonymousLogger());
        assertTrue(bridge.initialise(), "no LibsDisguises API classes should be required");
        return new MorphService(bridge,
            new MorphService.Config(true, Tier.FABLED, duration, healthFloor, false, true, true));
    }

    private TradingCardData card(Tier tier) {
        return new TradingCardData("ZOMBIE", "zombie", tier, 1, 100, List.of(), 0, false);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private PluginCommand testCommand(String name) throws ReflectiveOperationException {
        var constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, plugin);
    }
}
