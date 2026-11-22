package codes.castled.partylocator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PartyStoreTest {
    @TempDir Path dir;

    @Test
    void partiesSurviveARestart() throws Exception {
        UUID leader = UUID.randomUUID(), member = UUID.randomUUID();
        PartyBook book = new PartyBook();
        book.create("ggpots's party", leader);
        book.invite(leader, member);
        book.join("ggpots's party", member);

        PartyStore store = new PartyStore(dir.resolve("parties.yml"));
        store.write(PartyStore.snapshot(book.parties()));
        assertFalse(Files.exists(dir.resolve("parties.yml.tmp")), "the temporary file is moved into place");

        PartyBook restored = new PartyBook();
        restored.load(store.load());
        assertEquals(List.of(leader, member), List.copyOf(restored.party("ggpots's party").members()));
        assertEquals(leader, restored.partyOf(member).leader());
    }

    @Test
    void noFileIsNoPartiesAndABadRowIsSkipped() throws Exception {
        PartyStore store = new PartyStore(dir.resolve("parties.yml"));
        assertEquals(List.of(), store.load());
        UUID leader = UUID.randomUUID();
        Files.writeString(dir.resolve("parties.yml"), "parties:\n- name: good\n  leader: " + leader
                + "\n  members: ['" + leader + "']\n- name: bad\n  leader: not-a-uuid\n  members: []\n");
        List<Party> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).name());
    }
}
