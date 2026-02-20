package net.survivalfun.core.managers.core;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.PlayerVisibilityHelper;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages parties and player visibility for locator bar functionality.
 */
public class PartyManager {
    private static final long UPDATE_INTERVAL_TICKS = 10L;
    
    private final PluginStart plugin;
    private final Map<String, Party> parties;
    private final Map<UUID, String> playerToParty;
    // IMPROVED: Track invites with both sender and target information
    private final Map<String, Map<UUID, UUID>> sentInvites; // partyName -> (targetUUID -> senderUUID)
    private final Map<UUID, Set<String>> pendingInvites; // targetUUID -> set of party names (for quick lookup)
    // IMPROVED: Track visible players like PartyLocator for efficient visibility management
    private final Map<UUID, Set<UUID>> visiblePlayers;
    private SchedulerAdapter.TaskHandle distanceCheckTask;
    private TabListManager tabListManager;
    
    // Config values
    private boolean hideNonPartyMembers;
    private int showNonPartyMembersRadius;
    private boolean hideNonPartyMembersTab;
    private boolean paperAPIAvailable;

    // Temporary visibility overrides: player -> (target -> expiration time)
    private final Map<UUID, Map<UUID, Long>> forcedVisibilityOverrides = new ConcurrentHashMap<>();

    public PartyManager(PluginStart plugin) {
        this.plugin = plugin;
        this.parties = new HashMap<>();
        this.playerToParty = new HashMap<>();
        this.sentInvites = new HashMap<>(); // partyName -> (targetUUID -> senderUUID)
        this.pendingInvites = new HashMap<>(); // targetUUID -> set of party names (for quick lookup)
        this.visiblePlayers = new ConcurrentHashMap<>();
        loadConfig();
        
        startDistanceCheckTask();
    }

    private boolean shouldSendTabPackets() {
        return tabListManager != null && hideNonPartyMembers;
    }

    /**
     * Load configuration values from config.yml
     */
    private void loadConfig() {
        this.hideNonPartyMembers = plugin.getConfig().getBoolean("party-manager.hide-non-party-members", true);
        this.showNonPartyMembersRadius = plugin.getConfig().getInt("party-manager.show-non-party-members-radius", 48);
        this.hideNonPartyMembersTab = plugin.getConfig().getBoolean("party-manager.hide-non-party-members-tab", false);

        // Check if Paper API is available for entity visibility
        try {
            // Try to call a Paper API method to see if it's available
            org.bukkit.entity.Player testPlayer = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
            if (testPlayer != null) {
                testPlayer.hideEntity(plugin, testPlayer); // This will throw NoSuchMethodError if Paper API not available
                this.paperAPIAvailable = true;
            } else {
                this.paperAPIAvailable = false;
            }
        } catch (NoSuchMethodError e) {
            this.paperAPIAvailable = false;
        }
    }

    /**
     * Reloads the configuration values from config.yml
     */
    public void reloadConfig() {
        loadConfig();
        // Removed debug log to reduce spam
    }

    /**
     * Get current configuration values for debugging
     */
    public void logCurrentConfig() {
        // Removed debug log to reduce spam
    }

    /**
     * Start the periodic distance check task for showing non-party members at radius
     */
    private void startDistanceCheckTask() {
        if (distanceCheckTask != null) {
            distanceCheckTask.cancel();
        }
        // Optional periodic visibility updates - can be enabled if needed
        // Currently disabled as visibility is event-driven for better performance
    }

    /**
     * IMPROVED: Determine if a target player should be visible to a viewer (for entity visibility).
     * Combines PartyLocator logic with existing radius-based visibility.
     * Party members are always visible regardless of distance.
     * Non-party members are visible within configured radius.
     */
    public boolean shouldBeVisible(Player viewer, Player target) {
        if (viewer.equals(target)) return true;
        
        // Check forced visibility overrides first
        int override = getForcedVisibilityOverride(viewer.getUniqueId(), target.getUniqueId());
        if (override == 1) return true;
        if (override == -1) return false;
        
        // Check if players are in the same world
        if (!viewer.getWorld().equals(target.getWorld())) return false;
        
        // Check if in same party - party members always see each other
        if (isInSameParty(viewer, target)) return true;
        
        // Check distance for non-party members
        double distanceSquared = viewer.getLocation().distanceSquared(target.getLocation());
        int radiusSquared = showNonPartyMembersRadius * showNonPartyMembersRadius;
        return distanceSquared <= radiusSquared;
    }

    /**
     * Check if two players are in the same party
     */
    private boolean isInSameParty(Player playerOne, Player playerTwo) {
        Party partyOne = getPlayerParty(playerOne.getUniqueId());
        Party partyTwo = getPlayerParty(playerTwo.getUniqueId());
        return partyOne != null && partyTwo != null && partyOne.equals(partyTwo);
    }

    /**
     * Show player using the visibility helper
     */
    private void showPlayer(Player viewer, Player target) {
        PlayerVisibilityHelper.showPlayer(viewer, target);
    }

    /**
     * Hide player - send destroy packet first for cleaner hiding, then hide
     */
    private void hidePlayer(Player viewer, Player target) {
        // Note: Entity destroy packet sending is handled by TabListManager for tablist
        // For entity visibility, just use the standard hide method
        PlayerVisibilityHelper.hidePlayer(viewer, target);
    }

    /**
     * Update a single visibility relation between viewer and target
     * More efficient than updating all visibility
     */
    private void updateSingleRelation(Player viewer, Player target) {
        boolean shouldSee = shouldBeVisible(viewer, target);
        boolean doesSee = visiblePlayers.getOrDefault(viewer.getUniqueId(), Collections.emptySet()).contains(target.getUniqueId());

        if (shouldSee && !doesSee) {
            showPlayerConsideringSpectator(viewer, target);
            visiblePlayers.computeIfAbsent(viewer.getUniqueId(), k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(target.getUniqueId());
        } else if (!shouldSee && doesSee) {
            hidePlayer(viewer, target);
            Set<UUID> visible = visiblePlayers.get(viewer.getUniqueId());
            if (visible != null) {
                visible.remove(target.getUniqueId());
            }
        }
    }

    /**
     * Handle visibility updates for a specific player when their radius-based visibility changes
     * @param targetPlayer The player whose visibility status changed
     * @param viewers List of players who can see this target (all online players except target)
     */
    public void handleRadiusVisibilityChange(Player targetPlayer, List<Player> viewers) {
        if (!shouldSendTabPackets()) {
            return;
        }

        List<Player> playersToAdd = new ArrayList<>();
        List<Player> playersToRemove = new ArrayList<>();

        for (Player viewer : viewers) {
            if (viewer.equals(targetPlayer)) {
                continue;
            }

            boolean shouldBeVisible = shouldBeVisibleToViewer(targetPlayer, viewer);

            if (shouldBeVisible) {
                playersToAdd.add(viewer);
            } else {
                playersToRemove.add(viewer);
            }
        }

        // Send tablist update packets
        if (!playersToAdd.isEmpty()) {
            tabListManager.sendTabListAddPacket(targetPlayer, playersToAdd);
        }
        if (!playersToRemove.isEmpty()) {
            tabListManager.sendTabListRemovePacket(targetPlayer, playersToRemove);
        }
    }

    /**
     * Determine if a target player should be visible to a specific viewer based on party and radius rules
     * @param target The target player
     * @param viewer The viewing player
     * @return true if target should be visible to viewer in tablist
     */
    private boolean shouldBeVisibleToViewer(Player target, Player viewer) {
        // Spectators can always see everyone
        if (viewer.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return true;
        }

        // If hide-non-party-members-tab is false, everyone is visible
        if (!hideNonPartyMembersTab) {
            return true;
        }

        // Check if both players are in the same party
        Party viewerParty = getPlayerParty(viewer.getUniqueId());
        Party targetParty = getPlayerParty(target.getUniqueId());

        // Party members are always visible to each other
        if (viewerParty != null && targetParty != null && viewerParty.equals(targetParty)) {
            return true;
        }

        // Non-party members are hidden when hide-non-party-members-tab is true
        return false;
    }

    /**
     * Creates a new party with the given name and leader.
     */
    public boolean createParty(String name, UUID leader) {
        if (parties.containsKey(name) || playerToParty.containsKey(leader)) {
            return false; // Party exists or player already in party
        }
        Party party = new Party(name, leader);
        parties.put(name, party);
        playerToParty.put(leader, name);
        pendingInvites.remove(leader);
        updateVisibility();

        // Trigger tablist updates for party creation
        if (shouldSendTabPackets()) {
            Player leaderPlayer = Bukkit.getPlayer(leader);
            if (leaderPlayer != null) {
                List<Player> allOtherPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                allOtherPlayers.remove(leaderPlayer);
                handleRadiusVisibilityChange(leaderPlayer, allOtherPlayers);
            }
        }
        return true;
    }

    /**
     * Disbands a party.
     */
    public boolean disbandParty(String name) {
        Party party = parties.remove(name);
        if (party != null) {
            for (UUID member : party.getMembers()) {
                playerToParty.remove(member);
                pendingInvites.remove(member);
            }
            removePendingInvitesForParty(name);
            updateVisibility();

            // Trigger tablist updates for disbanded party members
            if (shouldSendTabPackets()) {
                for (UUID memberId : party.getMembers()) {
                    Player member = Bukkit.getPlayer(memberId);
                    if (member != null) {
                        List<Player> allOtherPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                        allOtherPlayers.remove(member);
                        handleRadiusVisibilityChange(member, allOtherPlayers);
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Adds a player to a party.
     */
    public PartyJoinResult joinParty(String name, UUID player) {
        Party party = parties.get(name);
        if (party == null) {
            return PartyJoinResult.NOT_FOUND;
        }
        if (playerToParty.containsKey(player)) {
            return PartyJoinResult.ALREADY_IN_PARTY;
        }

        Set<String> invites = pendingInvites.get(player);
        if (invites == null || !invites.contains(name)) {
            return PartyJoinResult.NOT_INVITED;
        }

        party.addMember(player);
        playerToParty.put(player, name);
        pendingInvites.remove(player);

        // Clean up the accepted invite from sentInvites
        Map<UUID, UUID> partySentInvites = sentInvites.get(name);
        if (partySentInvites != null) {
            partySentInvites.remove(player);
            if (partySentInvites.isEmpty()) {
                sentInvites.remove(name);
            }
        }

        updateVisibility();

        // Trigger tablist updates for the joining player
        if (shouldSendTabPackets()) {
            Player joiningPlayer = Bukkit.getPlayer(player);
            if (joiningPlayer != null) {
                List<Player> allOtherPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                allOtherPlayers.remove(joiningPlayer);
                handleRadiusVisibilityChange(joiningPlayer, allOtherPlayers);
            }
        }
        return PartyJoinResult.SUCCESS;
    }

    /**
     * Removes a player from their party.
     */
    public boolean leaveParty(UUID player) {
        String partyName = playerToParty.remove(player);
        if (partyName != null) {
            Party party = parties.get(partyName);
            if (party != null) {
                if (party.getLeader().equals(player)) {
                    // Leader attempting to leave should be prevented by command layer; restore mapping
                    playerToParty.put(player, partyName);
                    return false;
                }

                party.removeMember(player);
                updateVisibility();

                // Trigger tablist updates for the leaving player
                if (shouldSendTabPackets()) {
                    Player leavingPlayer = Bukkit.getPlayer(player);
                    if (leavingPlayer != null) {
                        List<Player> allOtherPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                        allOtherPlayers.remove(leavingPlayer);
                        handleRadiusVisibilityChange(leavingPlayer, allOtherPlayers);
                    }
                }
            }
            pendingInvites.remove(player);
            return true;
        }
        return false;
    }

    public boolean disbandPartyIfLeader(UUID requester) {
        Party party = getPlayerParty(requester);
        if (party == null || !party.getLeader().equals(requester)) {
            return false;
        }
        return disbandParty(party.getName());
    }

    /**
     * Gets the party for a player.
     */
    public Party getPlayerParty(UUID player) {
        String partyName = playerToParty.get(player);
        return partyName != null ? parties.get(partyName) : null;
    }

    public boolean isPartyLeader(UUID player) {
        Party party = getPlayerParty(player);
        return party != null && party.getLeader().equals(player);
    }

    public int getShowNonPartyMembersRadius() {
        return showNonPartyMembersRadius;
    }

    public PartyInviteResult invitePlayer(UUID inviter, UUID target) {
        if (inviter.equals(target)) {
            return PartyInviteResult.SELF_INVITE;
        }

        Party party = getPlayerParty(inviter);
        if (party == null) {
            return PartyInviteResult.NO_PARTY;
        }

        if (!party.getLeader().equals(inviter)) {
            return PartyInviteResult.NOT_LEADER;
        }

        if (playerToParty.containsKey(target)) {
            return PartyInviteResult.TARGET_IN_PARTY;
        }

        String partyName = party.getName();
        Set<String> invites = pendingInvites.computeIfAbsent(target, k -> new HashSet<>());
        if (!invites.add(partyName)) {
            return PartyInviteResult.ALREADY_INVITED;
        }

        // Track who sent the invite for proper cleanup
        Map<UUID, UUID> partySentInvites = sentInvites.computeIfAbsent(partyName, k -> new HashMap<>());
        partySentInvites.put(target, inviter);

        return PartyInviteResult.SUCCESS;
    }

    /**
     * Gets all parties.
     */
    public Map<String, Party> getParties() {
        return new HashMap<>(parties);
    }

    /**
     * Updates visibility for all players based on party membership and config settings.
     * Players in parties can see each other on the locator bar regardless of distance.
     * Non-party members are shown based on distance and config settings.
     *
     * OPTIMIZED: Only updates visibility when party membership actually changes,
     * avoiding the previous O(N²) complexity.
     */
    public void updateVisibility() {
        if (!hideNonPartyMembers) {
            return;
        }

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

        // Update visibility for each player relative to all others
        // This is still O(N²) but only called when party membership changes
        for (Player viewer : onlinePlayers) {
            for (Player target : onlinePlayers) {
                if (viewer.equals(target)) {
                    continue;
                }
                updatePlayerVisibility(viewer, target);
            }
        }
    }

    /**
     * Updates visibility for a specific player relative to all other players.
     * More efficient than updating all visibility combinations.
     *
     * @param player The player whose visibility should be updated
     */
    public void updateVisibilityForPlayer(Player player) {
        if (!hideNonPartyMembers) {
            return;
        }

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

        for (Player otherPlayer : onlinePlayers) {
            if (!otherPlayer.equals(player)) {
                // Update visibility in both directions
                updatePlayerVisibility(player, otherPlayer);
                updatePlayerVisibility(otherPlayer, player);
            }
        }
    }

    public boolean arePartyFeaturesEnabled() {
        return hideNonPartyMembers;
    }
    

    /**
     * Handles player movement - updates visibility when players move in/out of radius
     * @param movedPlayer The player who moved
     *
     * OPTIMIZED: Only updates visibility for non-party members within radius.
     * Party members always see each other regardless of distance.
     */
    public void onPlayerMove(Player movedPlayer) {
        if (!hideNonPartyMembers) {
            return;
        }

        UUID movedPlayerId = movedPlayer.getUniqueId();
        Party movedPlayerParty = getPlayerParty(movedPlayerId);

        // Update visibility for all other players relative to the moved player
        // Only check players who are NOT in the same party (party members always see each other)
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            if (otherPlayer.equals(movedPlayer)) {
                continue;
            }

            UUID otherPlayerId = otherPlayer.getUniqueId();
            Party otherPlayerParty = getPlayerParty(otherPlayerId);

            // Skip if both players are in the same party (they always see each other)
            if (movedPlayerParty != null && movedPlayerParty.equals(otherPlayerParty)) {
                continue;
            }

            // Update visibility in both directions for non-party members
            updatePlayerVisibility(movedPlayer, otherPlayer);
            updatePlayerVisibility(otherPlayer, movedPlayer);
        }

        // Tablist visibility is handled immediately when entity visibility changes
    }

    /**
     * Update visibility between two specific players based on distance and party rules
     * @param viewer The viewing player
     * @param target The target player to show/hide
     */
    private void updatePlayerVisibility(Player viewer, Player target) {
        if (!hideNonPartyMembers) {
            return;
        }
        UUID viewerId = viewer.getUniqueId();
        Party viewerParty = getPlayerParty(viewerId);
        Party targetParty = getPlayerParty(target.getUniqueId());
        boolean sameParty = viewerParty != null && targetParty != null && viewerParty.equals(targetParty);

        if (!Boolean.TRUE.equals(viewer.getWorld().getGameRuleValue(GameRule.LOCATOR_BAR))) {
            return;
        }

        // Party members are always visible to each other
        if (sameParty) {
            showPlayerConsideringSpectator(viewer, target);
            return;
        }

        // Check forced visibility overrides
        int override = getForcedVisibilityOverride(viewerId, target.getUniqueId());
        if (override == 1) {
            showPlayerConsideringSpectator(viewer, target);
            return;
        } else if (override == -1) {
            PlayerVisibilityHelper.hidePlayer(viewer, target);
            return;
        }

        // Handle non-party member visibility based on distance
        handleNonPartyVisibility(viewer, target);
    }

    /**
     * Handle visibility between two players who are not in the same party.
     * Shows players if they are within the configured radius.
     */
    private void handleNonPartyVisibility(Player viewer, Player target) {
        if (!hideNonPartyMembers) {
            return;
        }
        if (isSpectatorWithPermission(viewer)) {
            showPlayerConsideringSpectator(viewer, target);
            return;
        }

        if (shouldHideSpectator(viewer, target)) {
            PlayerVisibilityHelper.hidePlayer(viewer, target);
            return;
        }

        // Check if players are in the same world
        if (!viewer.getWorld().equals(target.getWorld())) {
            // Players are in different worlds - hide non-party members
            PlayerVisibilityHelper.hidePlayer(viewer, target);
            return;
        }

        double distance = viewer.getLocation().distance(target.getLocation());
        if (distance <= showNonPartyMembersRadius) {
            PlayerVisibilityHelper.showPlayer(viewer, target);
            if (shouldSendTabPackets()) {
                tabListManager.forceSendTabListAddPacket(target, List.of(viewer));
            }
        } else {
            PlayerVisibilityHelper.hidePlayer(viewer, target);
            if (shouldSendTabPackets()) {
                if (tabListManager.shouldBeVisibleInTabList(viewer, target)) {
                    // Keep non-party members visible in tablist when configured to do so
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (shouldSendTabPackets()) {
                            tabListManager.sendTabListAddPacket(target, List.of(viewer));
                        }
                    }, 2L); // 2 tick delay to ensure entity visibility is processed first
                } else {
                    tabListManager.sendTabListRemovePacket(target, List.of(viewer));
                }
            }
        }
    }

    private void showPlayerConsideringSpectator(Player viewer, Player target) {
        if (shouldHideSpectator(viewer, target)) {
            PlayerVisibilityHelper.hidePlayer(viewer, target);
            // Send proactive tablist add packet to ensure spectator stays visible in tablist
            if (shouldSendTabPackets() && tabListManager.shouldBeVisibleInTabList(viewer, target)) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (shouldSendTabPackets()) {
                        tabListManager.sendTabListAddPacket(target, List.of(viewer));
                    }
                }, 2L); // 2 tick delay to ensure entity visibility is processed first
            }
        } else {
            PlayerVisibilityHelper.showPlayer(viewer, target);
            if (shouldSendTabPackets()) {
                tabListManager.forceSendTabListAddPacket(target, List.of(viewer));
            }
        }
    }

    private boolean shouldHideSpectator(Player viewer, Player target) {
        return target.getGameMode() == GameMode.SPECTATOR && !viewer.hasPermission("allium.gamemode.spectator");
    }

    private boolean isSpectatorWithPermission(Player player) {
        return player.getGameMode() == GameMode.SPECTATOR && player.hasPermission("allium.gamemode.spectator");
    }

    /**
     * Check if there are any forced visibility overrides between two players
     * Returns: 1 for forced show, -1 for forced hide, 0 for no override
     */
    private int getForcedVisibilityOverride(UUID sourceId, UUID targetId) {
        Map<UUID, Long> sourceOverrides = forcedVisibilityOverrides.get(sourceId);
        if (sourceOverrides == null) {
            return 0;
        }

        Long expirationTime = sourceOverrides.get(targetId);
        if (expirationTime == null) {
            return 0;
        }

        long currentTime = System.currentTimeMillis();

        if (expirationTime > 0) {
            // Positive expiration time = forced show
            if (currentTime < expirationTime) {
                return 1;
            } else {
                // Expired, clean up
                sourceOverrides.remove(targetId);
            }
        } else {
            // Negative expiration time = forced hide
            long hideExpiration = -expirationTime;
            if (currentTime < hideExpiration) {
                return -1;
            } else {
                // Expired, clean up
                sourceOverrides.remove(targetId);
            }
        }

        return 0;
    }

    /**
     * Handles player join event - updates visibility for the joining player.
     */
    public void onPlayerJoin(Player player) {
        // Initialize visibility tracking for the joining player
        visiblePlayers.computeIfAbsent(player.getUniqueId(), k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        
        if (!hideNonPartyMembers) {
            return;
        }
        // Update visibility for the joining player
        updateVisibilityForPlayer(player);

        if (shouldSendTabPackets()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (shouldSendTabPackets()) {
                    tabListManager.ensureAllPlayersVisibleInTabLists();
                }
            }, 5L);

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (shouldSendTabPackets() && player.isOnline()) {
                    List<Player> others = new ArrayList<>(plugin.getServer().getOnlinePlayers());
                    others.remove(player);

                    if (!others.isEmpty()) {
                        tabListManager.forceSendTabListAddPacketForMultiplePlayers(Collections.singletonList(player), others);
                        tabListManager.forceSendTabListAddPacketForMultiplePlayers(others, Collections.singletonList(player));
                    }
                }
            }, 2L);
        }

        // Send immediate tablist add packets AFTER entity visibility updates with a delay
        // This ensures TAB plugin entity visibility processing completes before we establish tablist
        if (shouldSendTabPackets() && !hideNonPartyMembersTab) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (shouldSendTabPackets() && player.isOnline()) {
                    List<Player> allOnlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
                    allOnlinePlayers.remove(player); // Don't add the joining player to themselves

                    // Send packets TO the joining player (they see everyone)
                    tabListManager.sendTabListAddPacketForMultiplePlayers(allOnlinePlayers, Arrays.asList(player));

                    // Send packets FROM the joining player TO everyone else (everyone sees them)
                    for (Player otherPlayer : allOnlinePlayers) {
                        tabListManager.sendTabListAddPacket(player, List.of(otherPlayer));
                    }
                }
            }, 20L); // 20 tick delay to ensure entity visibility processing completes
        }

        // Send pending party invitations to the player
        UUID playerId = player.getUniqueId();
        Set<String> invites = pendingInvites.get(playerId);
        if (invites != null) {
            for (String partyName : invites) {
                Party party = parties.get(partyName);
                if (party != null) {
                    // Send invitation message to the player
                    player.sendMessage("You have been invited to join party " + partyName);
                }
            }
        }

        // Send initial tablist state for the joining player
        if (shouldSendTabPackets()) {
            if (!hideNonPartyMembersTab) {
                // When hide-non-party-members-tab is false, use delayed sendInitialTabListState
                // This ensures all players are visible in tablist after entity visibility processing
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (shouldSendTabPackets() && player.isOnline()) {
                        tabListManager.sendInitialTabListState(player);
                    }
                }, 20L); // 20 tick delay for extra safety
            } else {
                // When hide-non-party-members-tab is true, use party-based visibility rules
                tabListManager.sendInitialTabListState(player);
            }
        }
    }

    /**
     * Handles player quit event - removes player from party and cleans up invites.
     */
    public void onPlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Clean up visiblePlayers tracking - remove player from all visibility sets
        visiblePlayers.remove(playerId);
        for (Set<UUID> visibleSet : visiblePlayers.values()) {
            visibleSet.remove(playerId);
        }

        if (!hideNonPartyMembers) {
            return;
        }

        // Leave their current party (if any)
        leaveParty(playerId);

        // Clean up any pending invites this player has sent to others
        // Now we can properly track and remove invites sent by this player
        for (Map.Entry<String, Map<UUID, UUID>> partyEntry : sentInvites.entrySet()) {
            String partyName = partyEntry.getKey();
            Map<UUID, UUID> partySentInvites = partyEntry.getValue();

            // Find invites sent by this player
            Iterator<Map.Entry<UUID, UUID>> inviteIterator = partySentInvites.entrySet().iterator();
            while (inviteIterator.hasNext()) {
                Map.Entry<UUID, UUID> inviteEntry = inviteIterator.next();
                UUID senderUUID = inviteEntry.getValue();

                if (senderUUID.equals(playerId)) {
                    // This player sent this invite, remove it
                    UUID targetUUID = inviteEntry.getKey();
                    inviteIterator.remove();

                    // Also remove from pendingInvites for the target
                    Set<String> targetInvites = pendingInvites.get(targetUUID);
                    if (targetInvites != null) {
                        targetInvites.remove(partyName);
                        if (targetInvites.isEmpty()) {
                            pendingInvites.remove(targetUUID);
                        }
                    }

                    plugin.getLogger().info("Cleaned up invite sent by " + player.getName() + " to party " + partyName);
                }
            }

            // Clean up empty party invite maps
            if (partySentInvites.isEmpty()) {
                sentInvites.remove(partyName);
            }
        }

        // Update visibility for all remaining players (when a player quits, all other players need updates)
        // This is necessary because the quitting player's relationships with others change
        updateVisibility();

        // Remove the leaving player from all tab lists
        if (shouldSendTabPackets()) {
            // Send immediately for instant effect
            tabListManager.sendTabListRemoveForLeavingPlayer(player);
            
            // Send again with delay as backup (TAB plugin might interfere)
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (shouldSendTabPackets()) {
                    tabListManager.sendTabListRemoveForLeavingPlayer(player);
                }
            }, 2L); // 2 tick delay
        }
    }

    /**
     * Cleans up resources on disable.
     */
    public void setTabListManager(TabListManager tabListManager) {
        this.tabListManager = tabListManager;
    }

    private void removePendingInvitesForParty(String partyName) {
        // Remove all invites for this party from both data structures
        Map<UUID, UUID> partySentInvites = sentInvites.remove(partyName);
        if (partySentInvites != null) {
            for (UUID targetUUID : partySentInvites.keySet()) {
                // Remove from pendingInvites for the target
                Set<String> targetInvites = pendingInvites.get(targetUUID);
                if (targetInvites != null) {
                    targetInvites.remove(partyName);
                    if (targetInvites.isEmpty()) {
                        pendingInvites.remove(targetUUID);
                    }
                }
            }
        }
    }

    public enum PartyInviteResult {
        SUCCESS,
        NO_PARTY,
        NOT_LEADER,
        TARGET_IN_PARTY,
        ALREADY_INVITED,
        SELF_INVITE
    }

    public enum PartyJoinResult {
        SUCCESS,
        NOT_FOUND,
        ALREADY_IN_PARTY,
        NOT_INVITED
    }

    /**
     * Force show a target player to a source player for a specified duration.
     * @param sourcePlayer The player who will see the target
     * @param targetPlayer The player to be shown
     * @param durationSeconds Duration in seconds (0 for permanent)
     */
    public void forceShowPlayer(Player sourcePlayer, Player targetPlayer, long durationSeconds) {
        UUID sourceId = sourcePlayer.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        Map<UUID, Long> sourceOverrides = forcedVisibilityOverrides.computeIfAbsent(sourceId, k -> new ConcurrentHashMap<>());

        long expirationTime;
        if (durationSeconds <= 0) {
            expirationTime = Long.MAX_VALUE; // Permanent show
        } else {
            expirationTime = System.currentTimeMillis() + (durationSeconds * 1000);
        }

        sourceOverrides.put(targetId, expirationTime);
        updatePlayerVisibility(sourcePlayer, targetPlayer);
    }

    /**
     * Force hide a target player from a source player for a specified duration.
     * @param sourcePlayer The player who will not see the target
     * @param targetPlayer The player to be hidden
     * @param durationSeconds Duration in seconds (0 for permanent)
     */
    public void forceHidePlayer(Player sourcePlayer, Player targetPlayer, long durationSeconds) {
        UUID sourceId = sourcePlayer.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        Map<UUID, Long> sourceOverrides = forcedVisibilityOverrides.computeIfAbsent(sourceId, k -> new ConcurrentHashMap<>());

        long expirationTime;
        if (durationSeconds <= 0) {
            expirationTime = Long.MIN_VALUE; // Permanent hide
        } else {
            expirationTime = -(System.currentTimeMillis() + (durationSeconds * 1000));
        }

        sourceOverrides.put(targetId, expirationTime);
        updatePlayerVisibility(sourcePlayer, targetPlayer);
    }

    /**
     * Reset any forced visibility overrides between two players.
     * @param sourcePlayer The player whose visibility settings to reset
     * @param targetPlayer The target player whose visibility to reset
     */
    public void resetPlayer(Player sourcePlayer, Player targetPlayer) {
        UUID sourceId = sourcePlayer.getUniqueId();
        UUID targetId = targetPlayer.getUniqueId();

        Map<UUID, Long> sourceOverrides = forcedVisibilityOverrides.get(sourceId);
        if (sourceOverrides != null) {
            sourceOverrides.remove(targetId);
        }
        updatePlayerVisibility(sourcePlayer, targetPlayer);
    }

    /**
     * Forces an immediate, targeted visibility refresh for one or more players.
     * Call this when a player joins, leaves, or a party is disbanded.
     * Similar to PartyLocator's refreshVisibilityFor method.
     * @param playersToUpdate The player(s) whose party status has changed.
     */
    public void refreshVisibilityFor(Player... playersToUpdate) {
        if (playersToUpdate == null) return;

        for (Player player : playersToUpdate) {
            if (player == null || !player.isOnline()) continue;

            // Update what this player can see
            updateVisibilityForPlayer(player);

            // Update what all other players see of this player
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(player)) continue;
                updateSingleRelation(viewer, player);
            }
        }
    }
}
