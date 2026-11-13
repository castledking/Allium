package codes.castled.allium.mail;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class PlayerDataFilesTest {
    public static class Storage {
        private final File dir;
        public Storage(File dir) { this.dir=dir; }
        public File getPlayerDir() { return dir; }
    }
    public static class Parent { private final Storage storage; Parent(File dir) { storage=new Storage(dir); } }
    public static class Players extends Parent { Players(File dir) { super(dir); } }
    public static class Server { private final Players players; Server(File dir) { players=new Players(dir); } public Players getHandle() { return players; } }
    public static class AmbiguousPlayers { private final Storage one=new Storage(new File("one")),two=new Storage(new File("two")); }
    public static class AmbiguousServer { public AmbiguousPlayers getHandle() { return new AmbiguousPlayers(); } }
    @Test void usesNativePrimaryDirectoryIncludingInheritedStorageInsteadOfDimensionNames() {
        for(String dir : new String[]{"world/playerdata","world/players/data","custom-root/players/data"}) {
            assertEquals(Path.of(dir).toAbsolutePath().normalize(),PlayerDataFiles.guardedDirectory(new Server(new File(dir))));
        }
    }
    @Test void ambiguityAndUnsupportedServersFailClosed() {
        assertThrows(IllegalStateException.class,()->PlayerDataFiles.guardedDirectory(new Object()));
        assertThrows(IllegalStateException.class,()->PlayerDataFiles.guardedDirectory(new AmbiguousServer()));
    }
}
