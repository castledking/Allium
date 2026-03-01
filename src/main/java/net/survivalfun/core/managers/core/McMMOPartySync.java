package net.survivalfun.core.managers.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Soft-dependency helper to sync Allium party commands with mcMMO parties.
 * Uses reflection so mcMMO is not required at compile time.
 *
 * <p>We bypass mcMMO's createParty/addToParty to avoid duplicate messages (Allium already
 * sends its own). We directly add the party and members to mcMMO's internal structures.
 */
public final class McMMOPartySync {
    private static final String MCMO_PLUGIN_NAME = "mcMMO";
    private static final String PARTY_API_CLASS = "com.gmail.nossr50.api.PartyAPI";
    private static final String MCMO_CLASS = "com.gmail.nossr50.mcMMO";
    private static final String USER_MANAGER_CLASS = "com.gmail.nossr50.util.player.UserManager";
    private static final String PARTY_CLASS = "com.gmail.nossr50.datatypes.party.Party";
    private static final String PARTY_LEADER_CLASS = "com.gmail.nossr50.datatypes.party.PartyLeader";

    private McMMOPartySync() {}

    /**
     * @return true if mcMMO is installed and enabled
     */
    public static boolean isMcMMOAvailable() {
        return Bukkit.getPluginManager().getPlugin(MCMO_PLUGIN_NAME) != null
                && Bukkit.getPluginManager().isPluginEnabled(MCMO_PLUGIN_NAME);
    }

    /**
     * Create an mcMMO party with the given leader (silent - no mcMMO messages).
     */
    public static boolean createParty(Player leader, String partyName) {
        if (!isMcMMOAvailable() || leader == null || !leader.isOnline()) {
            return true;
        }
        try {
            Object mmoPlugin = Class.forName(MCMO_CLASS).getField("p").get(null);
            Object partyManager = mmoPlugin.getClass().getMethod("getPartyManager").invoke(mmoPlugin);
            Object mmoPlayer = Class.forName(USER_MANAGER_CLASS)
                    .getMethod("getPlayer", Player.class)
                    .invoke(null, leader);
            if (mmoPlayer == null) {
                return false;
            }
            String name = partyName.replace(".", "");
            Class<?> partyLeaderClass = Class.forName(PARTY_LEADER_CLASS);
            Object partyLeader = partyLeaderClass
                    .getConstructor(UUID.class, String.class)
                    .newInstance(leader.getUniqueId(), leader.getName());
            Class<?> partyClass = Class.forName(PARTY_CLASS);
            Object party = partyClass
                    .getConstructor(partyLeaderClass, String.class, String.class)
                    .newInstance(partyLeader, name, null);
            @SuppressWarnings("unchecked")
            List<Object> parties = (List<Object>) getField(partyManager, "parties");
            parties.add(party);
            addMemberToParty(mmoPlayer, party, leader);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Add a player to an existing mcMMO party (silent - no mcMMO messages).
     */
    public static boolean addToParty(Player player, String partyName) {
        if (!isMcMMOAvailable() || player == null || !player.isOnline()) {
            return true;
        }
        try {
            Object mmoPlugin = Class.forName(MCMO_CLASS).getField("p").get(null);
            Object partyManager = mmoPlugin.getClass().getMethod("getPartyManager").invoke(mmoPlugin);
            Object party = partyManager.getClass()
                    .getMethod("getParty", String.class)
                    .invoke(partyManager, partyName.replace(".", ""));
            if (party == null) {
                return false;
            }
            Object mmoPlayer = Class.forName(USER_MANAGER_CLASS)
                    .getMethod("getPlayer", Player.class)
                    .invoke(null, player);
            if (mmoPlayer == null) {
                return false;
            }
            addMemberToParty(mmoPlayer, party, player);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Object getField(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void addMemberToParty(Object mmoPlayer, Object party, Player player)
            throws ReflectiveOperationException {
        mmoPlayer.getClass().getMethod("setParty", Class.forName(PARTY_CLASS)).invoke(mmoPlayer, party);
        Object members = party.getClass().getMethod("getMembers").invoke(party);
        members.getClass().getMethod("put", Object.class, Object.class)
                .invoke(members, player.getUniqueId(), player.getName());
        Object onlineMembers = party.getClass().getMethod("getOnlineMembers").invoke(party);
        onlineMembers.getClass().getMethod("add", Object.class).invoke(onlineMembers, player);
    }

    /**
     * Remove a player from their mcMMO party.
     *
     * @param player the online player to remove
     * @return true if the call succeeded (or mcMMO isn't available)
     */
    public static boolean removeFromParty(Player player) {
        if (!isMcMMOAvailable() || player == null || !player.isOnline()) {
            return true;
        }
        try {
            Class<?> apiClass = Class.forName(PARTY_API_CLASS);
            Method removeFromParty = apiClass.getMethod("removeFromParty", Player.class);
            removeFromParty.invoke(null, player);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
