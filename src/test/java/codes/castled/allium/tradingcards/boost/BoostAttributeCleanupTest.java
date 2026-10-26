package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoostAttributeCleanupTest {
    private final UUID playerId = UUID.randomUUID();
    private final Attribute scale = new TestAttribute("scale");
    private final Attribute flight = new TestAttribute("flying_speed");
    private final Map<Attribute, Map<UUID, AttributeModifier>> attributes = new HashMap<>();
    private final Map<Attribute, AttributeInstance> instances = new HashMap<>();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[]{Player.class}, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getAttribute" -> instance((Attribute) args[0]);
            default -> throw new UnsupportedOperationException(method.getName());
        });

    @Test
    void unequipRemovesScaleAndFlightWhilePreservingOtherModifiers() {
        var service = service();
        UUID other = UUID.randomUUID();
        instance(scale).addModifier(new AttributeModifier(other, "another-plugin", 0.25,
            AttributeModifier.Operation.ADD_NUMBER));
        assertEquals(List.of("scale", "flight-speed"), service.apply(player, card("scale", "flight-speed")));
        assertEquals(2, attributes.get(scale).size());
        assertEquals(1, attributes.get(flight).size());

        service.remove(player);
        assertEquals(java.util.Set.of(other), attributes.get(scale).keySet(),
            "unequip must remove only the card's scale modifier");
        assertTrue(attributes.get(flight).isEmpty(), "flight speed must reset with the card too");
        service.remove(player);
        assertEquals(java.util.Set.of(other), attributes.get(scale).keySet());
    }

    @Test
    void refreshAndSwapCannotStackOrLeaveScaleOnACardWithoutIt() {
        var service = service();
        service.apply(player, card("scale"));
        assertDoesNotThrow(() -> service.apply(player, card("scale")),
            "level-ups and bonus changes must replace the existing modifier");
        assertEquals(1, attributes.get(scale).size());
        service.apply(player, card());
        assertTrue(attributes.get(scale).isEmpty(), "a replacement card without scale must restore size");
    }

    @Test
    void aNewServiceCanRepairOrphanedModifiersWithoutAnEquippedCard() {
        service().apply(player, card("scale", "flight-speed"));
        UUID other = UUID.randomUUID();
        instance(scale).addModifier(new AttributeModifier(other, "another-plugin", 0.25,
            AttributeModifier.Operation.ADD_NUMBER));
        // The previous version forgot its record on unequip; a reload also loses the map.
        var reloaded = new BoostService(Logger.getAnonymousLogger(), null, this::attribute);
        reloaded.remove(player);
        assertEquals(java.util.Set.of(other), attributes.get(scale).keySet());
        assertTrue(attributes.get(flight).isEmpty());
    }

    @Test
    void reEquippingAfterReloadReplacesAnOrphanedScaleModifier() {
        service().apply(player, card("scale"));
        var reloaded = service();
        assertDoesNotThrow(() -> reloaded.apply(player, card("scale")));
        assertEquals(1, attributes.get(scale).size());
        reloaded.remove(player);
        assertTrue(attributes.get(scale).isEmpty());
    }

    @Test
    void recordedCustomScaleIsRemovedEvenAfterItsDefinitionIsDeleted() {
        var service = service();
        var custom = new BoostDefinition("giant", "Giant", BoostMechanism.CARD_SCALE,
            1.0, 0, false, Map.of());
        service.configure(new BoostCatalog.LoadResult(Map.of("giant", custom), Map.of(),
            Map.of(), Map.of(), 3, 0, 5, List.of()), 1, 0.6, 1.9);
        service.apply(player, card("giant"));
        assertEquals(1, attributes.get(scale).size());
        service.configure(new BoostCatalog.LoadResult(Map.of(), Map.of(), Map.of(), Map.of(),
            3, 0, 5, List.of()), 1, 0.6, 1.9);
        service.remove(player);
        assertTrue(attributes.get(scale).isEmpty(), "cleanup must use the applied record, not the current config");
    }

    @Test
    void logoutRemovesModifiersBeforeForgettingTheEquippedCard() {
        var service = service();
        var tracker = new EquippedCardTracker();
        var card = card("scale", "flight-speed");
        tracker.set(playerId, card, service.apply(player, card));
        var progression = new CardProgression(service, tracker,
            new CardProgression.ProgressRules(100, 1, 1), Logger.getAnonymousLogger());
        progression.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player,
            net.kyori.adventure.text.Component.empty()));
        assertTrue(attributes.get(scale).isEmpty());
        assertTrue(attributes.get(flight).isEmpty());
        assertTrue(tracker.isEmpty(playerId));
    }

    @Test
    void theScaleModifierMakesThePlayerTheRolledSize() {
        // MULTIPLY_SCALAR_1 multiplies by (1 + modifier), so a size is applied as size - 1.
        var service = service();
        service.configure(service.catalog(), 1, BonusStrength.SCALE_MIN, BonusStrength.SCALE_MAX);
        assertEquals(-0.4, scaleModifier(service, 0), 1e-9, "the weakest roll is x0.6");
        assertEquals(1.0, scaleModifier(service, 100), 1e-9, "the strongest roll is x2.0");
        assertEquals(1.0, scaleModifier(service, null), 1e-9,
            "a scale bonus from before strengths keeps the x2.0 it gave");
    }

    private double scaleModifier(BoostService service, Integer strength) {
        var card = new TradingCardData("ALLAY", "allay", Tier.FABLED, 1, 100, List.of(),
            List.of("scale"), 0.0, 0, false, java.util.Arrays.asList(strength));
        service.apply(player, card);
        var modifiers = attributes.get(scale).values();
        assertEquals(1, modifiers.size());
        return modifiers.iterator().next().getAmount();
    }

    private Attribute attribute(String name) {
        return switch (name) { case "scale" -> scale; case "flying_speed" -> flight; default -> null; };
    }

    private BoostService service() {
        var service = new BoostService(Logger.getAnonymousLogger(), null, this::attribute);
        var boosts = Map.of(
            "scale", new BoostDefinition("scale", "Scale", BoostMechanism.CARD_SCALE,
                1.0, 0, false, Map.of()),
            "flight-speed", new BoostDefinition("flight-speed", "Flight", BoostMechanism.CARD_FLIGHT_SPEED,
                1.6, 0, false, Map.of()));
        service.configure(new BoostCatalog.LoadResult(boosts, Map.of(), Map.of(), Map.of(),
            3, 0, 5, List.of()), 1, 0.6, 1.9);
        return service;
    }

    private TradingCardData card(String... bonuses) {
        return new TradingCardData("ALLAY", "allay", Tier.FABLED, 1, 100,
            List.of(), List.of(bonuses), 0, false);
    }

    private AttributeInstance instance(Attribute attribute) {
        return instances.computeIfAbsent(attribute, key -> {
            var modifiers = attributes.computeIfAbsent(key, unused -> new HashMap<>());
            return (AttributeInstance) Proxy.newProxyInstance(AttributeInstance.class.getClassLoader(),
                new Class<?>[]{AttributeInstance.class}, (p, method, args) -> switch (method.getName()) {
                    case "addModifier" -> {
                        var modifier = (AttributeModifier) args[0];
                        if (modifiers.putIfAbsent(modifier.getUniqueId(), modifier) != null)
                            throw new IllegalArgumentException("Modifier is already applied");
                        yield null;
                    }
                    case "removeModifier" -> {
                        modifiers.remove(args[0] instanceof AttributeModifier m ? m.getUniqueId() : args[0]);
                        yield null;
                    }
                    case "getModifiers" -> List.copyOf(modifiers.values());
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        });
    }

    /** Implements the registry interface without initialising Bukkit's global attribute registry. */
    private record TestAttribute(String id) implements Attribute {
        @Override public NamespacedKey getKey() { return NamespacedKey.minecraft(id); }
        @Override public String getTranslationKey() { return "attribute.name." + id; }
        @Override public String translationKey() { return getTranslationKey(); }
        @Override public Attribute.Sentiment getSentiment() { return Attribute.Sentiment.POSITIVE; }
        @Override public double getDefaultValue() { return 1; }
        @Override public String name() { return id.toUpperCase(java.util.Locale.ROOT); }
        @Override public int ordinal() { return 0; }
        @Override public int compareTo(Attribute other) { return name().compareTo(other.name()); }
    }
}
