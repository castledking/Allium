package codes.castled.partylocator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PartyBookTest {
    final UUID leader = UUID.randomUUID(), friend = UUID.randomUUID(), stranger = UUID.randomUUID();

    @Test
    void partiesAreNamedForTheirLeaderAndOnePerPlayer() {
        PartyBook book = new PartyBook();
        assertEquals("ggpots's party", PartyBook.nameFor("GGPots"));
        Party party = book.create(PartyBook.nameFor("ggpots"), leader);
        assertNotNull(party);
        assertEquals(Set.of(leader), party.members());
        assertNull(book.create("another", leader), "a leader cannot start a second party");
        assertNull(book.create("GGPOTS's party", stranger), "names are unique ignoring case");
    }

    @Test
    void onlyTheLeaderInvitesAndAnInviteIsNeededToJoin() {
        PartyBook book = new PartyBook();
        Party party = book.create("p", leader);
        assertEquals(PartyBook.Join.NOT_INVITED, book.join("p", friend));
        assertEquals(PartyBook.Invite.SELF_INVITE, book.invite(leader, leader));
        assertEquals(PartyBook.Invite.NO_PARTY, book.invite(stranger, friend));
        assertEquals(PartyBook.Invite.SUCCESS, book.invite(leader, friend));
        assertEquals(PartyBook.Invite.ALREADY_INVITED, book.invite(leader, friend));
        assertEquals(Set.of("p"), book.invitesFor(friend));
        assertEquals(PartyBook.Join.SUCCESS, book.join("p", friend));
        assertTrue(book.sameParty(leader, friend));
        assertEquals(Set.of(), book.invitesFor(friend), "joining uses up the invite");
        assertEquals(PartyBook.Invite.NOT_LEADER, book.invite(friend, stranger));
        assertEquals(PartyBook.Invite.TARGET_IN_PARTY, book.invite(leader, friend));
        assertEquals(PartyBook.Join.ALREADY_IN_PARTY, book.join("p", friend));
        assertEquals(PartyBook.Join.NOT_FOUND, book.join("nope", stranger));
        assertEquals(2, party.size());
    }

    @Test
    void leadersDisbandRatherThanLeaveAndDisbandingClearsInvites() {
        PartyBook book = new PartyBook();
        book.create("p", leader);
        book.invite(leader, friend);
        book.join("p", friend);
        book.invite(leader, stranger);
        assertFalse(book.leave(leader), "the leader disbands instead");
        assertTrue(book.leave(friend));
        assertNull(book.partyOf(friend));
        assertNull(book.disband(friend), "only a leader disbands");
        Party disbanded = book.disband(leader);
        assertEquals("p", disbanded.name());
        assertNull(book.partyOf(leader));
        assertTrue(book.parties().isEmpty());
        assertEquals(Set.of(), book.invitesFor(stranger), "invites to a disbanded party lapse");
    }

    @Test
    void invitesLapseWhenTheInviterLogsOut() {
        PartyBook book = new PartyBook();
        book.create("p", leader);
        book.invite(leader, friend);
        book.dropInvitesFrom(leader);
        assertEquals(PartyBook.Join.NOT_INVITED, book.join("p", friend));
        assertNotNull(book.partyOf(leader), "the party itself stays");
    }

    @Test
    void loadingKeepsEachPlayerInOneParty() {
        Party first = new Party("a", leader);
        first.add(friend);
        Party second = new Party("b", stranger);
        second.add(friend);
        PartyBook book = new PartyBook();
        book.load(List.of(first, second));
        assertEquals("a", book.partyOf(friend).name());
        assertEquals(Set.of(stranger), book.party("b").members());
    }
}
