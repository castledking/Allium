package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Bukkit;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class TimePlaceholder extends PlaceholderExpansion {
    private static final long TICKS_PER_DAY = 24000L;
    private static final long TICKS_PER_HOUR = 1000L;
    private static final int DAYS_PER_YEAR = 365;
    private static final String[] MONTH_NAMES = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };
    private static final int[] DAYS_PER_MONTH = {
        31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
    };

    private final PluginStart plugin;

    public TimePlaceholder(PluginStart plugin) {
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
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        World world = player != null && player.isOnline() ? 
            player.getPlayer().getWorld() : 
            Bukkit.getWorlds().get(0); // Fallback to default world
            
        if (world == null) {
            return "";
        }

        switch (params.toLowerCase()) {
            case "world_date":
                return formatWorldDate(world);
            case "world_time":
                return formatWorldTime12(world);
            case "world_time_24":
                return formatWorldTime24(world);
            default:
                return "";
        }
    }

    private String formatWorldDate(World world) {
        // Get total days passed (1 day = 24000 ticks)
        long totalDays = world.getFullTime() / TICKS_PER_DAY;
        
        // Calculate year (starting from year 0)
        int year = (int) (totalDays / DAYS_PER_YEAR);
        int dayOfYear = (int) (totalDays % DAYS_PER_YEAR);
        
        // Calculate month and day
        int month = 0;
        int day = dayOfYear + 1; // Convert from 0-based to 1-based day
        
        for (int daysInMonth : DAYS_PER_MONTH) {
            if (day <= daysInMonth) {
                break;
            }
            day -= daysInMonth;
            month++;
            
            // Handle February in leap years (every 4th year)
            if (month == 1 && year % 4 == 0 && day > 28) {
                day--;
                if (day == 0) {
                    day = 29;
                    month--;
                }
            }
        }
        
        // Format: Jan 1, 2
        return String.format("%s %d, %d", MONTH_NAMES[month], day, year);
    }

    private String formatWorldTime12(World world) {
        long time = world.getTime();
        long hours = (time / TICKS_PER_HOUR + 6) % 24; // +6 to convert to 6 AM = 0
        long minutes = (time % TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR;
        
        String ampm = hours >= 12 ? "PM" : "AM";
        hours = hours % 12;
        if (hours == 0) hours = 12;
        
        return String.format("%d:%02d %s", hours, minutes, ampm);
    }
    
    private String formatWorldTime24(World world) {
        long time = world.getTime();
        long hours = (time / TICKS_PER_HOUR + 6) % 24; // +6 to convert to 6 AM = 0
        long minutes = (time % TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR;
        
        return String.format("%02d:%02d", hours, minutes);
    }
}
