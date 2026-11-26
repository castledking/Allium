package codes.castled.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MenuIcons {
    private MenuIcons() {}

    static ItemStack resolve(MenusPlugin plugin, org.bukkit.entity.Player player, String reference, Material fallback) {
        String ref = MenuVariables.expand(player, reference);
        Material material = Material.matchMaterial(ref);
        if (material != null && material.isItem() && !material.isAir()) return new ItemStack(material);
        if (ref.matches("head:[0-9a-f]{32,64}")) return texture(ref.substring(5));
        if (!ref.isBlank()) {
            var result = plugin.core().items().create(ref, 1).stack().orElse(null);
            if (result != null) return result;
        }
        return new ItemStack(fallback);
    }

    static ItemStack texture(String hash) {
        var stack = new ItemStack(Material.PLAYER_HEAD);
        if (!(stack.getItemMeta() instanceof SkullMeta meta) || hash.isEmpty()) return stack;
        try {
            var profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)));
            var textures = profile.getTextures();
            textures.setSkin(URI.create("https://textures.minecraft.net/texture/" + hash).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            stack.setItemMeta(meta);
        } catch (IllegalArgumentException | MalformedURLException ignored) {
            // A plain head still represents the entry.
        }
        return stack;
    }

    static ItemStack lore(ItemStack stack, java.util.List<String> lines) {
        var meta = stack.getItemMeta();
        if (meta != null && !lines.isEmpty()) {
            MenuItemText.lore(meta, lines, false);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
