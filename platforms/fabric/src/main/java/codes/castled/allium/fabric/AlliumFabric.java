package codes.castled.allium.fabric;

import codes.castled.allium.minecraft.MinecraftCommands;
import codes.castled.allium.minecraft.MinecraftNativePlatform;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class AlliumFabric implements DedicatedServerModInitializer {
    @Override public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        var mod = loader.getModContainer("allium").orElseThrow();
        String version = mod.getMetadata().getVersion().getFriendlyString();
        Path dataDir = loader.getGameDir().resolve("plugins").resolve("Allium");
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                MinecraftCommands.register(dispatcher, "Fabric", version, dataDir));
        try {
            ChatHook.register();
        } catch (LinkageError ignored) {
        }
        MinecraftNativePlatform.tryHookFabricConnectionEvents();
    }

    private static final class ChatHook {
        static void register() {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                    !MinecraftNativePlatform.handleChat(sender, message.signedContent()));
        }
    }
}
