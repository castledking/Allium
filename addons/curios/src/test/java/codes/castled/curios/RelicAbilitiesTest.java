package codes.castled.curios;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelicAbilitiesTest {

    private static Catalog bundledCatalog() throws Exception {
        try (var in = RelicAbilitiesTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            return new Catalog(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    @Test
    void evokersMantleSummonsLoyalVexesBelowThresholdAndDespawnsOnQuit() throws Exception {
        Catalog catalog = bundledCatalog();
        Catalog.Entry mantle = catalog.entries.get("evokers_mantle");

        CuriosPlugin plugin = mock(CuriosPlugin.class);
        when(plugin.getName()).thenReturn("Curios");
        AddonConnection core = mock(AddonConnection.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.core()).thenReturn(core);
        when(core.ownsEntity(any())).thenReturn(true);
        when(core.runForEntity(any(), any(), any(), anyLong())).thenReturn(TaskHandle.NONE);

        Player wearer = mock(Player.class);
        UUID wearerId = UUID.randomUUID();
        when(wearer.getUniqueId()).thenReturn(wearerId);
        when(wearer.getHealth()).thenReturn(8.0);
        Attribute maxHealth = CardBoostRuntime.resolveAttribute("max_health");
        AttributeInstance healthAttr = mock(AttributeInstance.class);
        when(healthAttr.getValue()).thenReturn(20.0);
        when(wearer.getAttribute(maxHealth)).thenReturn(healthAttr);

        World world = mock(World.class);
        when(wearer.getWorld()).thenReturn(world);
        when(wearer.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(plugin.abilityEntry(wearer, Catalog.Ability.SUMMON_VEX)).thenReturn(mantle);

        List<Vex> spawned = new ArrayList<>();
        when(world.spawn(any(Location.class), eq(Vex.class), any())).thenAnswer(inv -> {
            Vex vex = mock(Vex.class);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            Map<Object, String> store = new HashMap<>();
            when(vex.getPersistentDataContainer()).thenReturn(pdc);
            doAnswer(c -> { store.put(c.getArgument(0), c.getArgument(2)); return null; })
                    .when(pdc).set(any(), eq(PersistentDataType.STRING), anyString());
            when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(c -> store.get(c.getArgument(0)));
            when(vex.isValid()).thenReturn(true);
            Object rawInit = inv.getArgument(2);
            if (rawInit instanceof java.util.function.Consumer<?> c) {
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<Vex> init = (java.util.function.Consumer<Vex>) c;
                init.accept(vex);
            } else if (rawInit instanceof org.bukkit.util.Consumer<?> c) {
                @SuppressWarnings("unchecked")
                org.bukkit.util.Consumer<Vex> init = (org.bukkit.util.Consumer<Vex>) c;
                init.accept(vex);
            }
            spawned.add(vex);
            return vex;
        });

        Zombie attacker = mock(Zombie.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());

        RelicAbilities abilities = new RelicAbilities(plugin, scheduler);
        @SuppressWarnings("deprecation")
        EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(attacker, wearer, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3.0);
        abilities.damage(hit);

        assertEquals(2, spawned.size());
        verify(spawned.get(0)).setTarget(attacker);

        // Loyal Vex cannot target its owner
        EntityTargetEvent selfTarget = new EntityTargetEvent(spawned.get(0), wearer, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        abilities.target(selfTarget);
        assertTrue(selfTarget.isCancelled());

        // Second hit within cooldown does not summon again
        abilities.damage(hit);
        assertEquals(2, spawned.size());

        // Quit despawns loyal Vexes
        abilities.quit(new PlayerQuitEvent(wearer, ""));
        for (Vex vex : spawned) {
            verify(vex).remove();
        }
    }

    @Test
    void chorusBuckleTeleportsSafelyOnSneakDropAndRespectsCooldown() throws Exception {
        Catalog catalog = bundledCatalog();
        Catalog.Entry buckle = catalog.entries.get("chorus_buckle");

        CuriosPlugin plugin = mock(CuriosPlugin.class);
        when(plugin.getName()).thenReturn("Curios");
        AddonConnection core = mock(AddonConnection.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.core()).thenReturn(core);
        when(core.runForEntity(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return TaskHandle.NONE;
        });
        when(core.teleport(any(), any(), eq(PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)))
                .thenReturn(CompletableFuture.completedFuture(true));

        World world = mock(World.class);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);

        Block ground = mock(Block.class);
        Block feet = mock(Block.class);
        Block head = mock(Block.class);
        when(ground.getY()).thenReturn(63);
        when(ground.getType()).thenReturn(Material.STONE);
        when(ground.isLiquid()).thenReturn(false);
        when(ground.getRelative(0, 1, 0)).thenReturn(feet);
        when(ground.getRelative(0, 2, 0)).thenReturn(head);
        when(feet.isPassable()).thenReturn(true);
        when(feet.isLiquid()).thenReturn(false);
        when(feet.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(head.isPassable()).thenReturn(true);
        when(head.isLiquid()).thenReturn(false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(ground);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isSneaking()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(plugin.abilityEntry(player, Catalog.Ability.CHORUS_BLINK)).thenReturn(buckle);

        RelicAbilities abilities = new RelicAbilities(plugin, scheduler);
        PlayerDropItemEvent drop1 = new PlayerDropItemEvent(player, mock(org.bukkit.entity.Item.class));
        abilities.drop(drop1);
        assertTrue(drop1.isCancelled());
        verify(core, times(1)).teleport(eq(player), any(Location.class), eq(PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT));

        // Second sneak-drop during cooldown does not cancel drop or teleport again
        PlayerDropItemEvent drop2 = new PlayerDropItemEvent(player, mock(org.bukkit.entity.Item.class));
        abilities.drop(drop2);
        assertFalse(drop2.isCancelled());
        verify(core, times(1)).teleport(eq(player), any(Location.class), eq(PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT));
    }
}
