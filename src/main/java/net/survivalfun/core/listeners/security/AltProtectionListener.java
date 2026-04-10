package net.survivalfun.core.listeners.security;

import net.milkbowl.vault.permission.Permission;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.PvpCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;

public class AltProtectionListener implements Listener {

    private final PluginStart plugin;

    public AltProtectionListener(PluginStart plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolveDamagingPlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        Permission vaultPermission = plugin.getVaultPermission();
        if (sharesCurrentIp(attacker, victim)
                && vaultPermission != null
                && vaultPermission.hasGroupSupport()
                && vaultPermission.playerInGroup(victim.getName(), "alt", null)) {
            event.setCancelled(true);
            return;
        }

        if (!PvpCommand.isPvpEnabled(plugin, attacker) || !PvpCommand.isPvpEnabled(plugin, victim)) {
            event.setCancelled(true);
        }
    }

    private @Nullable Player resolveDamagingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean sharesCurrentIp(Player first, Player second) {
        String firstIp = getPlayerIpAddress(first);
        String secondIp = getPlayerIpAddress(second);
        return firstIp != null && firstIp.equals(secondIp);
    }

    private @Nullable String getPlayerIpAddress(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }
}
