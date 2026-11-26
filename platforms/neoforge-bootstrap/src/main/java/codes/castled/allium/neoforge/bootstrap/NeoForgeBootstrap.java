package codes.castled.allium.neoforge.bootstrap;

import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod("allium")
public final class NeoForgeBootstrap {
    public NeoForgeBootstrap() {
        ModList mods = ModList.get();
        String game = mods.getModContainerById("minecraft").orElseThrow().getModInfo().getVersion().toString();
        String version = mods.getModContainerById("allium").orElseThrow().getModInfo().getVersion().toString();
        String entrypoint = game.equals("1.20.2")
                ? "codes.castled.allium.neoforge.v1202.LegacyNeoForge"
                : "codes.castled.allium.neoforge.AlliumNeoForge";
        try {
            Class.forName(entrypoint).asSubclass(NeoForgeAdapter.class).getConstructor().newInstance().initialize(version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not initialize Allium's NeoForge adapter for Minecraft " + game, e);
        }
    }
}
