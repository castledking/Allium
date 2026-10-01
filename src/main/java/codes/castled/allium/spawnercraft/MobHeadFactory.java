package codes.castled.allium.spawnercraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Builds the player-head item Allium drops for a mob, as configured in
 * {@code spawner_heads.yml}. Shared by the drop listener and /spawnerhead so
 * a given head is identical however it was obtained.
 */
public final class MobHeadFactory {

    private MobHeadFactory() {}

    public static ItemStack create(Plugin plugin, EntityType entityType, SpawnerHeadConfig.MobHead headData) {
        if (headData == null) {
            return null;
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) {
            return null;
        }
        String hexColor = SpawnerCoreManager.getMobColor(entityType);
        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Head");
        List<String> lore = new ArrayList<>();
        lore.add("§7Used to craft " + entityName + " Spawners");
        meta.setLore(lore);
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(profileId(entityType));
            PlayerTextures textures = profile.getTextures();
            String textureUrl = decodeTextureUrl(plugin, headData.texture());
            if (textureUrl != null) {
                textures.setSkin(new URL(textureUrl));
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (MalformedURLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set mob head texture for " + entityName + " Head", e);
        }
        setNoteBlockSound(plugin, meta, headData.sound());
        head.setItemMeta(meta);
        return head;
    }

    /**
     * Profile id for a mob's head, derived from the mob type so it is the same every
     * time. A random id per head would make every head a distinct item, and heads of
     * the same mob would refuse to stack with each other.
     */
    private static UUID profileId(EntityType entityType) {
        return UUID.nameUUIDFromBytes(
                ("allium:mobhead:" + entityType.name()).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeTextureUrl(Plugin plugin, String base64Texture) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64Texture));
            int urlStart = decoded.indexOf("\"url\":\"") + 7;
            int urlEnd = decoded.indexOf("\"", urlStart);
            if (urlStart > 6 && urlEnd > urlStart) {
                return decoded.substring(urlStart, urlEnd);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to decode texture", e);
        }
        return null;
    }

    // The note_block_sound component is how Allium recognises its own heads later.
    private static void setNoteBlockSound(Plugin plugin, SkullMeta meta, String sound) {
        NamespacedKey soundKey = NamespacedKey.minecraft(sound);
        try {
            Method method = SkullMeta.class.getMethod("setNoteBlockSound", NamespacedKey.class);
            method.setAccessible(true);
            method.invoke(meta, soundKey);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set note_block_sound via SkullMeta", e);
        }
        try {
            Method method = meta.getClass().getMethod("setNoteBlockSound", NamespacedKey.class);
            method.setAccessible(true);
            method.invoke(meta, soundKey);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set note_block_sound", e);
        }
    }
}
