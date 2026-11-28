package codes.castled.allium.brigadier;

import codes.castled.allium.portable.Homes;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * Version-independent server adapter implemented by Fabric (1.14, 1.20.1, 1.21.11, 26.x)
 * and NeoForge (1.20.2, 26.x) so all Brigadier commands share one Java 8 implementation.
 */
public interface NativePlatform<S, P> {
    Path dataDirectory(S source);

    boolean isGamemaster(S source);

    P playerOrNull(S source);

    UUID id(P player);

    String name(P player);

    Collection<P> onlinePlayers(S source);

    Homes.Position positionOf(P player);

    Homes.Position defaultSpawn(S source);

    boolean teleport(S source, P player, Homes.Position destination);

    void reply(S source, String message);

    void send(P player, String message);

    void broadcast(S source, String message);

    void heal(P player);

    void feed(P player);

    boolean toggleFly(P player);

    boolean setGameMode(P player, String mode);

    int seaLevel(P player);

    boolean setTime(S source, long ticks);

    boolean setWeather(S source, String mode);

    void clearInventory(P player);
}
