package codes.castled.allium.tradingcards.xp;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import dev.aurelium.auraskills.api.event.mana.ManaAbilityActivateEvent;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Card xp for using an AuraSkills ability.
 *
 * <p>The one source that <b>is</b> compiled against AuraSkills, because Allium
 * already declares its api artifact for the stat modifiers and adding a second
 * mechanism for the same jar would be a needless reflection layer. It is still
 * conditional at runtime — the class is only loaded if the plugin is present,
 * which is why this listener is only registered behind an isPluginEnabled check
 * rather than unconditionally.
 */
public class AuraSkillsAbilityListener implements Listener {

    private final CardXpService xp;
    private final Logger logger;

    public AuraSkillsAbilityListener(CardXpService xp, Logger logger) {
        this.xp = xp;
        this.logger = logger;
    }

    /**
     * An ability being used.
     *
     * <p>Fires on activation rather than on effect, so an ability that misses
     * still pays. That is deliberate: the card is paying for practice with the
     * skill, not for damage dealt, and a miss-based reward would discourage
     * abilities whose value is not damage.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbility(ManaAbilityActivateEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        // A short per-source cooldown keeps a high-mana build from levelling a
        // card purely by chaining abilities, which would make mana the only
        // xp worth having.
        CardXpService.Award award = xp.award(player, "auraskills-ability", 1.0, null, null);
        if (award.gated() && award.reason() != null
            && logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("[" + TradingCardsBranding.DISPLAY_NAME + "] ability xp gated: "
                + award.reason());
        }
    }

    static UUID of(Player player) {
        return player == null ? null : player.getUniqueId();
    }

    static boolean ready() {
        return Bukkit.getServer() != null;
    }
}
