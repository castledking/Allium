package codes.castled.allium.fabric.bootstrap;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

public final class FabricBootstrap implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        String game = loader.getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString();
        String entrypoint;
        String commandApi;

        CustomValue adapter = loader.getModContainer("allium").get().getMetadata()
                .getCustomValue("allium:adapters").getAsObject().get(game);
        if (adapter != null) {
            CustomValue.CvArray definition = adapter.getAsArray();
            entrypoint = definition.get(0).getAsString();
            commandApi = definition.get(1).getAsString();
        } else {
            String[] fallback = resolveByVersionRange(game);
            if (fallback == null) {
                throw new IllegalStateException("Allium has no Fabric adapter for Minecraft " + game);
            }
            entrypoint = fallback[0];
            commandApi = fallback[1];
        }

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

    private static String[] resolveByVersionRange(String game) {
        int[] ver = parseVersion(game);
        if (ver == null) return null;
        int major = ver[0];
        int minor = ver[1];
        int patch = ver[2];
        if (major >= 26) {
            return new String[]{"codes.castled.allium.fabric.AlliumFabric", "fabric-command-api-v2"};
        }
        if (major == 1 && minor == 21 && patch >= 11) {
            return new String[]{"codes.castled.allium.fabric.v12111.FabricAdapter", "fabric-command-api-v2"};
        }
        if (major == 1 && minor >= 19) {
            return new String[]{"codes.castled.allium.fabric.v1201.FabricAdapter", "fabric-command-api-v2"};
        }
        if (major == 1 && minor >= 14) {
            return new String[]{"codes.castled.allium.fabric.v114.LegacyFabric", "fabric-commands-v0"};
        }
        return null;
    }

    private static int[] parseVersion(String raw) {
        if (raw == null) return null;
        String base = raw.split("[-+]")[0];
        String[] parts = base.split("\\.");
        if (parts.length < 2) return null;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
