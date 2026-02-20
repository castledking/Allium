package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for handling Minecraft-style entity selectors.
 * Supports selectors like @p, @r, @a, @e[type=minecraft:sheep,distance=..3]
 */
public class Selectors {
    private final Lang lang;
    private final PluginStart plugin;

    public Selectors(Lang lang, PluginStart plugin) {
        this.lang = lang;
        this.plugin = plugin;
    }

    /**
     * Parses a Minecraft entity selector and returns matching entities
     * @param selector The selector string (e.g., "@e[type=minecraft:villager,distance=..3]")
     * @param executor The player executing the selector (for relative positioning and permissions)
     * @return List of matching entities, or empty list if selector is invalid
     */
    public List<Entity> parseSelector(String selector, Player executor) {
        if (selector == null || selector.isEmpty()) {
            return Collections.emptyList();
        }

        // Handle player names (non-selector)
        if (!selector.startsWith("@")) {
            Player target = Bukkit.getPlayer(selector);
            return target != null ? Collections.singletonList(target) : Collections.emptyList();
        }

        String baseSelector = selector.substring(1); // Remove @
        Map<String, String> parameters = new HashMap<>();

        // Parse parameters in brackets
        int bracketStart = baseSelector.indexOf('[');
        int bracketEnd = baseSelector.lastIndexOf(']');
        String selectorType = baseSelector;
        
        if (bracketStart != -1 && bracketEnd != -1 && bracketEnd > bracketStart) {
            selectorType = baseSelector.substring(0, bracketStart);
            String paramsStr = baseSelector.substring(bracketStart + 1, bracketEnd);
            
            // Parse parameters like "type=minecraft:villager,distance=..3"
            String[] paramPairs = paramsStr.split(",");
            for (String paramPair : paramPairs) {
                String[] parts = paramPair.split("=", 2);
                if (parts.length == 2) {
                    parameters.put(parts[0].trim().toLowerCase(), parts[1].trim());
                }
            }
        }

        Location executorLoc = executor != null ? executor.getLocation() : null;
        World world = executor != null ? executor.getWorld() : Bukkit.getWorlds().get(0);
        List<Entity> candidates = new ArrayList<>();

        // Get candidates based on selector type
        switch (selectorType) {
            case "p":
                // Nearest player
                if (executorLoc == null) return Collections.emptyList();
                candidates.addAll(findNearestPlayer(executor, world, parameters, executorLoc));
                break;
                
            case "r":
                // Random player
                candidates.addAll(getRandomPlayer(executor, world, parameters));
                break;
                
            case "a":
                // All players
                if (!hasPermission(executor, "allium.command.selector.all")) {
                    return Collections.emptyList();
                }
                candidates.addAll(world.getPlayers());
                break;
                
            case "e":
                // All entities
                if (!hasPermission(executor, "allium.command.selector.entities")) {
                    return Collections.emptyList();
                }
                candidates.addAll(world.getEntities());
                break;
                
            default:
                return Collections.emptyList();
        }

        // Filter candidates based on parameters
        return candidates.stream()
                .filter(entity -> matchesParameters(entity, parameters, executorLoc))
                .collect(Collectors.toList());
    }

    private boolean hasPermission(Player player, String permission) {
        return player == null || player.hasPermission(permission);
    }

    private List<Entity> findNearestPlayer(Player executor, World world, Map<String, String> parameters, Location executorLoc) {
        double minDistance = Double.MAX_VALUE;
        Player nearestPlayer = null;
        
        try {
            for (Player player : world.getPlayers()) {
                if (player.equals(executor)) continue;
                
                double distance = player.getLocation().distanceSquared(executorLoc);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestPlayer = player;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error finding nearest player: " + e.getMessage());
        }
        
        return nearestPlayer != null ? Collections.singletonList(nearestPlayer) : Collections.emptyList();
    }

    private List<Entity> getRandomPlayer(Player executor, World world, Map<String, String> parameters) {
        try {
            List<Player> players = world.getPlayers().stream()
                    .filter(p -> !p.equals(executor))
                    .collect(Collectors.toList());
                    
            if (players.isEmpty()) {
                if (lang != null) {
                    lang.sendMessage(executor, lang.get("no-players-found"));
                }
                return Collections.emptyList();
            }
            
            return Collections.singletonList(players.get(new Random().nextInt(players.size())));
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting random player: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean matchesParameters(Entity entity, Map<String, String> parameters, Location executorLoc) {
        if (parameters.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            switch (key) {
                case "type":
                    if (!matchesEntityType(entity, value)) {
                        return false;
                    }
                    break;
                    
                case "distance":
                    if (executorLoc == null || !matchesDistance(entity.getLocation(), executorLoc, value)) {
                        return false;
                    }
                    break;
                    
                case "name":
                    if (!entity.getName().equalsIgnoreCase(value)) {
                        return false;
                    }
                    break;
                    
                case "tag":
                    if (!(entity instanceof Tameable) || !((Tameable) entity).isTamed() || 
                        !((Tameable) entity).getOwner().getUniqueId().toString().equals(value)) {
                        return false;
                    }
                    break;
            }
        }
        
        return true;
    }

    private boolean matchesEntityType(Entity entity, String typeValue) {
        try {
            EntityType type = EntityType.valueOf(typeValue.toUpperCase().replace("minecraft:", ""));
            return entity.getType() == type;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean matchesDistance(Location entityLoc, Location executorLoc, String distanceValue) {
        double distance = entityLoc.distance(executorLoc);
        
        if (distanceValue.startsWith("..")) {
            double max = Double.parseDouble(distanceValue.substring(2));
            return distance <= max;
        } else if (distanceValue.endsWith("..")) {
            double min = Double.parseDouble(distanceValue.substring(0, distanceValue.length() - 2));
            return distance >= min;
        } else if (distanceValue.contains("..")) {
            // Split on the first occurrence of ".."
            int splitIndex = distanceValue.indexOf("..");
            double min = Double.parseDouble(distanceValue.substring(0, splitIndex));
            double max = Double.parseDouble(distanceValue.substring(splitIndex + 2));
            return distance >= min && distance <= max;
        } else {
            double exact = Double.parseDouble(distanceValue);
            return Math.abs(distance - exact) < 0.5; // Small tolerance for exact match
        }
    }
}
