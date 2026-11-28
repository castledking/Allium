package codes.castled.allium.neoforge.bootstrap;

import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod("allium")
public final class NeoForgeBootstrap {
    public NeoForgeBootstrap() {
        ModList mods = ModList.get();
        String game = mods.getModContainerById("minecraft").orElseThrow().getModInfo().getVersion().toString();
        String version = mods.getModContainerById("allium").orElseThrow().getModInfo().getVersion().toString();
        String entrypoint = isLegacyNeoForge(game)
                ? "codes.castled.allium.neoforge.v1202.LegacyNeoForge"
                : "codes.castled.allium.neoforge.AlliumNeoForge";
        try {
            Class.forName(entrypoint).asSubclass(NeoForgeAdapter.class).getConstructor().newInstance().initialize(version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not initialize Allium's NeoForge adapter for Minecraft " + game, e);
        }
    }

    private static boolean isLegacyNeoForge(String game) {
        return "1.20.2".equals(game) || "1.20.3".equals(game) || "1.20.4".equals(game);
    }
}
