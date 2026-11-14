package codes.castled.allium.kit;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.library.TextTokens;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.profile.PlayerProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Captures player inventory {@link ItemStack}s into canonical {@code ItemSpecParser} strings for
 * {@code /createkit}.
 *
 * <p>Uses {@link ItemService#identify(ItemStack)} first so provider items ({@code nexo:<id>},
 * {@code craftengine:<ns>:<id>}, {@code oraxen:<id>}) and Allium items ({@code allium:<id>}) keep
 * their canonical provider/Allium reference instead of degrading to their vanilla carrier material.
 */
public final class KitItemCapture {

    private KitItemCapture() {
    }

    public static List<String> captureInventory(ItemService items, ItemStack[] storageContents) {
        Objects.requireNonNull(items, "items");
        List<String> specs = new ArrayList<>();
        if (storageContents == null) {
            return specs;
        }
        for (ItemStack stack : storageContents) {
            if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir() || stack.getAmount() < 1) {
                continue;
            }
            specs.add(captureStack(items, stack));
        }
        return List.copyOf(specs);
    }

    public static String captureStack(ItemService items, ItemStack stack) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(stack, "stack");
        ItemRef identified = items.identify(stack).orElseGet(
                () -> ItemRef.parse("minecraft:" + stack.getType().name().toLowerCase(Locale.ROOT)));
        String reference = identified.appearance().asString();
        StringBuilder out = new StringBuilder(reference).append(' ').append(stack.getAmount());

        // Provider and Allium items carry their own authored display name, lore and enchantments
        // from their definition; only append extra tokens for vanilla items (or enchantments added on top).
        boolean vanilla = identified.appearance().isVanilla() && identified.behaviorId().isEmpty();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return out.toString();
        }

        Map<Enchantment, Integer> enchants = meta instanceof EnchantmentStorageMeta book
                ? book.getStoredEnchants()
                : meta.getEnchants();
        enchants.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getKey().getKey()))
                .forEach(e -> out.append(' ')
                        .append(e.getKey().getKey().getKey())
                        .append(':')
                        .append(e.getValue()));

        if (meta.isUnbreakable()) {
            out.append(" unbreakable");
        }

        if (vanilla) {
            if (meta.hasDisplayName()) {
                out.append(" name:").append(quote(meta.getDisplayName().replace('\u00a7', '&')));
            }
            if (meta.hasLore() && meta.getLore() != null && !meta.getLore().isEmpty()) {
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < Math.min(TextTokens.MAX_LORE_LINES, meta.getLore().size()); i++) {
                    lines.add(quote(meta.getLore().get(i).replace('\u00a7', '&')));
                }
                out.append(" lore:").append(String.join("|", lines));
            }
            if (meta instanceof SkullMeta skull && stack.getType() == Material.PLAYER_HEAD) {
                PlayerProfile profile = skull.getOwnerProfile();
                if (profile != null && profile.getName() != null && profile.getName().matches("[A-Za-z0-9_]{1,16}")) {
                    out.append(" owner:").append(profile.getName());
                }
            }
            if (meta instanceof LeatherArmorMeta leather) {
                Color color = leather.getColor();
                if (color != null) {
                    out.append(String.format(Locale.ROOT, " color:#%02x%02x%02x",
                            color.getRed(), color.getGreen(), color.getBlue()));
                }
            }
            if (meta instanceof PotionMeta potion) {
                if (potion.hasColor() && potion.getColor() != null) {
                    Color c = potion.getColor();
                    out.append(String.format(Locale.ROOT, " color:#%02x%02x%02x",
                            c.getRed(), c.getGreen(), c.getBlue()));
                }
                if (potion.hasCustomEffects() && !potion.getCustomEffects().isEmpty()) {
                    PotionEffect effect = potion.getCustomEffects().get(0);
                    String key = effect.getType().getKey().getKey();
                    int level = Math.max(1, Math.min(255, effect.getAmplifier() + 1));
                    int seconds = Math.max(1, Math.min(86_400, effect.getDuration() / 20));
                    out.append(" effect:").append(key).append(':').append(level).append(':').append(seconds);
                }
            }
        }
        return out.toString();
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
