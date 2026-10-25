package codes.castled.allium.tradingcards.morph;

import org.bukkit.Server;

/** Checks the console commands used for morphs, without loading API classes. */
public final class DisguiseBridgeLookup {

    private DisguiseBridgeLookup() {
    }

    public static boolean commandsAvailable(Server server) {
        return server.getPluginManager().isPluginEnabled("LibsDisguises")
            && server.getPluginCommand("disguiseplayer") != null
            && server.getPluginCommand("undisguiseplayer") != null;
    }
}
