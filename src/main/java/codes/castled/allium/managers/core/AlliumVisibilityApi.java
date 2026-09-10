package codes.castled.allium.managers.core;

import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;

/**
 * Optional bridge for EssentialsX's {@code AlliumVisibilityBridge}.
 *
 * <p>EssentialsX calls {@code PluginStart.getVisibilityApi()} via reflection
 * (see {@code com.earth2me.essentials.AlliumVisibilityBridge#resolve}) and
 * then {@code getVisibility(Player viewer, Player target)} on the returned
 * object. Returning {@code "VANISHED"} makes Essentials treat the target as
 * hidden from the viewer, returning {@code "PARTY_ISOLATED"} makes Essentials
 * treat them as visible (so a party-only-isolation system still wins over
 * Bukkit's {@code canSee}), returning {@code "VISIBLE"} forces visible, and
 * anything else falls back to Essentials' default decision.</p>
 *
 * <p>Allium keeps the vanish and party managers as the source of truth, so
 * this class is a thin adapter that maps their answers to the four
 * {@code Decision} values the bridge understands.</p>
 */
public final class AlliumVisibilityApi {

    public static final String DECISION_DEFAULT = "DEFAULT";
    public static final String DECISION_VISIBLE = "VISIBLE";
    public static final String DECISION_PARTY_ISOLATED = "PARTY_ISOLATED";
    public static final String DECISION_VANISHED = "VANISHED";

    private final PluginStart plugin;

    public AlliumVisibilityApi(PluginStart plugin) {
        this.plugin = plugin;
    }

    /**
     * Resolve the visibility decision {@code viewer} should see for {@code target}.
     *
     * <p>Precedence: a real vanish wins first (Essentials already checks its own
     * vanish metadata before calling us). Then Allium's vanish levels decide
     * whether the viewer can see the target. Finally, if the two players are
     * in different parties we report party-isolation so Essentials' fall-back
     * to Bukkit {@code canSee} does not undo Allium's party-only hiding.</p>
     */
    public String getVisibility(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return DECISION_DEFAULT;
        }
        if (viewer.equals(target)) {
            return DECISION_VISIBLE;
        }

        VanishManager vanish = plugin.getVanishManager();
        if (vanish != null && vanish.isVanished(target) && !vanish.canSee(viewer, target)) {
            return DECISION_VANISHED;
        }

        // Party-isolation: when Allium's party manager would hide the target from
        // the viewer (different party, outside the non-party radius), signal
        // PARTY_ISOLATED. Essentials treats that as "do not let Bukkit's canSee
        // override Allium's hide" so the two systems agree.
        codes.castled.allium.managers.core.PartyManager party = plugin.getPartyManager();
        if (party != null && !party.shouldBeVisibleIgnoreDistance(viewer, target)) {
            return DECISION_PARTY_ISOLATED;
        }

        return DECISION_DEFAULT;
    }
}
