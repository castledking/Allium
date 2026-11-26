package codes.castled.allium.fabric.bootstrap;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

public final class FabricBootstrap implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        String game = loader.getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString();
        CustomValue adapter = loader.getModContainer("allium").get().getMetadata()
                .getCustomValue("allium:adapters").getAsObject().get(game);
        if (adapter == null) {
            throw new IllegalStateException("Allium has no Fabric adapter for Minecraft " + game);
        }
        CustomValue.CvArray definition = adapter.getAsArray();
        String entrypoint = definition.get(0).getAsString();
        String commandApi = definition.get(1).getAsString();
        if (!loader.isModLoaded(commandApi)) {
            throw new IllegalStateException("Allium's Minecraft " + game + " adapter requires " + commandApi
                    + ". Install Fabric API for this Minecraft version.");
        }
        try {
            Class.forName(entrypoint).asSubclass(DedicatedServerModInitializer.class)
                    .getConstructor().newInstance().onInitializeServer();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not initialize Allium's Fabric adapter for Minecraft " + game, e);
        }
    }
}
