package codes.castled.curios;

import codes.castled.allium.api.ItemIdentity;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CardBoostRuntimeTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (var in = CardBoostRuntimeTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigDefinesAllNineAntiquitiesRelicAbilitiesAndAttributes() throws Exception {
        Catalog catalog = new Catalog(bundledConfig());

        assertTrue(catalog.entries.get("evokers_mantle").abilities().contains(Catalog.Ability.SUMMON_VEX));
        assertEquals(600, catalog.entries.get("evokers_mantle").effectTicks("cooldown-ticks", 0));
        assertEquals(1, catalog.entries.get("evokers_mantle").attributes().size());

        assertTrue(catalog.entries.get("strider_hide_girdle").abilities().contains(Catalog.Ability.LAVA_WALKER));
        assertEquals(3, catalog.entries.get("strider_hide_girdle").effectTicks("radius", 0));
        assertEquals(2.0, catalog.entries.get("strider_hide_girdle").attributes().get(0).amount());

        assertTrue(catalog.entries.get("chorus_buckle").abilities().contains(Catalog.Ability.CHORUS_BLINK));
        assertEquals(16, catalog.entries.get("chorus_buckle").effectTicks("radius", 0));
        assertEquals(0.5, catalog.entries.get("chorus_buckle").attributes().get(0).amount());

        assertEquals(2, catalog.entries.get("piglin_signet").attributes().size());
        assertEquals(1, catalog.entries.get("echoing_circlet").attributes().size());
        assertEquals(1, catalog.entries.get("idol_of_depths").attributes().size());
        assertEquals(1, catalog.entries.get("membrane_cowl").attributes().size());
        assertEquals(1, catalog.entries.get("witchs_brewstone").attributes().size());
    }

    @Test
    void aggregatesRelicAttributesSignaturesAndAllCardBonuses() throws Exception {
        YamlConfiguration cfg = bundledConfig();
        Catalog catalog = new Catalog(cfg);
        CardRules rules = new CardRules(cfg);

        Catalog.Entry relic = catalog.entries.get("strider_hide_girdle");
        Catalog.Entry fabledCard = catalog.entries.get("card_zombie_fabled");
        Catalog.Entry legendaryCard = catalog.entries.get("card_blaze_legendary");

        ItemIdentity fabledId = new ItemIdentity("allium:card_zombie_fabled", Optional.of("curios:card_zombie_fabled"), Map.of(
                "level", "20",
                "quality", "100",
                "signatures", "luck,strength,speed,health,wisdom",
                "bonuses", "crop-boost:100:U:1,xp-drop-boost:100:U:1,disarm-rate:100:U:1,flight-speed:100:U:1,waypoint-multiplier:100:U:1"
        ));
        ItemIdentity legendaryId = new ItemIdentity("allium:card_blaze_legendary", Optional.of("curios:card_blaze_legendary"), Map.of(
                "level", "10",
                "quality", "80",
                "signatures", "luck,strength,speed,health",
                "bonuses", "armor:100:U:1,haste:100:U:1,waypoint-range:100:U:1,scale:100:U:1,"
        ));

        var snap = CardBoostRuntime.aggregate(List.of(
                new CardBoostRuntime.EquippedItem(relic, null),
                new CardBoostRuntime.EquippedItem(fabledCard, fabledId),
                new CardBoostRuntime.EquippedItem(legendaryCard, legendaryId)
        ), rules);

        assertEquals(1, snap.relicAttributes().size());
        assertTrue(snap.signatureStats().get("strength") > 10.0);
        assertTrue(snap.signatureStats().get("wisdom") > 5.0);
        assertEquals(4.0, snap.armor(), 1e-6);
        assertEquals(0.25, snap.haste(), 1e-6);
        assertEquals(0.25, snap.cropQualityBonus(), 1e-6);
        assertEquals(1.30, snap.xpMultiplier(), 1e-6);
        assertEquals(0.15, snap.disarmRate(), 1e-6);
        assertEquals(1.6, snap.flightSpeed(), 1e-6);
        assertEquals(64.0, snap.locatorRangeBonus(), 1e-6);
        assertEquals(2.0, snap.locatorRangeMultiplier(), 1e-6);
        assertEquals(1.25, snap.scale(), 1e-6);
    }

    @Test
    void runtimeExecutesDisarmXpLootRegenFlySpeedAndTokenDrops() throws Exception {
        YamlConfiguration cfg = bundledConfig();
        Catalog catalog = new Catalog(cfg);
        CardRules rules = new CardRules(cfg);

        CuriosPlugin plugin = mock(CuriosPlugin.class);
        when(plugin.catalog()).thenReturn(catalog);
        when(plugin.cardRules()).thenReturn(rules);

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("curios.use")).thenReturn(true);
        when(player.getFlySpeed()).thenReturn(0.1f);

        Map<Attribute, List<AttributeModifier>> modifierStore = new EnumMap<>(Attribute.class);
        for (Attribute attr : Attribute.values()) {
            AttributeInstance inst = mock(AttributeInstance.class);
            List<AttributeModifier> mods = new ArrayList<>();
            modifierStore.put(attr, mods);
            when(inst.getModifiers()).thenAnswer(inv -> List.copyOf(mods));
            doAnswer(inv -> { mods.add(inv.getArgument(0)); return null; }).when(inst).addModifier(any());
            doAnswer(inv -> { mods.remove(inv.getArgument(0)); return null; }).when(inst).removeModifier(any(AttributeModifier.class));
            when(player.getAttribute(attr)).thenReturn(inst);
        }

        Catalog.Entry cardEntry = catalog.entries.get("card_zombie_fabled");
        ItemStack equippedCard = mock(ItemStack.class);
        ItemIdentity id = new ItemIdentity("allium:card_zombie_fabled", Optional.of("curios:card_zombie_fabled"), Map.of(
                "level", "0",
                "quality", "100",
                "signatures", "luck,strength,speed,health,regeneration",
                "bonuses", "disarm-rate:100:U:1,xp-drop-boost:100:U:1,loot-boost:100:U:1,flight-speed:100:U:1,token-drop-chance:100:U:1"
        ));
        Catalog.Slot firstCardSlot = catalog.slots.stream().filter(s -> s.type().equals("card")).findFirst().orElseThrow();
        when(plugin.equipped(player, firstCardSlot)).thenReturn(equippedCard);
        when(plugin.entry(equippedCard)).thenReturn(cardEntry);
        when(plugin.identity(equippedCard)).thenReturn(Optional.of(id));

        // Deterministic random always returns 0.0 -> disarm, loot bonus, and token fortune always proc
        CardBoostRuntime runtime = new CardBoostRuntime(plugin, null, () -> 0.0);
        runtime.refresh(player);

        // Fly speed boosted from 0.1f -> 0.16f
        verify(player).setFlySpeed(0.16f);
        // Locator radius helper never shrinks base
        assertEquals(100.0, runtime.effectiveLocatorRadius(playerId, 100.0), 1e-6);

        // Disarm clears non-player mob's main-hand weapon
        Zombie mob = mock(Zombie.class);
        EntityEquipment eq = mock(EntityEquipment.class);
        when(mob.isValid()).thenReturn(true);
        when(mob.getEquipment()).thenReturn(eq);
        when(eq.getItemInMainHand()).thenReturn(new ItemStack(Material.IRON_SWORD, 1));
        @SuppressWarnings("deprecation")
        EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(player, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        runtime.onCombatHit(hit);
        verify(eq).setItemInMainHand(null);

        // XP drop boost & loot boost on EntityDeathEvent
        when(mob.getKiller()).thenReturn(player);
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 1)));
        EntityDeathEvent death = new EntityDeathEvent(mob, drops, 10);
        runtime.onEntityDeath(death);
        assertEquals(13, death.getDroppedExp());
        assertEquals(2, drops.size());

        // Vanilla regeneration fallback boosts natural regen
        EntityRegainHealthEvent regen = new EntityRegainHealthEvent(player, 1.0, EntityRegainHealthEvent.RegainReason.SATIATED);
        runtime.onRegainHealth(regen);
        assertTrue(regen.getAmount() > 1.0);

        // Token drop chance doubles drop on winning roll
        assertEquals(20.0, runtime.applyTokenDropBoost(playerId, 10.0, 0.0), 1e-6);
        assertEquals(10.0, runtime.applyTokenDropBoost(playerId, 10.0, 0.99), 1e-6);

        // Clear restores saved fly speed and removes all applied modifiers
        runtime.clear(player);
        verify(player).setFlySpeed(0.1f);
        for (List<AttributeModifier> mods : modifierStore.values()) {
            assertTrue(mods.isEmpty(), "Expected all Curios modifiers cleared");
        }
    }
}
