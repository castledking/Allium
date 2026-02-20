package net.survivalfun.core.listeners.security;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.items.HandcuffsItem;
import net.survivalfun.core.managers.core.Text;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class HandcuffsListener implements Listener {
    private final PluginStart plugin;
    private final Map<UUID, UUID> restrainedPlayers; // handcuffee -> handcuffer mapping (for quick lookup)
    private final Map<UUID, List<UUID>> handcufferToHandcuffees; // handcuffer -> ordered list of handcuffees
    private final Map<Integer, UUID> handcuffBobbers; // bobber entity ID -> handcuffer UUID
    private final Set<UUID> playersInTeleport; // Track players currently being handled for teleport
    private final Map<UUID, Location> lastKnownPositions; // Track last known positions for async teleport detection
    private final Map<UUID, Long> dismountMessageCooldowns; // Rate limit dismount messages
    private final Set<UUID> playersWaitingForUnrestrainInput; // Track players waiting for chat input to specify which player to unrestrain

    public HandcuffsListener(PluginStart plugin) {
        this.plugin = plugin;
        this.restrainedPlayers = new ConcurrentHashMap<>();
        this.handcufferToHandcuffees = new ConcurrentHashMap<>();
        this.handcuffBobbers = new HashMap<>();
        this.playersInTeleport = ConcurrentHashMap.newKeySet();
        this.dismountMessageCooldowns = new HashMap<>();
        this.lastKnownPositions = new ConcurrentHashMap<>(); // Initialize lastKnownPositions here
        this.playersWaitingForUnrestrainInput = ConcurrentHashMap.newKeySet();

        // Start periodic position monitoring for async teleport detection
        startPositionMonitoring();
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Check if player is restrained
        if (isPlayerRestrained(player)) {
            // Cancel all commands for restrained players
            event.setCancelled(true);
            player.sendMessage(Text.colorize("&cYou cannot use commands while restrained!"));
            Text.sendDebugLog(INFO, "Blocked command from restrained player: " + player.getName() + " attempted: " + event.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (isPlayerRestrained(player)) {
                // Check if this is a legitimate teleport unmount
                boolean isTeleportUnmount = playersInTeleport.contains(player.getUniqueId());

                if (!isTeleportUnmount) {
                    // Rate limit dismount messages (10 seconds cooldown)
                    long currentTime = System.currentTimeMillis();
                    Long lastMessageTime = dismountMessageCooldowns.get(player.getUniqueId());

                    if (lastMessageTime == null || (currentTime - lastMessageTime) >= 10000) { // 10 seconds
                        event.setCancelled(true);
                        player.sendMessage(Text.colorize("&cYou cannot dismount while restrained!"));
                        dismountMessageCooldowns.put(player.getUniqueId(), currentTime);
                        Text.sendDebugLog(INFO, "Prevented dismount attempt by restrained player: " + player.getName());
                    } else {
                        event.setCancelled(true);
                        // Silent cancel if message is on cooldown
                        Text.sendDebugLog(INFO, "Silently prevented dismount attempt (message on cooldown): " + player.getName());
                    }
                } else {
                    Text.sendDebugLog(INFO, "Allowing legitimate teleport unmount for: " + player.getName());
                    // Remove from teleport tracking after successful unmount
                    playersInTeleport.remove(player.getUniqueId());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if player has saved handcuff state but isn't actually restrained
        // This can happen after server restart when relationships aren't restored
        try {
            Map<String, Object> savedState = plugin.getDatabase().loadPlayerState(playerId);

            if (savedState != null && savedState.containsKey("is_flying")) {
                Object gameModeObj = savedState.get("gamemode");

                // If player was in adventure mode (handcuffed state) but isn't actually restrained
                if (gameModeObj != null && gameModeObj.equals(org.bukkit.GameMode.ADVENTURE) &&
                    !isPlayerRestrained(player)) {

                    Text.sendDebugLog(INFO, "[JOIN] Player " + player.getName() + " has saved handcuff state but isn't restrained - restoring to normal");

                    // Restore player to normal state
                    removeHandcuffRestrictions(player);

                    // Also clear any passengers they might have (in case they were a handcuffer)
                    if (player.getPassengers().size() > 0) {
                        for (org.bukkit.entity.Entity passenger : new ArrayList<>(player.getPassengers())) {
                            if (passenger instanceof Player) {
                                player.removePassenger(passenger);
                                Text.sendDebugLog(INFO, "[JOIN] Removed passenger " + passenger.getName() + " from " + player.getName() + " during cleanup");
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to check player state on join for " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        PlayerFishEvent.State state = event.getState();
        FishHook hook = event.getHook();

        Text.sendDebugLog(INFO, player.getName() + " triggered PlayerFishEvent with state: " + state);

        // Handle FISHING state: player first casts the line
        if (state == PlayerFishEvent.State.FISHING) {
            ItemStack item = player.getInventory().getItemInMainHand();

            // Check if player is holding handcuffs and casting the rod
            if (isHandcuffs(item)) {
                // Track this bobber as a handcuff bobber
                handcuffBobbers.put(hook.getEntityId(), player.getUniqueId());
                Text.sendDebugLog(INFO, player.getName() + " cast handcuffs fishing rod, tracking bobber " + hook.getEntityId());
            }

            // If player is restrained (mounted), cancel the cast immediately
            if (isPlayerRestrained(player)) {
                event.setCancelled(true);
                Text.sendDebugLog(INFO, player.getName() + " attempted to cast while restrained, cancelled");
                return;
            }
        }

        // Handle BITE state: when a fish starts biting
        if (state == PlayerFishEvent.State.BITE) {
            // Play a sound or particle effect for the bite
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            // You can add particle effects here if needed
            Text.sendDebugLog(INFO, player.getName() + " got a bite on the hook");
        }

        // Handle CAUGHT_FISH state: successfully reeled in a fish or item
        if (state == PlayerFishEvent.State.CAUGHT_FISH) {
            // Give rewards, track statistics, or modify drops
            player.sendMessage(Text.colorize("&aYou caught a fish!"));
            Text.sendDebugLog(INFO, player.getName() + " caught a fish");
            // Add reward logic here if needed
        }

        // Handle CAUGHT_ENTITY state: hook snagged an entity
        if (state == PlayerFishEvent.State.CAUGHT_ENTITY) {
            // Integrate logic from onProjectileHit
            Integer bobberId = hook.getEntityId();

            // Check if this bobber was cast from handcuffs
            if (!handcuffBobbers.containsKey(bobberId)) {
                return;
            }

            UUID handcufferId = handcuffBobbers.get(bobberId);
            Player handcuffer = Bukkit.getPlayer(handcufferId);

            // Remove the bobber from tracking since we're processing it
            handcuffBobbers.remove(bobberId);

            if (handcuffer == null || !handcuffer.isOnline()) {
                return;
            }

            // Check if the caught entity is a player
            if (event.getCaught() instanceof Player target) {
                // Check if target already has handcuffs effect - if so, don't interfere with existing restraints
                if (restrainedPlayers.containsKey(target.getUniqueId())) {
                    // Player is already restrained by someone else, don't interfere
                    handcuffer.sendMessage(Text.colorize("&e" + target.getName() + " is already restrained by someone else."));
                    return;
                }

                // Check if player has permission to use handcuffs
                if (!handcuffer.hasPermission("allium.handcuffs.use")) {
                    handcuffer.sendMessage(Text.colorize("&cYou don't have permission to use handcuffs!"));
                    return;
                }

                // Check if target has permission to resist handcuffs
                if (target.hasPermission("allium.handcuffs.resist")) {
                    handcuffer.sendMessage(Text.colorize("&cThis player cannot be restrained!"));
                    return;
                }

                // Apply handcuffs effect - force target to ride on player's head
                applyHandcuffs(target, handcuffer, false); // Don't consume item for fishing rod method

                // Remove the fishing line/bobber to allow recasting for multiple players
                hook.remove();
            }
        }

        // Handle FAILED_ATTEMPT state: reeled in too early or too late
        if (state == PlayerFishEvent.State.FAILED_ATTEMPT) {
            // If player is restrained, cancel silently (no message)
            if (isPlayerRestrained(player)) {
                event.setCancelled(true);
                Text.sendDebugLog(INFO, player.getName() + " failed attempt while restrained, cancelled silently");
                return;
            }

            // Detect missed catches, apply penalties, etc.
            Text.sendDebugLog(INFO, player.getName() + " failed to catch anything");
        }

        // Handle REEL_IN state: player retracts the line
        if (state == PlayerFishEvent.State.REEL_IN) {
            ItemStack item = player.getInventory().getItemInMainHand();

            if (isHandcuffs(item)) {
                Integer bobberId = hook.getEntityId();

                Text.sendDebugLog(INFO, player.getName() + " reeling in handcuffs bobber " + bobberId);

                // Check if this bobber was cast from handcuffs
                if (!handcuffBobbers.containsKey(bobberId)) {
                    // This wasn't a handcuff bobber, ignore
                    Text.sendDebugLog(INFO, player.getName() + " reeled in bobber " + bobberId + " but it wasn't tracked as handcuffs");
                    return;
                }

                // Just remove from tracking, no release logic here (default Minecraft behavior)
                handcuffBobbers.remove(bobberId);
                Text.sendDebugLog(INFO, player.getName() + " removed bobber " + bobberId + " from tracking on reel in");
            }
        }
    }


    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // If this player was restraining someone, release them
        if (restrainedPlayers.containsValue(playerId)) {
            restrainedPlayers.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
        }

        // If this player was restrained, execute commands-on-quit and remove them from the map
        if (restrainedPlayers.containsKey(playerId)) {
            // Execute commands from configuration
            executeCommandsOnQuit(player);

            Player handcuffer = Bukkit.getPlayer(restrainedPlayers.get(playerId));
            if (handcuffer != null && handcuffer.isOnline()) {
                handcuffer.sendMessage(Text.colorize("&e" + player.getName() + " has logged out while restrained."));
            }

            // Clean up handcufferToHandcuffees relationship before removing from restrainedPlayers
            UUID handcufferId = restrainedPlayers.get(playerId);
            if (handcufferId != null) {
                List<UUID> handcuffees = handcufferToHandcuffees.get(handcufferId);
                if (handcuffees != null) {
                    handcuffees.remove(playerId);
                    // If handcuffer has no more handcuffees, remove the entry entirely for cleanliness
                    if (handcuffees.isEmpty()) {
                        handcufferToHandcuffees.remove(handcufferId);
                    }
                }
            }

            restrainedPlayers.remove(playerId);
        }

        // Clean up position tracking
        lastKnownPositions.remove(playerId);
        playersInTeleport.remove(playerId);
        dismountMessageCooldowns.remove(playerId);
    }
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack droppedItem = event.getItemDrop().getItemStack();

        // Check if the dropped item is handcuffs
        if (!isHandcuffs(droppedItem)) {
            return;
        }

        UUID handcufferId = player.getUniqueId();

        // Find all restrained players for this handcuffer
        List<UUID> handcuffees = handcufferToHandcuffees.get(handcufferId);

        if (handcuffees == null || handcuffees.isEmpty()) {
            // No one is restrained by this player, just send a message
            player.sendMessage(Text.colorize("&eNo one is currently restrained by you."));
            event.setCancelled(true); // Cancel drop even if no one is restrained
            return;
        }

        if (handcuffees.size() == 1) {
            // Only one player restrained, release them immediately
            UUID targetId = handcuffees.get(0);
            Player target = Bukkit.getPlayer(targetId);

            if (target != null && target.isOnline()) {
                Text.sendDebugLog(INFO, player.getName() + " releasing " + target.getName() + " via dropped handcuffs");
                releasePlayer(target);

                // Schedule explicit visual state update to ensure it happens after all other updates
                Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                    HandcuffsItem.updateHandcuffsModelData(player, "template:fishing_rod_handcuffs");
                    Text.sendDebugLog(INFO, player.getName() + " scheduled visual state update to fishing_rod_handcuffs after drop release");
                }, 1L); // Run on next tick to ensure all other updates complete first
            }
        } else {
            // Multiple players restrained, prompt for selection
            playersWaitingForUnrestrainInput.add(handcufferId);

            // Schedule visual state update to ensure it happens properly on main thread
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                HandcuffsItem.updateHandcuffsModelData(player, "template:fishing_rod_unlock");
                Text.sendDebugLog(INFO, player.getName() + " dropped handcuffs with multiple players - set visual state to fishing_rod_unlock (unlock/chat prompt) on main thread");
            });

            // Show list of restrained players
            StringBuilder message = new StringBuilder("&eYou are restraining multiple players. Type the name of the player you want to release:\n");
            for (UUID handcuffeeId : handcuffees) {
                Player handcuffee = Bukkit.getPlayer(handcuffeeId);
                if (handcuffee != null && handcuffee.isOnline()) {
                    message.append("&7- ").append(handcuffee.getName()).append("\n");
                }
            }
            message.append("&eOr type 'all' to release everyone.\n");
            message.append("&eOr type 'cancel' to cancel.");

            player.sendMessage(Text.colorize(message.toString()));
            player.sendMessage(Text.colorize("&eYou have 30 seconds to choose."));
        }

        // Always cancel the drop event so handcuffs don't actually drop
        event.setCancelled(true);

        // For single player case, the visual state is already updated by releasePlayer()
        // For multiple players case, it's already updated to 1003 above
        Text.sendDebugLog(INFO, player.getName() + " attempted to drop handcuffs, cancelled drop");
    }
    private boolean isHandcuffs(ItemStack item) {
        return HandcuffsItem.isHandcuffs(item);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if this player is waiting for unrestrain input
        if (playersWaitingForUnrestrainInput.contains(playerId)) {
            event.setCancelled(true); // Cancel the chat event to prevent it from being broadcast

            String message = event.getMessage().trim();

            if (message.equalsIgnoreCase("cancel")) {
                // Player cancelled the unrestrain operation
                playersWaitingForUnrestrainInput.remove(playerId);

                // Restore normal visual state (not 1003)
                updateHandcufferVisualState(player);

                player.sendMessage(Text.colorize("&eUnrestrain operation cancelled."));
                Text.sendDebugLog(INFO, player.getName() + " cancelled unrestrain operation");
                return;
            }

            // Check if player wants to release ALL restrained players
            if (message.equalsIgnoreCase("all")) {
                List<UUID> handcuffees = handcufferToHandcuffees.get(playerId);

                if (handcuffees == null || handcuffees.isEmpty()) {
                    player.sendMessage(Text.colorize("&cYou are not restraining any players."));
                    return;
                }

                // Remove from waiting list first
                playersWaitingForUnrestrainInput.remove(playerId);

                // Release all restrained players for this handcuffer - schedule on main thread
                final List<UUID> finalHandcuffees = new ArrayList<>(handcuffees);
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                    int releasedCount = 0;
                    // Unmount players in reverse order (top-most first) for safety
                    for (int i = finalHandcuffees.size() - 1; i >= 0; i--) {
                        UUID handcuffeeId = finalHandcuffees.get(i);
                        Player handcuffee = Bukkit.getPlayer(handcuffeeId);
                        if (handcuffee != null && handcuffee.isOnline()) {
                            Text.sendDebugLog(INFO, player.getName() + " releasing " + handcuffee.getName() + " via 'all' command");
                            releasePlayer(handcuffee);
                            releasedCount++;
                        }
                    }

                    // Update visual state after releasing all players
                    updateHandcufferVisualState(player);
                    player.sendMessage(Text.colorize("&aSuccessfully released " + releasedCount + " player(s)!"));
                });

                return;
            }

            // Try to find the player by name
            Player targetPlayer = Bukkit.getPlayer(message);

            if (targetPlayer == null) {
                // Try to find by partial name match
                targetPlayer = findPlayerByPartialName(message);
            }

            if (targetPlayer == null) {
                player.sendMessage(Text.colorize("&cPlayer '" + message + "' not found. Please type the exact player name, 'all' to release everyone, or 'cancel' to cancel."));
                return;
            }

            // Check if this player is actually restraining the target
            if (!restrainedPlayers.containsKey(targetPlayer.getUniqueId()) ||
                !restrainedPlayers.get(targetPlayer.getUniqueId()).equals(playerId)) {
                player.sendMessage(Text.colorize("&cYou are not restraining " + targetPlayer.getName() + "."));
                return;
            }

            // Remove from waiting list first
            playersWaitingForUnrestrainInput.remove(playerId);

            // Release the specific player - schedule on main thread to avoid IllegalStateException
            final Player finalTargetPlayer = targetPlayer;
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                Text.sendDebugLog(INFO, player.getName() + " releasing " + finalTargetPlayer.getName() + " via chat input");
                releasePlayer(finalTargetPlayer);

                // Restore normal visual state after releasing player
                updateHandcufferVisualState(player);
            });

            // Send confirmation message immediately
            player.sendMessage(Text.colorize("&aSuccessfully released " + targetPlayer.getName() + "!"));
        }
    }

    /**
     * Finds a player by partial name match
     * @param partialName The partial name to search for
     * @return The matching player, or null if none found
     */
    private Player findPlayerByPartialName(String partialName) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getName().toLowerCase().contains(partialName.toLowerCase()) ||
                onlinePlayer.getName().toLowerCase().equals(partialName.toLowerCase())) {
                return onlinePlayer;
            }
        }
        return null;
    }

    private void applyHandcuffs(Player target, Player handcuffer, boolean consumeItem) {
        UUID targetId = target.getUniqueId();
        UUID handcufferId = handcuffer.getUniqueId();

        // Check if target is already restrained by this handcuffer
        if (restrainedPlayers.containsKey(targetId) && restrainedPlayers.get(targetId).equals(handcufferId)) {
            handcuffer.sendMessage(Text.colorize("&e" + target.getName() + " is already restrained by you!"));
            return;
        }

        // Check if handcuffer already has too many restrained players (optional limit)
        List<UUID> currentHandcuffees = handcufferToHandcuffees.get(handcufferId);
        if (currentHandcuffees != null && currentHandcuffees.size() >= 5) { // Optional limit of 5
            handcuffer.sendMessage(Text.colorize("&cYou can only restrain up to 5 players at once!"));
            return;
        }

        // Eject target from any current mount
        target.eject();

        // Find the bottom-most player to mount the new target on
        Player mountTarget = handcuffer;
        if (currentHandcuffees != null && !currentHandcuffees.isEmpty()) {
            // Find the last player in the stack (bottom-most)
            UUID bottomPlayerId = currentHandcuffees.get(currentHandcuffees.size() - 1);
            Player bottomPlayer = Bukkit.getPlayer(bottomPlayerId);
            if (bottomPlayer != null && bottomPlayer.isOnline()) {
                mountTarget = bottomPlayer;
            }
        }

        // Safety check: ensure mountTarget is not the same as target to prevent "Entity cannot ride itself" error
        if (mountTarget.getUniqueId().equals(targetId)) {
            Text.sendDebugLog(ERROR, "CRITICAL: Attempted to mount player " + target.getName() + " on themselves! Skipping handcuff application.");
            return;
        }

        // Check if target is already a passenger of mountTarget
        if (mountTarget.getPassengers().contains(target)) {
            Text.sendDebugLog(INFO, target.getName() + " is already a passenger of " + mountTarget.getName() + ", skipping mount operation");
        } else {
            // Mount the new target on the bottom-most player
            mountTarget.addPassenger(target);
            Text.sendDebugLog(INFO, "Mounted " + target.getName() + " on " + mountTarget.getName());
        }

        // Update data structures
        restrainedPlayers.put(targetId, handcufferId);
        handcufferToHandcuffees.computeIfAbsent(handcufferId, k -> new ArrayList<>()).add(targetId);

        // Update handcuffer's handcuffs visual state based on number of restrained players
        updateHandcufferVisualState(handcuffer);

        // Apply proper handcuff restrictions to the target
        applyHandcuffRestrictions(target);

        // Send messages
        target.sendMessage(Text.colorize("&cYou have been restrained by " + handcuffer.getName() + "!"));
        handcuffer.sendMessage(Text.colorize("&aYou have restrained " + target.getName() + "!"));

        // Log the action
        Text.sendDebugLog(INFO, handcuffer.getName() + " restrained " + target.getName() + " with handcuffs (stacked)");
    }

    /**
     * Updates the visual state of handcuffs based on the number of restrained players
     * @param handcuffer The player whose handcuffs visual state should be updated
     */
    private void updateHandcufferVisualState(Player handcuffer) {
        // If player is waiting for unrestrain input (chat prompt), don't change visual state
        if (playersWaitingForUnrestrainInput.contains(handcuffer.getUniqueId())) {
            Text.sendDebugLog(INFO, handcuffer.getName() + " is waiting for unrestrain input - skipping visual state update to preserve fishing_rod_unlock");
            return;
        }

        List<UUID> handcuffees = handcufferToHandcuffees.get(handcuffer.getUniqueId());
        int count = handcuffees != null ? handcuffees.size() : 0;

        String modelName;
        if (count == 0) {
            modelName = "template:fishing_rod_handcuffs"; // No players restrained
        } else if (count >= 5) {
            modelName = "template:fishing_rod_max"; // Max players (5) restrained
        } else if (count == 1) {
            modelName = "template:fishing_rod_locked"; // First player restrained (first catch)
        } else {
            modelName = "template:fishing_rod_locked"; // Multiple players (2-4) restrained (active state)
        }

        HandcuffsItem.updateHandcuffsModelData(handcuffer, modelName);
        Text.sendDebugLog(INFO, handcuffer.getName() + " visual state updated to " + modelName + " (count: " + count + ")");
    }

    /**
     * Applies handcuff restrictions to prevent movement, commands, and damage
     */
    private void applyHandcuffRestrictions(Player target) {
        // Set player to adventure mode to prevent breaking/placing blocks
        target.setGameMode(org.bukkit.GameMode.ADVENTURE);

        // Make player invulnerable to prevent damage
        target.setInvulnerable(true);

        // Store original walk speed for restoration later
        float originalWalkSpeed = target.getWalkSpeed();
        float originalFlySpeed = target.getFlySpeed();

        // Set walk speed to allow more movement for testing dismount
        target.setWalkSpeed(0.3f);

        // Set fly speed to allow more flying for testing
        target.setFlySpeed(0.2f);

        // Store original flying state for restoration later
        boolean originalAllowFlight = target.getAllowFlight();
        boolean originalIsFlying = target.isFlying();

        // Allow flight to prevent kicks for flying when dismounting
        target.setAllowFlight(true);

        // Set player to actually be flying to prevent anti-fly kicks when dismounting
        target.setFlying(true);

        // Disable collision for handcuffed players (if supported)
        try {
            // Try to disable collision - this may not work on all Bukkit versions
            target.setCollidable(false);
        } catch (Exception e) {
            // Collision disabling not supported in this Bukkit version
            Text.sendDebugLog(INFO, "Collision disabling not supported in this Bukkit version");
        }

        // Save the player's original state to database
        try {
            plugin.getDatabase().savePlayerState(
                target.getUniqueId(),
                target.getGameMode(),
                originalAllowFlight,
                originalIsFlying,
                originalWalkSpeed,
                originalFlySpeed
            );
            plugin.getDatabase().savePlayerSpeeds(target.getUniqueId(), originalWalkSpeed, originalFlySpeed);
        } catch (SQLException e) {
            Text.sendDebugLog(ERROR, "Failed to save player state for handcuffs: " + e.getMessage());
        }

        Text.sendDebugLog(INFO, "Applied handcuff restrictions to " + target.getName() + " (invulnerable, no collision)");
    }

    /**
     * Removes handcuff restrictions
     */
    private void removeHandcuffRestrictions(Player target) {
        try {
            // Load the player's saved state from database
            Map<String, Object> savedState = plugin.getDatabase().loadPlayerState(target.getUniqueId());
            Map<String, Float> savedSpeeds = plugin.getDatabase().loadPlayerSpeeds(target.getUniqueId());

            // Restore original game mode - always default to SURVIVAL for released players
            if (savedState != null && savedState.get("gamemode") != null) {
                org.bukkit.GameMode savedGameMode = (org.bukkit.GameMode) savedState.get("gamemode");
                // If the saved gamemode was ADVENTURE (handcuffs mode), default to SURVIVAL
                if (savedGameMode == org.bukkit.GameMode.ADVENTURE) {
                    target.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    Text.sendDebugLog(INFO, "Player " + target.getName() + " was in ADVENTURE mode from handcuffs, defaulting to SURVIVAL");
                } else {
                    target.setGameMode(savedGameMode);
                    Text.sendDebugLog(INFO, "Restored " + target.getName() + " to " + savedGameMode + " mode");
                }
            } else {
                target.setGameMode(org.bukkit.GameMode.SURVIVAL);
                Text.sendDebugLog(INFO, "No saved gamemode found for " + target.getName() + ", defaulting to SURVIVAL");
            }

            // Remove invulnerability
            target.setInvulnerable(false);

            // Restore collision for the player
            try {
                target.setCollidable(true);
            } catch (Exception e) {
                // Collision setting not supported in this Bukkit version
                Text.sendDebugLog(INFO, "Collision setting not supported in this Bukkit version");
            }

            // Restore original walk speed - validate database value, use default if too low
            if (savedSpeeds != null && savedSpeeds.get("walk_speed") != null) {
                float dbWalkSpeed = savedSpeeds.get("walk_speed");
                if (dbWalkSpeed >= 0.2f) { // Only use if it's at least normal speed
                    target.setWalkSpeed(dbWalkSpeed);
                    Text.sendDebugLog(INFO, "Restored walk speed to " + dbWalkSpeed + " for " + target.getName());
                } else {
                    target.setWalkSpeed(0.2f);
                    Text.sendDebugLog(INFO, "Database walk speed " + dbWalkSpeed + " too low, using default 0.2f for " + target.getName());
                }
            } else {
                // Fallback to normal walk speed if not saved in database
                target.setWalkSpeed(0.2f);
                Text.sendDebugLog(INFO, "Using fallback walk speed 0.2f for " + target.getName());
            }

            // Restore original fly speed - validate database value, use default if too low
            if (savedSpeeds != null && savedSpeeds.get("fly_speed") != null) {
                float dbFlySpeed = savedSpeeds.get("fly_speed");
                if (dbFlySpeed >= 0.1f) { // Only use if it's at least normal speed
                    target.setFlySpeed(dbFlySpeed);
                    Text.sendDebugLog(INFO, "Restored fly speed to " + dbFlySpeed + " for " + target.getName());
                } else {
                    target.setFlySpeed(0.1f);
                    Text.sendDebugLog(INFO, "Database fly speed " + dbFlySpeed + " too low, using default 0.1f for " + target.getName());
                }
            } else {
                // Fallback to normal fly speed if not saved in database
                target.setFlySpeed(0.1f);
                Text.sendDebugLog(INFO, "Using fallback fly speed 0.1f for " + target.getName());
            }

            // Restore original flying state
            if (savedState != null) {
                target.setAllowFlight((Boolean) savedState.get("allow_flight"));
                target.setFlying((Boolean) savedState.get("is_flying"));
            }

        } catch (SQLException e) {
            // Fallback to basic restoration if database fails
            Text.sendDebugLog(ERROR, "Failed to load player state from database, using fallback: " + e.getMessage());
            target.setGameMode(org.bukkit.GameMode.SURVIVAL);
            target.setInvulnerable(false);
            // Restore collision in fallback
            try {
                target.setCollidable(true);
            } catch (Exception ex) {
                // Collision setting not supported in this Bukkit version
                Text.sendDebugLog(INFO, "Collision setting not supported in this Bukkit version");
            }
            // Fallback speed restoration - ensure full restoration
            target.setWalkSpeed(0.2f);
            target.setFlySpeed(0.1f);
            target.setAllowFlight(true);
            target.setFlying(false);
            Text.sendDebugLog(INFO, "Applied fallback restoration for " + target.getName());
        }

        Text.sendDebugLog(INFO, "Removed handcuff restrictions from " + target.getName());
    }

    /**
     * Public method to remove handcuff restrictions from a player
     * Can be called from external commands like /unrestrain
     * This method performs a complete release operation including data structure cleanup
     */
    public void removeHandcuffRestrictionsExternal(Player target) {
        UUID targetId = target.getUniqueId();

        // Check if player is actually restrained
        if (!restrainedPlayers.containsKey(targetId)) {
            Text.sendDebugLog(INFO, "Attempted to release " + target.getName() + " via external command but they weren't restrained");
            // Still remove restrictions in case they have them
            removeHandcuffRestrictions(target);
            return;
        }

        UUID handcufferId = restrainedPlayers.get(targetId);
        Player handcuffer = Bukkit.getPlayer(handcufferId);

        // Remove from tracking FIRST to prevent other event handlers from remounting
        restrainedPlayers.remove(targetId);

        // Clean up handcufferToHandcuffees relationship
        List<UUID> handcuffees = handcufferToHandcuffees.get(handcufferId);
        if (handcuffees != null) {
            handcuffees.remove(targetId);
            // If handcuffer has no more handcuffees, remove the entry entirely for cleanliness
            if (handcuffees.isEmpty()) {
                handcufferToHandcuffees.remove(handcufferId);
            }
        }

        // Unmount the player from their handcuffer (this properly unmounts them)
        if (handcuffer != null && handcuffer.isOnline()) {
            // Schedule on main thread to avoid IllegalStateException
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                if (handcuffer.getPassengers().contains(target)) {
                    handcuffer.removePassenger(target);
                }
                handcuffer.sendMessage(Text.colorize("&aYou have released " + target.getName() + " via admin command!"));

                // Update handcuffer's handcuffs visual state after releasing player
                updateHandcufferVisualState(handcuffer);
            });
        }

        // Remove handcuff restrictions from the player
        removeHandcuffRestrictions(target);

        target.sendMessage(Text.colorize("&aYou have been released from restraints by an admin!"));
        Text.sendDebugLog(INFO, "Released " + target.getName() + " from handcuffs via external command");
    }

    /**
     * Releases a player from handcuffs
     */
    public void releasePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        UUID handcufferId = restrainedPlayers.get(playerId);

        if (handcufferId != null) {
            Player handcuffer = Bukkit.getPlayer(handcufferId);

            // IMPORTANT: Remove from tracking FIRST to prevent other event handlers from remounting
            restrainedPlayers.remove(playerId);

            // Clean up handcufferToHandcuffees relationship
            List<UUID> handcuffees = handcufferToHandcuffees.get(handcufferId);
            if (handcuffees != null) {
                handcuffees.remove(playerId);
                // If handcuffer has no more handcuffees, remove the entry entirely for cleanliness
                if (handcuffees.isEmpty()) {
                    handcufferToHandcuffees.remove(handcufferId);
                }
            }

            if (handcuffer != null && handcuffer.isOnline()) {
                // Remove player as passenger from handcuffer (this properly unmounts them)
                handcuffer.removePassenger(player);
                handcuffer.sendMessage(Text.colorize("&aYou have released " + player.getName() + "!"));

                // Update handcuffer's handcuffs visual state after releasing player
                updateHandcufferVisualState(handcuffer);
            }
            // If handcuffer is offline, the player should already be unmounted since we removed them from tracking

            // Remove handcuff restrictions from the player
            removeHandcuffRestrictions(player);

            player.sendMessage(Text.colorize("&aYou have been released from restraints!"));
            Text.sendDebugLog(INFO, "Released " + player.getName() + " from handcuffs");
        } else {
            // Player isn't in the restrained map, but let's make sure they're not mounted anywhere
            player.eject();
            Text.sendDebugLog(WARN, "Attempted to release " + player.getName() + " but they weren't in restrained players map");
        }
    }

    /**
     * Checks if a player is currently restrained
     */
    public boolean isPlayerRestrained(Player player) {
        return restrainedPlayers.containsKey(player.getUniqueId());
    }

    /**
     * Gets the handcuffer of a restrained player
     */
    public Player getHandcuffer(Player restrainedPlayer) {
        UUID handcufferId = restrainedPlayers.get(restrainedPlayer.getUniqueId());
        return handcufferId != null ? Bukkit.getPlayer(handcufferId) : null;
    }

    /**
     * Gets the number of currently restrained players
     * @return The count of restrained players
     */
    public int getRestrainedCount() {
        return restrainedPlayers.size();
    }

    /**
     * Releases all currently restrained players
     * @return The number of players released
     */
    public int releaseAllPlayers() {
        int releasedCount = 0;
        List<Player> playersToRelease = new ArrayList<>();

        // Collect all players to release first to avoid ConcurrentModificationException
        for (UUID handcuffeeId : restrainedPlayers.keySet()) {
            Player handcuffee = Bukkit.getPlayer(handcuffeeId);
            if (handcuffee != null && handcuffee.isOnline()) {
                playersToRelease.add(handcuffee);
            }
        }

        // Release all collected players - schedule on main thread for safety
        if (!playersToRelease.isEmpty()) {
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                // Unmount players in reverse order (top-most first) for safety
                for (int i = playersToRelease.size() - 1; i >= 0; i--) {
                    Player handcuffee = playersToRelease.get(i);
                    releasePlayer(handcuffee);
                }

                // Update visual state for all handcuffers who had players released
                Set<Player> handcuffersToUpdate = new HashSet<>();
                for (UUID handcuffeeId : restrainedPlayers.keySet()) {
                    UUID handcufferId = restrainedPlayers.get(handcuffeeId);
                    Player handcuffer = Bukkit.getPlayer(handcufferId);
                    if (handcuffer != null && handcuffer.isOnline()) {
                        handcuffersToUpdate.add(handcuffer);
                    }
                }

                for (Player handcuffer : handcuffersToUpdate) {
                    updateHandcufferVisualState(handcuffer);
                }

                Text.sendDebugLog(INFO, "Released " + playersToRelease.size() + " restrained players during cleanup");
            });
            releasedCount = playersToRelease.size();
        }

        return releasedCount;
    }

    /**
     * Executes commands from configuration when a restrained player logs out
     */
    private void executeCommandsOnQuit(Player player) {
        List<String> commands = plugin.getConfig().getStringList("handcuffs.commands-on-quit");

        if (commands.isEmpty()) {
            return;
        }

        String playerName = player.getName();

        Text.sendDebugLog(INFO, "Executing " + commands.size() + " commands for player " + playerName + " who logged out while restrained");

        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }

            // Replace placeholders
            String processedCommand = command.replace("{player}", playerName);

            try {
                // Execute command as console
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                Text.sendDebugLog(INFO, "Executed command: " + processedCommand);
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to execute command '" + processedCommand + "': " + e.getMessage());
            }
        }
    }

    /**
     * Handles successful teleport completion for handcuffed players
     * This method should be called by teleport commands after teleport is complete
     * @param player The player who was teleported
     */
    public void handleSuccessfulTeleport(Player player) {
        UUID playerId = player.getUniqueId();

        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] Handling successful teleport for " + player.getName());

        // If this player is a handcuffer, unmount their passengers before teleport and remount after
        if (restrainedPlayers.containsValue(playerId)) {
            Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] " + player.getName() + " is a handcuffer, handling teleport");

            // Get all handcuffees for this handcuffer in chronological order
            List<UUID> handcuffees = handcufferToHandcuffees.get(playerId);
            if (handcuffees != null && !handcuffees.isEmpty()) {
                Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] " + player.getName() + " has " + handcuffees.size() + " handcuffees to remount");

                // Mark all handcuffees for legitimate teleport unmount
                for (UUID handcuffeeId : handcuffees) {
                    playersInTeleport.add(handcuffeeId);
                }

                // Unmount all handcuffees before teleport - in reverse order (top-most first)
                for (int i = handcuffees.size() - 1; i >= 0; i--) {
                    UUID handcuffeeId = handcuffees.get(i);
                    Player handcuffee = Bukkit.getPlayer(handcuffeeId);
                    if (handcuffee != null && handcuffee.isOnline() && player.getPassengers().contains(handcuffee)) {
                        player.removePassenger(handcuffee);
                        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] Unmounted " + handcuffee.getName() + " from " + player.getName() + " before teleport");
                    }
                }

                // Schedule remount after handcuffer teleport completes
                Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                    if (player.isOnline()) {
                        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] Remounting " + handcuffees.size() + " handcuffees for " + player.getName());

                        // Rebuild the proper stack: handcuffer -> player1 -> player2 -> player3...
                        Player currentMount = player; // Start with the handcuffer
                        for (UUID handcuffeeId : handcuffees) {
                            Player handcuffee = Bukkit.getPlayer(handcuffeeId);

                            if (handcuffee != null && handcuffee.isOnline() &&
                                restrainedPlayers.containsValue(playerId) &&
                                restrainedPlayers.containsKey(handcuffeeId) &&
                                restrainedPlayers.get(handcuffeeId).equals(playerId)) {

                                Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] Remounting " + handcuffee.getName() + " to " + currentMount.getName());

                                // Safety check: ensure handcuffee is not the same as current mount
                                if (!handcuffee.getUniqueId().equals(currentMount.getUniqueId())) {
                                    // Check if handcuffee is already a passenger of current mount
                                    if (currentMount.getPassengers().contains(handcuffee)) {
                                        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] " + handcuffee.getName() + " is already a passenger of " + currentMount.getName() + ", skipping mount operation");
                                    } else {
                                        // Mount the handcuffee on the current mount (which could be handcuffer or another player)
                                        currentMount.addPassenger(handcuffee);
                                        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] Successfully mounted " + handcuffee.getName() + " to " + currentMount.getName());

                                        // Update currentMount to this player for the next iteration
                                        currentMount = handcuffee;
                                    }
                                } else {
                                    Text.sendDebugLog(ERROR, "[TELEPORT-HANDLER] CRITICAL: Attempted to mount player " + handcuffee.getName() + " on themselves during remount! Skipping.");
                                }
                            } else {
                                Text.sendDebugLog(WARN, "[TELEPORT-HANDLER] Skipping remount for offline or invalid handcuffee: " + (handcuffee != null ? handcuffee.getName() : "null"));
                            }
                        }

                        // Remove all handcuffees from teleport tracking after remounting
                        for (UUID handcuffeeId : handcuffees) {
                            playersInTeleport.remove(handcuffeeId);
                        }
                    }
                }, 15L); // 15 ticks delay (0.75 seconds) to ensure handcuffer teleport is complete
            }
        }

        // If this player is handcuffed, unmount them before teleport and remount after
        if (restrainedPlayers.containsKey(playerId)) {
            UUID handcufferId = restrainedPlayers.get(playerId);
            Player handcuffer = Bukkit.getPlayer(handcufferId);

            if (handcuffer != null && handcuffer.isOnline()) {
                Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] " + player.getName() + " is handcuffed, handling teleport");

                // Mark player for legitimate teleport unmount
                playersInTeleport.add(player.getUniqueId());

                // Unmount the player before teleport (if still mounted)
                if (handcuffer.getPassengers().contains(player)) {
                    handcuffer.removePassenger(player);
                }

                // Schedule teleport and remount after teleport completes
                Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugin("Allium"), () -> {
                    if (player.isOnline() && handcuffer.isOnline() &&
                        restrainedPlayers.containsKey(playerId) &&
                        restrainedPlayers.get(playerId).equals(handcufferId)) {

                        // Teleport the player to the handcuffer's current location
                        Location handcufferLocation = handcuffer.getLocation();
                        player.teleportAsync(handcufferLocation).thenAccept(success -> {
                            if (success) {
                                // Safety check: ensure player is not the same as handcuffer
                                if (!player.getUniqueId().equals(handcufferId)) {
                                    // Check if player is already a passenger
                                    if (handcuffer.getPassengers().contains(player)) {
                                        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] " + player.getName() + " is already a passenger of " + handcuffer.getName() + ", skipping mount operation");
                                    } else {
                                        // Remount the player on the handcuffer
                                        handcuffer.addPassenger(player);
                                        Text.sendDebugLog(INFO, "[TELEPORT-HANDLER] Teleported " + player.getName() + " to " + handcuffer.getName() + " and remounted");
                                    }
                                } else {
                                    Text.sendDebugLog(ERROR, "[TELEPORT-HANDLER] CRITICAL: Attempted to mount player " + player.getName() + " on themselves during teleport remount! Skipping.");
                                }
                            } else {
                                Text.sendDebugLog(WARN, "[TELEPORT-HANDLER] Failed to teleport " + player.getName() + " to " + handcuffer.getName());
                            }
                        });

                        // Remove from teleport tracking after handling
                        playersInTeleport.remove(player.getUniqueId());
                    }
                }, 10L); // 10 ticks delay (0.5 seconds)
            }
        }
    }

    /**
     * Starts periodic position monitoring to detect async teleports
     * DISABLED: This causes false positives and doesn't work for our use case
     */
    private void startPositionMonitoring() {
        // Async position monitoring disabled - it causes false positives
        // Teleport commands should call handleSuccessfulTeleport() instead
        Text.sendDebugLog(INFO, "Async position monitoring disabled - using handleSuccessfulTeleport() approach");
    }

    /**
     * Cleans up all data structures when the plugin is disabled
     */
    public void cleanup() {
        Text.sendDebugLog(INFO, "Cleaning up HandcuffsListener data structures on plugin disable");

        // Clear all restrained players and release them properly
        int releasedCount = 0;
        for (UUID handcuffeeId : new HashSet<>(restrainedPlayers.keySet())) {
            Player handcuffee = Bukkit.getPlayer(handcuffeeId);
            if (handcuffee != null && handcuffee.isOnline()) {
                // Ensure flight is properly disabled before releasing
                // This fixes the issue where restrained players keep flight after restart
                handcuffee.setAllowFlight(false);
                handcuffee.setFlying(false);
                releasePlayer(handcuffee);
                releasedCount++;
            }
        }

        // Clear all data structures
        restrainedPlayers.clear();
        handcufferToHandcuffees.clear();
        handcuffBobbers.clear();
        playersInTeleport.clear();
        dismountMessageCooldowns.clear();
        lastKnownPositions.clear();

        Text.sendDebugLog(INFO, "HandcuffsListener cleanup complete - released " + releasedCount + " restrained players");
    }
}
