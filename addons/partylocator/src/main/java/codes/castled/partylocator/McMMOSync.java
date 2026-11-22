package codes.castled.partylocator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors party changes into mcMMO, through reflection so mcMMO is optional, as legacy did. It adds
 * the party and its members to mcMMO's own lists directly instead of calling mcMMO's commands, so
 * players are not sent mcMMO's join messages on top of the party's own.
 */
final class McMMOSync {
    private static final String MCMMO = "com.gmail.nossr50.mcMMO";
    private static final String USERS = "com.gmail.nossr50.util.player.UserManager";
    private static final String PARTY = "com.gmail.nossr50.datatypes.party.Party";
    private static final String LEADER = "com.gmail.nossr50.datatypes.party.PartyLeader";
    private static final String PARTY_API = "com.gmail.nossr50.api.PartyAPI";

    private McMMOSync() {}

    static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("mcMMO");
    }

    /** mcMMO party names cannot contain dots. */
    private static String name(String party) {
        return party.replace(".", "");
    }

    static void create(Player leader, String partyName) {
        if (!available() || leader == null || !leader.isOnline()) return;
        try {
            Object manager = manager();
            Object user = user(leader);
            if (user == null) return;
            Class<?> leaderClass = Class.forName(LEADER);
            Object partyLeader = leaderClass.getConstructor(UUID.class, String.class).newInstance(leader.getUniqueId(), leader.getName());
            Object party = Class.forName(PARTY).getConstructor(leaderClass, String.class, String.class)
                    .newInstance(partyLeader, name(partyName), null);
            @SuppressWarnings("unchecked")
            List<Object> parties = (List<Object>) field(manager, "parties");
            parties.add(party);
            addMember(user, party, leader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A different mcMMO build: the party still works without mcMMO.
        }
    }

    static void join(Player player, String partyName) {
        if (!available() || player == null || !player.isOnline()) return;
        try {
            Object manager = manager();
            Object party = manager.getClass().getMethod("getParty", String.class).invoke(manager, name(partyName));
            Object user = user(player);
            if (party != null && user != null) addMember(user, party, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    static void leave(Player player) {
        if (!available() || player == null || !player.isOnline()) return;
        try {
            Class.forName(PARTY_API).getMethod("removeFromParty", Player.class).invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    private static Object manager() throws ReflectiveOperationException {
        Object plugin = Class.forName(MCMMO).getField("p").get(null);
        return plugin.getClass().getMethod("getPartyManager").invoke(plugin);
    }

    private static Object user(Player player) throws ReflectiveOperationException {
        return Class.forName(USERS).getMethod("getPlayer", Player.class).invoke(null, player);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void addMember(Object user, Object party, Player player) throws ReflectiveOperationException {
        user.getClass().getMethod("setParty", Class.forName(PARTY)).invoke(user, party);
        Object members = party.getClass().getMethod("getMembers").invoke(party);
        members.getClass().getMethod("put", Object.class, Object.class).invoke(members, player.getUniqueId(), player.getName());
        Object online = party.getClass().getMethod("getOnlineMembers").invoke(party);
        online.getClass().getMethod("add", Object.class).invoke(online, player);
    }
}
