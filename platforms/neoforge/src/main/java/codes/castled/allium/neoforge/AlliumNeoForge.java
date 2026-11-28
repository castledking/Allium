package codes.castled.allium.neoforge;

import codes.castled.allium.minecraft.MinecraftCommands;
import codes.castled.allium.minecraft.MinecraftNativePlatform;
import codes.castled.allium.neoforge.bootstrap.NeoForgeAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.nio.file.Path;

public final class AlliumNeoForge implements NeoForgeAdapter {
    @Override
    public void initialize(String version) {
        if (FMLEnvironment.getDist() != Dist.DEDICATED_SERVER) {
            return;
        }
        Path dataDir = FMLPaths.GAMEDIR.get().resolve("plugins").resolve("Allium");
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                MinecraftCommands.register(event.getDispatcher(), "NeoForge", version, dataDir));
        NeoForge.EVENT_BUS.addListener((ServerChatEvent event) -> {
            if (MinecraftNativePlatform.handleChat(event.getPlayer(), event.getRawText())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer) {
                MinecraftNativePlatform.handleJoin((ServerPlayer) event.getEntity());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer) {
                MinecraftNativePlatform.handleQuit((ServerPlayer) event.getEntity());
            }
        });
    }
}
