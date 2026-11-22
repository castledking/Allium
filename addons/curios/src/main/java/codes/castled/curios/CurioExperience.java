package codes.castled.curios;

import org.bukkit.entity.Player;

/** Server-thread entry point for quests, jobs and other optional progression sources. */
public interface CurioExperience {
    /** Awards XP to equipped curios and returns how many items changed. */
    int award(Player player, double xp);
}
