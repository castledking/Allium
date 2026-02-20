package net.survivalfun.core.managers.DB;

import org.bukkit.GameMode;

/**
 * Represents a player's vanish state for database storage
 */
public class VanishState {
    private final int vanishLevel;
    private final GameMode gameMode;

    public VanishState(int vanishLevel, GameMode gameMode) {
        this.vanishLevel = vanishLevel;
        this.gameMode = gameMode;
    }

    public int getVanishLevel() {
        return vanishLevel;
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public boolean isVanished() {
        return vanishLevel > 0;
    }
}
