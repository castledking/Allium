package net.survivalfun.core.managers.core.placeholderapi;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.Spy;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.*;
import java.util.UUID;

public class SpyPlaceholder extends PlaceholderExpansion {
    private final PluginStart plugin;
    private Spy cachedSpyCommand;
    private boolean missingSpyLogged;

    public SpyPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Towkio";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0"; // Return a fixed version string
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        
        UUID playerId = player.getUniqueId();
        Spy spyCommand = getSpyCommand();
        if (spyCommand == null) {
            return handleMissingSpy(params);
        }
        
        if (params.equalsIgnoreCase("spy")) {
            if (!isSpyEnabled(spyCommand, playerId)) {
                return "no";
            }
            return "yes";
        } else if (params.equalsIgnoreCase("spy_target")) {
            if (!isSpyEnabled(spyCommand, playerId)) {
                return "none";
            }
            // Check if player has a specific target
            Set<UUID> targets = spyCommand.getTargetedSpying().get(playerId);
            if (targets != null && !targets.isEmpty()) {
                // Return the first target (since the command only allows one target at a time)
                UUID targetId = targets.iterator().next();
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
                return target.getName() != null ? target.getName() : "unknown";
            } else if (spyCommand.isGloballySpying(playerId)) {
                return "global";
            }
            return "none";
        }
        return null;
    }
    
    /**
     * Checks if a player has any form of spying enabled
     * @param playerId The player's UUID
     * @return true if the player has global or targeted spying enabled
     */
    public boolean isSpyEnabled(Spy spyCommand, UUID playerId) {
        return spyCommand.isGloballySpying(playerId) || 
               (spyCommand.getTargetedSpying().containsKey(playerId) && 
                !spyCommand.getTargetedSpying().get(playerId).isEmpty());
    }
    
    /**
     * Checks if a player has global spying enabled
     * @param playerId The player's UUID
     * @return true if the player has global spying enabled
     */
    public boolean isGloballySpying(UUID playerId) {
        Spy spyCommand = getSpyCommand();
        if (spyCommand == null) {
            return false;
        }
        return spyCommand.isGloballySpying(playerId);
    }
    
    /**
     * Checks if a player is spying on a specific target
     * @param spyId The spy's UUID
     * @param targetId The target's UUID
     * @return true if the player is spying on the target
     */
    public boolean isSpyingOn(UUID spyId, UUID targetId) {
        Spy spyCommand = getSpyCommand();
        if (spyCommand == null) {
            return false;
        }
        return spyCommand.isSpyingOn(spyId, targetId);
    }

    private Spy getSpyCommand() {
        Spy spy = plugin.getSpyCommand();
        if (spy != null) {
            cachedSpyCommand = spy;
            missingSpyLogged = false;
            return spy;
        }

        if (cachedSpyCommand != null) {
            return cachedSpyCommand;
        }

        if (!missingSpyLogged) {
            Text.sendDebugLog(WARN, "SpyPlaceholder requested before Spy command initialized. Returning safe defaults.");
            missingSpyLogged = true;
        }
        return null;
    }

    private String handleMissingSpy(String params) {
        if (params.equalsIgnoreCase("spy")) {
            return "no";
        }
        if (params.equalsIgnoreCase("spy_target")) {
            return "none";
        }
        return null;
    }
}
