package net.survivalfun.core.managers.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a party with members and a leader.
 */
public class Party {
    private final String name;
    private final UUID leader;
    private final List<UUID> members;

    public Party(String name, UUID leader) {
        this.name = name;
        this.leader = leader;
        this.members = new ArrayList<>();
        this.members.add(leader);
    }

    public String getName() {
        return name;
    }

    public UUID getLeader() {
        return leader;
    }

    public List<UUID> getMembers() {
        return new ArrayList<>(members);
    }

    public boolean addMember(UUID player) {
        if (!members.contains(player)) {
            members.add(player);
            return true;
        }
        return false;
    }

    public boolean removeMember(UUID player) {
        return members.remove(player);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    public int getSize() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }
}
