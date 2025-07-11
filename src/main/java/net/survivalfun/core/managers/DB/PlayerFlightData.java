package net.survivalfun.core.managers.DB;

/**
 * Record to hold player flight status data
 */
public record PlayerFlightData(boolean allowFlight, boolean isFlying) {
    public boolean allowFlight() {
        return allowFlight;
    }
    
    public boolean isFlying() {
        return isFlying;
    }
}
