package codes.castled.allium.player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.essentials.PvpCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceDefaults;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.PreferenceStore;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PvpServiceAndCommandTest {

    @TempDir
    Path tempDir;

    private static Player mockPlayer(UUID uuid, String name, String ip, String... perms) throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        if (ip != null) {
            when(player.getAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(ip), 54321));
        }
        var permSet = java.util.Set.of(perms);
        when(player.hasPermission(anyString())).thenAnswer(inv -> permSet.contains(inv.getArgument(0)));
        when(player.canSee(any(Player.class))).thenReturn(true);
        return player;
    }

    @Test
    void preferenceRepositoryMigration4PersistsAndReloadsPvpEnabled() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("prefs.db"));
        PreferenceRepository repo = new PreferenceRepository(cs, SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(4, repo.migrate());

        UUID alice = UUID.randomUUID();
        assertTrue(repo.load(alice).isEmpty());

        var disabled = repo.apply(alice, PreferenceChange.set(PreferenceKey.PVP_ENABLED, false));
        assertEquals(Boolean.FALSE, disabled.pvpEnabled());
        assertFalse(disabled.effective(new PreferenceDefaults(true, true, true, 1.0D, true)).pvpEnabled());

        var reloaded = repo.load(alice).orElseThrow();
        assertEquals(Boolean.FALSE, reloaded.pvpEnabled());

        var enabled = repo.apply(alice, PreferenceChange.set(PreferenceKey.PVP_ENABLED, true));
        assertEquals(Boolean.TRUE, enabled.pvpEnabled());

        PreferenceStore store = new PreferenceStore(repo, 1, 32, uuid -> {});
        try {
            PreferenceSessions sessions = new PreferenceSessions(store, () -> new PreferenceDefaults(true, true, true, 1.0D, true), m -> {});
            assertEquals(PreferenceSessions.Acceptance.UNAVAILABLE, sessions.acceptance(alice, PreferenceKey.PVP_ENABLED));
            sessions.begin(alice);
            sessions.whenReady(alice).get(5, TimeUnit.SECONDS);
            assertEquals(PreferenceSessions.Acceptance.ACCEPTS, sessions.acceptance(alice, PreferenceKey.PVP_ENABLED));
            sessions.change(alice, PreferenceChange.set(PreferenceKey.PVP_ENABLED, false)).get(5, TimeUnit.SECONDS);
            assertEquals(PreferenceSessions.Acceptance.REFUSES, sessions.acceptance(alice, PreferenceKey.PVP_ENABLED));
        } finally {
            store.close(2_000L);
        }
    }

    @Test
    void pvpServiceBlocksMeleeProjectilesTntCloudCombustHarmfulPotionsAndSameIpAlts() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        ModuleManager modules = mock(ModuleManager.class);
        PreferencesModule prefs = mock(PreferencesModule.class);
        MessageManager messages = mock(MessageManager.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("pvp.protect-same-ip-alts", true);
        config.set("pvp.notify-blocked", true);
        config.set("pvp.notify-cooldown-millis", 2500);
        config.set("alts.group", "alt");
        config.set("alts.ignore-local-addresses", true);

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(modules.getModule("preferences")).thenReturn(prefs);
        when(prefs.isEnabled()).thenReturn(true);

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID altId = UUID.randomUUID();

        Player alice = mockPlayer(aliceId, "Alice", "203.0.113.10");
        Player bob = mockPlayer(bobId, "Bob", "198.51.100.20");
        Player alt = mockPlayer(altId, "AliceAlt", "203.0.113.10", "group.alt");

        when(prefs.acceptance(aliceId, PreferenceKey.PVP_ENABLED)).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        when(prefs.acceptance(bobId, PreferenceKey.PVP_ENABLED)).thenReturn(PreferenceSessions.Acceptance.REFUSES);
        when(prefs.acceptance(altId, PreferenceKey.PVP_ENABLED)).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);

        AtomicLong now = new AtomicLong(10_000L);
        PvpService service = new PvpService(plugin, now::get, (p, group) -> p.hasPermission("group." + group));

        // 1. Direct melee hit blocked because Bob has PvP off (and notifies Alice once within cooldown).
        EntityDamageByEntityEvent melee = new EntityDamageByEntityEvent(alice, bob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        service.onEntityDamageByEntity(melee);
        assertTrue(melee.isCancelled());
        verify(messages, times(1)).send(alice, Messages.PVP_BLOCKED_TARGET, "{player}", "Bob");

        // 2. Projectile shot by Alice against Bob blocked; notification rate-limited during cooldown.
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(alice);
        EntityDamageByEntityEvent shot = new EntityDamageByEntityEvent(arrow, bob, EntityDamageEvent.DamageCause.PROJECTILE, 4.0);
        service.onEntityDamageByEntity(shot);
        assertTrue(shot.isCancelled());
        verify(messages, times(1)).send(alice, Messages.PVP_BLOCKED_TARGET, "{player}", "Bob");

        // 3. After cooldown expires, TNT and AreaEffectCloud and combustion are also blocked.
        now.addAndGet(3_000L);
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getSource()).thenReturn(alice);
        EntityDamageByEntityEvent blast = new EntityDamageByEntityEvent(tnt, bob, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 8.0);
        service.onEntityDamageByEntity(blast);
        assertTrue(blast.isCancelled());
        verify(messages, times(2)).send(alice, Messages.PVP_BLOCKED_TARGET, "{player}", "Bob");

        AreaEffectCloud cloud = mock(AreaEffectCloud.class);
        when(cloud.getSource()).thenReturn(alice);
        EntityDamageByEntityEvent linger = new EntityDamageByEntityEvent(cloud, bob, EntityDamageEvent.DamageCause.MAGIC, 2.0);
        service.onEntityDamageByEntity(linger);
        assertTrue(linger.isCancelled());

        EntityCombustByEntityEvent combust = new EntityCombustByEntityEvent(arrow, bob, 5);
        service.onEntityCombustByEntity(combust);
        assertTrue(combust.isCancelled());

        // 4. Harmful splash potion zeroes intensity for Bob; beneficial splash potion stays untouched.
        ThrownPotion poisonPotion = mock(ThrownPotion.class);
        when(poisonPotion.getShooter()).thenReturn(alice);
        when(poisonPotion.getEffects()).thenReturn(List.of(new PotionEffect(PotionEffectType.POISON, 100, 0)));
        var poisonMap = new java.util.HashMap<org.bukkit.entity.LivingEntity, Double>();
        poisonMap.put(bob, 1.0D);
        poisonMap.put(alice, 1.0D);
        PotionSplashEvent poisonSplash = new PotionSplashEvent(poisonPotion, poisonMap);
        service.onPotionSplash(poisonSplash);
        assertEquals(0.0D, poisonSplash.getIntensity(bob));
        assertEquals(1.0D, poisonSplash.getIntensity(alice), "Self splash is unaffected");

        ThrownPotion regenPotion = mock(ThrownPotion.class);
        when(regenPotion.getShooter()).thenReturn(alice);
        when(regenPotion.getEffects()).thenReturn(List.of(new PotionEffect(PotionEffectType.REGENERATION, 100, 0)));
        var regenMap = new java.util.HashMap<org.bukkit.entity.LivingEntity, Double>();
        regenMap.put(bob, 1.0D);
        PotionSplashEvent regenSplash = new PotionSplashEvent(regenPotion, regenMap);
        service.onPotionSplash(regenSplash);
        assertEquals(1.0D, regenSplash.getIntensity(bob), "Beneficial splash potions are allowed");

        // 5. Same-IP alt protection blocks Alice hitting AliceAlt even though both have PvP enabled.
        assertEquals(PvpService.Decision.SAME_IP_ALT, service.evaluate(alice, alt));

        // 6. Fail-closed while preferences are still LOADING.
        when(prefs.acceptance(bobId, PreferenceKey.PVP_ENABLED)).thenReturn(PreferenceSessions.Acceptance.UNAVAILABLE);
        assertEquals(PvpService.Decision.PREFERENCES_UNAVAILABLE, service.evaluate(alice, bob));

        // 7. When both accept PvP and are on different IPs, PvP is allowed.
        when(prefs.acceptance(bobId, PreferenceKey.PVP_ENABLED)).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        assertEquals(PvpService.Decision.ALLOWED, service.evaluate(alice, bob));

        // 8. Reloading config to disable same-IP alt protection allows Alice vs AliceAlt.
        YamlConfiguration updated = new YamlConfiguration();
        updated.set("pvp.protect-same-ip-alts", false);
        service.prepareReload(updated).publish();
        assertEquals(PvpService.Decision.ALLOWED, service.evaluate(alice, alt));
    }

    @Test
    void pvpCommandTogglesSetsAndEnforcesOtherPermission() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        ModuleManager modules = mock(ModuleManager.class);
        PreferencesModule prefs = mock(PreferencesModule.class);
        PreferenceSessions sessions = mock(PreferenceSessions.class);
        MessageManager messages = mock(MessageManager.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(modules.getModule("preferences")).thenReturn(prefs);
        when(prefs.isEnabled()).thenReturn(true);
        when(prefs.sessions()).thenReturn(sessions);
        when(sessions.change(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        Player alice = mockPlayer(aliceId, "Alice", "203.0.113.10", "allium.pvp");
        Player bob = mockPlayer(bobId, "Bob", "198.51.100.20", "allium.pvp");
        Player staff = mockPlayer(UUID.randomUUID(), "Staff", "198.51.100.30", "allium.pvp", "allium.pvp.other");

        when(server.getPlayerExact("Bob")).thenReturn(bob);
        when(prefs.effective(aliceId)).thenReturn(Optional.of(new EffectivePreferences(true, true, true, 1.0D, false, false, false, false, true)));
        when(prefs.effective(bobId)).thenReturn(Optional.of(new EffectivePreferences(true, true, true, 1.0D, false, false, false, false, true)));

        PvpCommand command = new PvpCommand(plugin);

        // Self bare toggle: true -> false
        command.execute(context(plugin, alice, new String[0]));
        verify(sessions).change(eq(aliceId), argThat(c -> Optional.of(Boolean.FALSE).equals(c.entries().get(PreferenceKey.PVP_ENABLED))));
        verify(messages).send(alice, Messages.PVP_OFF);

        // Non-staff trying to toggle Bob is refused
        command.execute(context(plugin, alice, new String[]{"off", "Bob"}));
        verify(messages).send(alice, Messages.NO_PERMISSION, "{cmd}", "pvp on others");
        verify(sessions, never()).change(eq(bobId), any());

        // Staff toggling Bob off succeeds and notifies both
        command.execute(context(plugin, staff, new String[]{"off", "Bob"}));
        verify(sessions).change(eq(bobId), argThat(c -> Optional.of(Boolean.FALSE).equals(c.entries().get(PreferenceKey.PVP_ENABLED))));
        verify(messages).send(staff, Messages.PVP_OTHER_OFF, "{player}", "Bob");
        verify(messages).send(bob, Messages.PVP_OFF);
    }

    private static BukkitCommandContext context(PluginStart plugin, CommandSender sender, String[] args) {
        return new BukkitCommandContext(plugin, CoreCommandSpecs.PVP, sender, "pvp", args);
    }
}
