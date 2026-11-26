package codes.castled.allium.neoforge;

import codes.castled.allium.minecraft.MinecraftCommands;
import codes.castled.allium.neoforge.bootstrap.NeoForgeAdapter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class AlliumNeoForge implements NeoForgeAdapter {
    @Override
    public void initialize(String version) {
        if (FMLEnvironment.getDist() != Dist.DEDICATED_SERVER) {
            return;
        }
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                MinecraftCommands.register(event.getDispatcher(), "NeoForge", version));
    }
}
