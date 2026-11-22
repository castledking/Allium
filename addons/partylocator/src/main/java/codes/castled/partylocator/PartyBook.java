package codes.castled.partylocator;

import java.util.*;

/**
 * Party membership and invites, without Bukkit: the rules legacy Allium's PartyManager enforced.
 * A player is in at most one party; only the leader invites and disbands; the leader cannot
 * leave (they disband instead); an invite is needed to join, and it lasts until it is used, the
 * party is disbanded or the inviter logs out.
 */
final class PartyBook {
    enum Invite { SUCCESS, NO_PARTY, NOT_LEADER, TARGET_IN_PARTY, ALREADY_INVITED, SELF_INVITE }
    enum Join { SUCCESS, NOT_FOUND, ALREADY_IN_PARTY, NOT_INVITED }

    private final Map<String, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, Party> byMember = new HashMap<>();
    /** Invited player -> party name -> who invited them. */
    private final Map<UUID, Map<String, UUID>> invites = new HashMap<>();

    /** The party name legacy gave a new party: the leader's name, lowercase. */
    static String nameFor(String leaderName) {
        return (leaderName + "'s party").toLowerCase(Locale.ROOT);
    }

    Party partyOf(UUID player) {
        return byMember.get(player);
    }

    Party party(String name) {
        return name == null ? null : parties.get(name.toLowerCase(Locale.ROOT));
    }

    boolean sameParty(UUID first, UUID second) {
        Party party = byMember.get(first);
        return party != null && party == byMember.get(second);
    }

    Collection<Party> parties() {
        return Collections.unmodifiableCollection(parties.values());
    }

    /** Creates a party led by {@code leader}, or returns null when the name is taken or they are in one. */
    Party create(String name, UUID leader) {
        String key = name.toLowerCase(Locale.ROOT);
        if (parties.containsKey(key) || byMember.containsKey(leader)) return null;
        Party party = new Party(key, leader);
        parties.put(key, party);
        byMember.put(leader, party);
        invites.remove(leader);
        return party;
    }

    Invite invite(UUID inviter, UUID target) {
        if (inviter.equals(target)) return Invite.SELF_INVITE;
        Party party = byMember.get(inviter);
        if (party == null) return Invite.NO_PARTY;
        if (!party.leader().equals(inviter)) return Invite.NOT_LEADER;
        if (byMember.containsKey(target)) return Invite.TARGET_IN_PARTY;
        Map<String, UUID> pending = invites.computeIfAbsent(target, key -> new LinkedHashMap<>());
        if (pending.containsKey(party.name())) return Invite.ALREADY_INVITED;
        pending.put(party.name(), inviter);
        return Invite.SUCCESS;
    }

    Join join(String partyName, UUID player) {
        Party party = party(partyName);
        if (party == null) return Join.NOT_FOUND;
        if (byMember.containsKey(player)) return Join.ALREADY_IN_PARTY;
        Map<String, UUID> pending = invites.get(player);
        if (pending == null || !pending.containsKey(party.name())) return Join.NOT_INVITED;
        party.add(player);
        byMember.put(player, party);
        invites.remove(player);
        return Join.SUCCESS;
    }

    /** Takes {@code player} out of their party. False when they are in none, or lead it. */
    boolean leave(UUID player) {
        Party party = byMember.get(player);
        if (party == null || party.leader().equals(player)) return false;
        party.remove(player);
        byMember.remove(player);
        return true;
    }

    /** Disbands the party {@code leader} leads, returning it, or null when they lead none. */
    Party disband(UUID leader) {
        Party party = byMember.get(leader);
        if (party == null || !party.leader().equals(leader)) return null;
        parties.remove(party.name());
        for (UUID member : party.members()) byMember.remove(member);
        for (Map<String, UUID> pending : invites.values()) pending.remove(party.name());
        invites.values().removeIf(Map::isEmpty);
        return party;
    }

    /** Party names {@code player} is invited to, oldest first. */
    Set<String> invitesFor(UUID player) {
        Map<String, UUID> pending = invites.get(player);
        return pending == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(pending.keySet()));
    }

    /** Withdraws every invite {@code inviter} sent, as legacy did when they logged out. */
    void dropInvitesFrom(UUID inviter) {
        for (Map<String, UUID> pending : invites.values()) pending.values().removeIf(inviter::equals);
        invites.values().removeIf(Map::isEmpty);
    }

    /** Replaces every party with {@code loaded}, skipping a member already placed in an earlier one. */
    void load(Collection<Party> loaded) {
        parties.clear();
        byMember.clear();
        invites.clear();
        for (Party source : loaded) {
            if (parties.containsKey(source.name()) || byMember.containsKey(source.leader())) continue;
            Party party = new Party(source.name(), source.leader());
            parties.put(party.name(), party);
            byMember.put(party.leader(), party);
            for (UUID member : source.members()) {
                if (byMember.containsKey(member)) continue;
                party.add(member);
                byMember.put(member, party);
            }
        }
    }
}
