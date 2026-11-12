package codes.castled.allium.protection.griefprevention;

import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionHook;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * GriefPrevention claim check, compiled against the public GriefPrevention 16.18 API and
 * loaded only when GriefPrevention (or GriefPrevention3D, which keeps the same API) is enabled.
 *
 * <p>Taking equipment from a mob inside a claim needs container trust there:
 * {@link ClaimPermission#Inventory} upstream, which GriefPrevention3D keeps as an alias of its
 * {@code Container} permission (the trust that governs creatures unless PvE trust is enabled).
 * Breaking blocks inside a claim requires build trust ({@link ClaimPermission#Build}).
 * GriefPrevention handles its own bypasses (ignoring claims, admin claims) and fires its
 * permission-check event for add-ons. Outside claims, this hook allows.
 */
public final class GriefPreventionProtectionHook implements ProtectionHook {

    @Override
    public String name() {
        return "GriefPrevention";
    }

    @Override
    public ProtectionDecision checkEntityInteraction(Player player, Entity target, @Nullable Event cause) {
        GriefPrevention plugin = GriefPrevention.instance;
        if (plugin == null || plugin.dataStore == null) {
            throw new IllegalStateException("GriefPrevention is enabled but its data store is unavailable");
        }
        Claim claim = plugin.dataStore.getClaimAt(target.getLocation(), false, null);
        if (claim == null) {
            return ProtectionDecision.allow();
        }
        Supplier<String> denial = claim.checkPermission(player, ClaimPermission.Inventory, cause);
        if (denial == null) {
            return ProtectionDecision.allow();
        }
        return ProtectionDecision.deny(name(), denial.get());
    }

    @Override
    public ProtectionDecision checkBlockBreak(Player player, Location location, @Nullable Event cause) {
        GriefPrevention plugin = GriefPrevention.instance;
        if (plugin == null || plugin.dataStore == null) {
            throw new IllegalStateException("GriefPrevention is enabled but its data store is unavailable");
        }
        Claim claim = plugin.dataStore.getClaimAt(location, false, null);
        if (claim == null) {
            return ProtectionDecision.allow();
        }
        Supplier<String> denial = claim.checkPermission(player, ClaimPermission.Build, cause);
        if (denial == null) {
            return ProtectionDecision.allow();
        }
        return ProtectionDecision.deny(name(), denial.get());
    }
}
