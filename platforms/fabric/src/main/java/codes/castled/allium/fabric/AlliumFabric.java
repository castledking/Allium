package codes.castled.allium.fabric;

import codes.castled.allium.minecraft.MinecraftCommands;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

public final class AlliumFabric implements DedicatedServerModInitializer {
    @Override public void onInitializeServer() {
        var mod = FabricLoader.getInstance().getModContainer("allium").orElseThrow();
        String version = mod.getMetadata().getVersion().getFriendlyString();
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                MinecraftCommands.register(dispatcher, "Fabric", version));
    }
}
