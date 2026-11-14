package codes.castled.allium.kit;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.library.TextTokens;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.profile.PlayerProfile;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Losslessly captures player inventory {@link ItemStack}s into canonical {@code ItemSpecParser}
 * strings for {@code /createkit}.
 *
 * <p>Uses {@link ItemService#identify(ItemStack)} first so provider items ({@code nexo:<id>},
 * {@code craftengine:<ns>:<id>}, {@code oraxen:<id>}) and Allium items ({@code allium:<id>}) keep
 * their canonical provider/Allium reference instead of degrading to their vanilla carrier material.
 *
 * <p>Enforces two strict rules:
 * <ol>
 *   <li>Capturing a stack with a privileged behavior requires {@code allium.give.privileged}.</li>
 *   <li>If any stack carries metadata that the human item grammar cannot represent losslessly
 *       (shulker/container contents, bundle contents, attribute modifiers, custom model data on
 *       vanilla items, written books, durability damage, custom PDC, etc.), capture is refused
 *       with the slot index and the unsupported data so a degraded kit is never saved.</li>
 * </ol>
 */
@SuppressWarnings("deprecation")
public final class KitItemCapture {

    public sealed interface CaptureOutcome
            permits CaptureOutcome.Captured, CaptureOutcome.PrivilegedDenied, CaptureOutcome.Unsupported {
        record Captured(List<String> specs) implements CaptureOutcome {
            public Captured {
                specs = List.copyOf(specs);
            }
        }

        record PrivilegedDenied(int slot, String item, String behavior) implements CaptureOutcome {
        }

        record Unsupported(int slot, String reason) implements CaptureOutcome {
        }
    }

    private KitItemCapture() {
    }

    public static CaptureOutcome captureInventory(
            ItemService items,
            ItemStack[] storageContents,
            boolean canCapturePrivileged
    ) {
        Objects.requireNonNull(items, "items");
        List<String> specs = new ArrayList<>();
        if (storageContents == null) {
            return new CaptureOutcome.Captured(specs);
        }
        for (int slot = 0; slot < storageContents.length; slot++) {
            ItemStack stack = storageContents[slot];
            if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir() || stack.getAmount() < 1) {
                continue;
            }
            CaptureOutcome single = captureSlot(items, slot, stack, canCapturePrivileged);
            if (!(single instanceof CaptureOutcome.Captured captured)) {
                return single;
            }
            specs.addAll(captured.specs());
        }
        return new CaptureOutcome.Captured(specs);
    }

    public static CaptureOutcome captureSlot(
            ItemService items,
            int slot,
            ItemStack stack,
            boolean canCapturePrivileged
    ) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(stack, "stack");

        ItemRef identified = items.identify(stack).orElse(null);
        if (identified == null) {
            return new CaptureOutcome.Unsupported(slot, "ambiguous or unrecognized item identity");
        }

        String behavior = identified.behaviorId().orElse(null);
        if (behavior != null && items.isPrivilegedBehavior(behavior) && !canCapturePrivileged) {
            return new CaptureOutcome.PrivilegedDenied(slot, identified.appearance().asString(), behavior);
        }
        if (behavior != null && items.isSignedBehavior(behavior) && stack.getAmount() > ItemLibrary.MAX_SIGNED_STACKS) {
            return new CaptureOutcome.Unsupported(slot,
                    "signed item stack size exceeds " + ItemLibrary.MAX_SIGNED_STACKS);
        }

        boolean vanilla = identified.appearance().isVanilla() && behavior == null;
        ItemMeta meta = stack.getItemMeta();

        if (meta != null) {
            String unsupported = checkUnsupportedMeta(items, stack, meta, identified, vanilla);
            if (unsupported != null) {
                return new CaptureOutcome.Unsupported(slot, unsupported);
            }
        }

        String reference = identified.appearance().asString();
        StringBuilder out = new StringBuilder(reference).append(' ').append(stack.getAmount());
        if (meta == null) {
            return new CaptureOutcome.Captured(List.of(out.toString()));
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
                for (String line : meta.getLore()) {
                    lines.add(quote(line.replace('\u00a7', '&')));
                }
                out.append(" lore:").append(String.join("|", lines));
            }
            if (meta instanceof SkullMeta skull && stack.getType() == Material.PLAYER_HEAD) {
                PlayerProfile profile = skull.getOwnerProfile();
                if (profile != null && profile.getName() != null) {
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
                    int level = effect.getAmplifier() + 1;
                    int seconds = effect.getDuration() / 20;
                    out.append(" effect:").append(key).append(':').append(level).append(':').append(seconds);
                }
            }
        }
        return new CaptureOutcome.Captured(List.of(out.toString()));
    }

    @Nullable
    private static String checkUnsupportedMeta(
            ItemService items,
            ItemStack stack,
            ItemMeta meta,
            ItemRef identified,
            boolean vanilla
    ) {
        // 1. Shulker box / container / block state contents
        if (meta instanceof BlockStateMeta bsm) {
            if (bsm.hasBlockState()) {
                BlockState state = bsm.getBlockState();
                if (state instanceof Container container) {
                    ItemStack[] contents = container.getInventory().getContents();
                    if (contents != null) {
                        for (ItemStack item : contents) {
                            if (item != null && item.getType() != Material.AIR && !item.getType().isAir()) {
                                return "shulker/container contents";
                            }
                        }
                    }
                }
                return "block state data";
            }
        }

        // 2. Bundle contents
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            return "bundle contents";
        }

        // 3. Written / writable books
        if (meta instanceof BookMeta book) {
            if (stack.getType() == Material.WRITTEN_BOOK
                    || stack.getType() == Material.WRITABLE_BOOK
                    || book.hasTitle()
                    || book.hasAuthor()
                    || book.hasPages()
                    || book.hasGeneration()) {
                return "written book data (title, author or pages)";
            }
        }

        // 4. Non-vanilla (provider / Allium) items: verify they have not been modified away from their fresh definition
        if (!vanilla) {
            ItemRef templateRef = items.refForKey(identified.appearance());
            if (!identified.stateData().equals(templateRef.stateData())) {
                return "non-default custom item state";
            }
            ResolutionResult fresh = items.create(templateRef);
            if (!fresh.isResolved() || fresh.itemStack().isEmpty()) {
                return "unresolved custom item template";
            }
            ItemMeta templateMeta = fresh.itemStack().get().getItemMeta();
            if (templateMeta != null) {
                if (meta.hasDisplayName() != templateMeta.hasDisplayName()
                        || (meta.hasDisplayName() && !Objects.equals(meta.getDisplayName(), templateMeta.getDisplayName()))) {
                    return "modified custom item display name";
                }
                if (meta.hasLore() != templateMeta.hasLore()
                        || (meta.hasLore() && !Objects.equals(meta.getLore(), templateMeta.getLore()))) {
                    return "modified custom item lore";
                }
                if (meta.hasCustomModelData() != templateMeta.hasCustomModelData()
                        || (meta.hasCustomModelData() && meta.getCustomModelData() != templateMeta.getCustomModelData())) {
                    return "modified custom model data";
                }
                if (meta.hasAttributeModifiers() != templateMeta.hasAttributeModifiers()
                        || (meta.hasAttributeModifiers() && !Objects.equals(meta.getAttributeModifiers(), templateMeta.getAttributeModifiers()))) {
                    return "attribute modifiers";
                }
                if (!Objects.equals(meta.getItemFlags(), templateMeta.getItemFlags())) {
                    return "item flags";
                }
            }
            if (meta instanceof Damageable dmg && dmg.hasDamage() && dmg.getDamage() > 0) {
                return "item durability damage";
            }
            return null;
        }

        // 5. Vanilla items: check all metadata outside the human token grammar
        if (meta.hasAttributeModifiers() && meta.getAttributeModifiers() != null && !meta.getAttributeModifiers().isEmpty()) {
            return "attribute modifiers";
        }
        if (meta.hasCustomModelData()) {
            return "custom model data";
        }
        if (meta instanceof Damageable dmg && dmg.hasDamage() && dmg.getDamage() > 0) {
            return "item durability damage";
        }
        if (!meta.getItemFlags().isEmpty()) {
            return "item flags";
        }
        if (meta instanceof Repairable repairable && repairable.hasRepairCost() && repairable.getRepairCost() > 0) {
            return "anvil repair cost";
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc != null && !pdc.getKeys().isEmpty()) {
            return "custom persistent data (PDC)";
        }
        if (meta.hasLore() && meta.getLore() != null && meta.getLore().size() > TextTokens.MAX_LORE_LINES) {
            return "lore exceeding " + TextTokens.MAX_LORE_LINES + " lines";
        }
        if (meta instanceof SkullMeta skull) {
            if (skull.hasOwner()) {
                if (stack.getType() != Material.PLAYER_HEAD) {
                    return "skull owner on non-player_head";
                }
                PlayerProfile profile = skull.getOwnerProfile();
                if (profile == null || profile.getName() == null || !profile.getName().matches("[A-Za-z0-9_]{1,16}")) {
                    return "skull profile without a valid player username";
                }
            }
        }
        if (meta instanceof PotionMeta potion) {
            if (potion.hasCustomEffects()) {
                List<PotionEffect> effects = potion.getCustomEffects();
                if (effects.size() > 1) {
                    return "multiple custom potion effects";
                }
                PotionEffect effect = effects.get(0);
                int duration = effect.getDuration();
                int level = effect.getAmplifier() + 1;
                if (duration < 20 || duration % 20 != 0 || duration / 20 > 86_400 || level < 1 || level > 255
                        || effect.isAmbient() || !effect.hasParticles() || !effect.hasIcon()) {
                    return "non-standard potion effect parameters";
                }
            }
        }
        if (meta instanceof BannerMeta banner && !banner.getPatterns().isEmpty()) {
            return "banner patterns";
        }
        if (meta instanceof FireworkMeta firework && (firework.hasEffects() || firework.getPower() > 0)) {
            return "firework data";
        }
        if (meta instanceof FireworkEffectMeta effectMeta && effectMeta.hasEffect()) {
            return "firework star effect";
        }
        if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
            return "armor trim";
        }
        if (meta instanceof CompassMeta compass && (compass.hasLodestone() || compass.isLodestoneTracked())) {
            return "lodestone compass data";
        }
        if (meta instanceof MapMeta map && (map.hasMapView() || map.hasLocationName() || map.hasColor() || map.isScaling())) {
            return "map data";
        }
        if (meta instanceof SuspiciousStewMeta stew && stew.hasCustomEffects()) {
            return "suspicious stew effects";
        }
        if (meta instanceof KnowledgeBookMeta kb && kb.hasRecipes()) {
            return "knowledge book recipes";
        }
        if (meta instanceof TropicalFishBucketMeta fish && fish.hasVariant()) {
            return "tropical fish variant";
        }
        if (meta instanceof CrossbowMeta crossbow && crossbow.hasChargedProjectiles()) {
            return "charged crossbow projectiles";
        }
        if (meta instanceof MusicInstrumentMeta instrument && instrument.getInstrument() != null) {
            return "goat horn instrument";
        }
        return null;
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
