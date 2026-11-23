package codes.castled.partylocator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A party: a unique name, its leader and its members, the leader included. */
final class Party {
    private final String name;
    private final UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();

    Party(String name, UUID leader) {
        this.name = name;
        this.leader = leader;
        members.add(leader);
    }

    String name() { return name; }
    UUID leader() { return leader; }
    synchronized Set<UUID> members() { return Collections.unmodifiableSet(new LinkedHashSet<>(members)); }
    synchronized boolean has(UUID player) { return members.contains(player); }
    synchronized int size() { return members.size(); }

    synchronized void add(UUID player) { members.add(player); }
    synchronized void remove(UUID player) { if (!player.equals(leader)) members.remove(player); }
}
