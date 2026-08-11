package codes.castled.allium.listeners.items;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItem;
import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.items.impl.MobDisarmerItem;
import codes.castled.allium.items.impl.PhantomObliteratorItem;
import codes.castled.allium.util.ApiCompat;

/**
 * Prevents the two charged swords from disappearing to vanilla durability. Their final durability
 * hit leaves them worn (one point before break) and gives them stick-level attack damage. An anvil
 * repair clears only that temporary combat penalty; it does not repair either item's ability.
 */
public final class SwordToolDurabilityListener implements Listener {
    private static final UUID MOB_WORN_MODIFIER = UUID.fromString("81099d69-214a-4e8d-a5d6-947a237d6cb6");
    private static final UUID PHANTOM_WORN_MODIFIER = UUID.fromString("5cdb4ba7-53d4-42cd-9991-7d22af36e86a");

    private final CustomItemRegistry registry;
    private final NamespacedKey mobWornKey;
    private final NamespacedKey phantomWornKey;

    public SwordToolDurabilityListener(final Plugin plugin, final CustomItemRegistry registry) {
        this.registry = registry;
        this.mobWornKey = new NamespacedKey(plugin, "mob_disarmer_worn");
        this.phantomWornKey = new NamespacedKey(plugin, "phantom_obliterator_worn");
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(final PlayerItemDamageEvent event) {
        final Tool tool = tool(event.getItem());
        if (tool == null) return;

        if (isWorn(event.getItem(), tool)) {
            event.setCancelled(true);
            return;
        }
        final int maxDurability = event.getItem().getType().getMaxDurability();
        if (maxDurability <= 0 || damage(event.getItem()) + event.getDamage() < maxDurability) return;

        makeWorn(event.getItem(), tool, maxDurability - 1);
        event.setCancelled(true); // Never permit vanilla to remove the stack.
    }

    @EventHandler
    public void onPrepareAnvil(final PrepareAnvilEvent event) {
        final ItemStack input = event.getInventory().getItem(0);
        final Tool inputTool = tool(input);
        final ItemStack result = event.getResult();
        if (inputTool == null || result == null || result.getType() == Material.AIR || !isWorn(input, inputTool)
                || tool(result) != inputTool || damage(result) >= damage(input)) return;

        repairWornState(result, inputTool);
        event.setResult(result);
    }

    private Tool tool(final ItemStack item) {
        final CustomItem customItem = registry.getItem(item);
        if (customItem instanceof MobDisarmerItem) return Tool.MOB_DISARMER;
        if (customItem instanceof PhantomObliteratorItem) return Tool.PHANTOM_OBLITERATOR;
        return null;
    }

    private boolean isWorn(final ItemStack item, final Tool tool) {
        final ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && Boolean.TRUE.equals(meta.getPersistentDataContainer()
                .get(wornKey(tool), PersistentDataType.BOOLEAN));
    }

    private void makeWorn(final ItemStack item, final Tool tool, final int damage) {
        final ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;
        damageable.setDamage(damage);
        meta.getPersistentDataContainer().set(wornKey(tool), PersistentDataType.BOOLEAN, true);
        removeWornModifier(meta, tool);
        if (ApiCompat.ATTACK_DAMAGE != null) {
            meta.addAttributeModifier(ApiCompat.ATTACK_DAMAGE, new AttributeModifier(
                    modifierId(tool), "allium_worn_sword_penalty", attackPenalty(tool),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        }
        item.setItemMeta(meta);
    }

    private void repairWornState(final ItemStack item, final Tool tool) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(wornKey(tool));
        removeWornModifier(meta, tool);
        item.setItemMeta(meta);
    }

    private void removeWornModifier(final ItemMeta meta, final Tool tool) {
        if (ApiCompat.ATTACK_DAMAGE == null) return;
        final Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(ApiCompat.ATTACK_DAMAGE);
        if (modifiers == null) return;
        for (final AttributeModifier modifier : modifiers) {
            if (modifierId(tool).equals(modifier.getUniqueId())) {
                meta.removeAttributeModifier(ApiCompat.ATTACK_DAMAGE, modifier);
            }
        }
    }

    private int damage(final ItemStack item) {
        final ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    private NamespacedKey wornKey(final Tool tool) {
        return tool == Tool.MOB_DISARMER ? mobWornKey : phantomWornKey;
    }

    private UUID modifierId(final Tool tool) {
        return tool == Tool.MOB_DISARMER ? MOB_WORN_MODIFIER : PHANTOM_WORN_MODIFIER;
    }

    /** Diamond sword is 7 damage and netherite sword is 8; both end at the stick's 1 damage. */
    private double attackPenalty(final Tool tool) {
        return tool == Tool.MOB_DISARMER ? -6.0D : -7.0D;
    }

    private enum Tool { MOB_DISARMER, PHANTOM_OBLITERATOR }
}
