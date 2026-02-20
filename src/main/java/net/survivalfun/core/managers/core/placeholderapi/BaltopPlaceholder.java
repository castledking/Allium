package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.economy.BalanceEntry;
import net.survivalfun.core.managers.economy.EconomyManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PlaceholderAPI expansion for baltop-related placeholders
 * Supports placeholders like %allium_baltop_balance_1%, %allium_baltop_player_1%, etc.
 */
public class BaltopPlaceholder extends PlaceholderExpansion {
    
    private final PluginStart plugin;
    private final EconomyManager economyManager;
    private List<BalanceEntry> cachedTopBalances;
    private long lastCacheUpdate = 0;
    private static final long CACHE_DURATION = 30000; // 30 seconds cache
    private static final int MAX_BALTOP_ENTRIES = 100; // Cache top 100 players
    
    public BaltopPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
        this.economyManager = plugin.getEconomyManager();
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
        return "1.0";
    }
    
    @Override
    public boolean persist() {
        return true;
    }
    
    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        String lowerIdentifier = identifier.toLowerCase();

        if (lowerIdentifier.equals("balance")) {
            return getPlayerBalanceRaw(player);
        }
        if (lowerIdentifier.equals("balance_fixed")) {
            return getPlayerBalanceFixed(player);
        }
        if (lowerIdentifier.equals("balance_formatted")) {
            return getPlayerBalanceFormatted(player);
        }
        if (lowerIdentifier.equals("balance_commas")) {
            return getPlayerBalanceCommas(player);
        }

        // Handle baltop placeholders
        if (lowerIdentifier.startsWith("baltop_")) {
            return handleBaltopPlaceholder(player, lowerIdentifier.substring(7)); // Remove "baltop_" prefix
        }
        
        return null;
    }
    
    /**
     * Handle baltop-related placeholders
     */
    private String handleBaltopPlaceholder(Player player, String identifier) {
        // Refresh cache if needed
        refreshCacheIfNeeded();
        
        // Handle player's rank placeholders
        if (identifier.equals("rank")) {
            return getPlayerRank(player);
        } else if (identifier.equals("rank_formatted")) {
            return getPlayerRankFormatted(player);
        }
        
        // Handle rank-specific placeholders
        if (identifier.startsWith("balance_") || identifier.startsWith("player_")) {
            return handleRankSpecificPlaceholder(identifier);
        }
        
        return null;
    }
    
    /**
     * Handle rank-specific placeholders like balance_1, player_1, etc.
     */
    private String handleRankSpecificPlaceholder(String identifier) {
        try {
            String[] parts = identifier.split("_");
            if (parts.length < 2) {
                return null;
            }
            
            String type = parts[0]; // balance, player
            String rankStr = parts[parts.length - 1]; // The rank number
            int rank = Integer.parseInt(rankStr);
            
            if (rank < 1 || rank > cachedTopBalances.size()) {
                return "";
            }
            
            BalanceEntry entry = cachedTopBalances.get(rank - 1); // Convert to 0-based index
            
            switch (type) {
                case "balance":
                    return handleBalancePlaceholder(parts, entry);
                case "player":
                    return handlePlayerPlaceholder(parts, entry);
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Handle balance-related placeholders
     */
    private String handleBalancePlaceholder(String[] parts, BalanceEntry entry) {
        BigDecimal balance = entry.getBalance();
        
        if (parts.length == 2) {
            // %allium_baltop_balance_<rank>% - raw balance as double
            return String.valueOf(balance.doubleValue());
        } else if (parts.length == 3) {
            String modifier = parts[1];
            switch (modifier) {
                case "commas":
                    // %allium_baltop_balance_commas_<rank>% - balance with commas
                    return formatWithCommas(balance);
                case "fixed":
                    // %allium_baltop_balance_fixed_<rank>% - balance without decimals
                    return String.valueOf(balance.intValue());
                case "formatted":
                    // %allium_baltop_balance_formatted_<rank>% - formatted balance (10k, 1M, etc.)
                    return formatShortened(balance);
                default:
                    return null;
            }
        }
        
        return null;
    }
    
    /**
     * Handle player-related placeholders
     */
    private String handlePlayerPlaceholder(String[] parts, BalanceEntry entry) {
        if (parts.length == 2) {
            // %allium_baltop_player_<rank>% - player display name
            return entry.getPlayerName();
        } else if (parts.length == 3 && parts[1].equals("stripped")) {
            // %allium_baltop_player_stripped_<rank>% - raw player name (for future /nick support)
            return entry.getPlayerName(); // For now, same as display name
        }
        
        return null;
    }
    
    /**
     * Get the player's rank in baltop
     */
    private String getPlayerRank(Player player) {
        if (player == null || cachedTopBalances == null) {
            return "0";
        }
        
        UUID playerUUID = player.getUniqueId();
        for (int i = 0; i < cachedTopBalances.size(); i++) {
            if (cachedTopBalances.get(i).getPlayerUUID().equals(playerUUID)) {
                return String.valueOf(i + 1); // Convert to 1-based rank
            }
        }
        
        return "0"; // Player not in top list
    }
    
    /**
     * Get the player's formatted rank in baltop (e.g., "1st", "2nd", "3rd")
     */
    private String getPlayerRankFormatted(Player player) {
        String rank = getPlayerRank(player);
        if (rank.equals("0")) {
            return "Unranked";
        }
        
        return formatRankWithSuffix(Integer.parseInt(rank));
    }
    
    /**
     * Format rank with ordinal suffix (1st, 2nd, 3rd, etc.)
     */
    private String formatRankWithSuffix(int rank) {
        if (rank >= 11 && rank <= 13) {
            return rank + "th";
        }
        
        switch (rank % 10) {
            case 1: return rank + "st";
            case 2: return rank + "nd";
            case 3: return rank + "rd";
            default: return rank + "th";
        }
    }
    
    /**
     * Format balance with commas (e.g., 1,000.50)
     */
    private String formatWithCommas(BigDecimal balance) {
        DecimalFormat formatter = new DecimalFormat("#,##0.##");
        return formatter.format(balance);
    }
    
    /**
     * Format balance in shortened form (e.g., 10k, 1.5M, 2.3B)
     */
    private String formatShortened(BigDecimal balance) {
        double value = balance.doubleValue();
        
        if (value >= 1_000_000_000) {
            return String.format("%.1fB", value / 1_000_000_000);
        } else if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000);
        } else if (value >= 1_000) {
            return String.format("%.1fk", value / 1_000);
        } else {
            return String.valueOf((int) value);
        }
    }
    
    /**
     * Refresh the baltop cache if it's older than the cache duration
     */
    private void refreshCacheIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (cachedTopBalances == null || (currentTime - lastCacheUpdate) > CACHE_DURATION) {
            try {
                cachedTopBalances = economyManager.getTopBalances(MAX_BALTOP_ENTRIES);
                lastCacheUpdate = currentTime;
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to refresh baltop cache: " + e.getMessage());
            }
        }
    }
    
    /**
     * Force refresh the baltop cache
     */
    public void forceRefreshCache() {
        try {
            cachedTopBalances = economyManager.getTopBalances(MAX_BALTOP_ENTRIES);
            lastCacheUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to force refresh baltop cache: " + e.getMessage());
        }
    }

    private String getPlayerBalanceRaw(Player player) {
        BigDecimal balance = getTargetBalance(player);
        return String.valueOf(balance.doubleValue());
    }

    private String getPlayerBalanceFixed(Player player) {
        BigDecimal balance = getTargetBalance(player);
        return String.valueOf(balance.intValue());
    }

    private String getPlayerBalanceFormatted(Player player) {
        BigDecimal balance = getTargetBalance(player);
        return formatShortened(balance);
    }

    private String getPlayerBalanceCommas(Player player) {
        BigDecimal balance = getTargetBalance(player);
        return formatWithCommas(balance);
    }

    private BigDecimal getTargetBalance(Player player) {
        if (player == null) {
            return BigDecimal.ZERO;
        }

        try {
            return economyManager.getBalance(player.getUniqueId());
        } catch (Exception ex) {
            Text.sendDebugLog(WARN, "Failed to fetch balance for " + player.getName(), ex);
            return BigDecimal.ZERO;
        }
    }
}
