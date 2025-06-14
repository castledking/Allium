package net.survivalfun.core.managers.DB;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays; // Added import
import java.util.Objects;

/**
 * Data class to hold player inventories for both survival and creative modes.
 */
public record PlayerInventories(
    ItemStack @Nullable [] survivalInventory,
    ItemStack @Nullable [] survivalArmor,
    @Nullable ItemStack survivalOffhand,
    ItemStack @Nullable [] creativeInventory,
    ItemStack @Nullable [] creativeArmor,
    @Nullable ItemStack creativeOffhand
) {
    /**
     * Gets the survival inventory, or an empty array if null.
     */
    public ItemStack @NotNull [] getSurvivalInventory() {
        return survivalInventory != null ? survivalInventory : new ItemStack[0];
    }

    /**
     * Gets the survival armor, or an empty array if null.
     */
    public ItemStack @NotNull [] getSurvivalArmor() {
        return survivalArmor != null ? survivalArmor : new ItemStack[0];
    }

    /**
     * Gets the survival offhand item, or null if not present.
     */
    public @Nullable ItemStack getSurvivalOffhand() {
        return survivalOffhand;
    }

    /**
     * Gets the creative inventory, or an empty array if null.
     */
    public ItemStack @NotNull [] getCreativeInventory() {
        return creativeInventory != null ? creativeInventory : new ItemStack[0];
    }

    /**
     * Gets the creative armor, or an empty array if null.
     */
    public ItemStack @NotNull [] getCreativeArmor() {
        return creativeArmor != null ? creativeArmor : new ItemStack[0];
    }

    /**
     * Gets the creative offhand item, or null if not present.
     */
    public @Nullable ItemStack getCreativeOffhand() {
        return creativeOffhand;
    }

    /**
     * Creates a new PlayerInventories with the given survival inventory.
     */
    public PlayerInventories withSurvivalInventory(ItemStack @Nullable [] survivalInventory) {
        return new PlayerInventories(
            survivalInventory,
            this.survivalArmor,
            this.survivalOffhand,
            this.creativeInventory,
            this.creativeArmor,
            this.creativeOffhand
        );
    }

    /**
     * Creates an empty PlayerInventories instance.
     */
    public static PlayerInventories empty() {
        return new PlayerInventories(null, null, null, null, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerInventories that = (PlayerInventories) o;
        return Arrays.equals(survivalInventory, that.survivalInventory) &&
               Arrays.equals(survivalArmor, that.survivalArmor) &&
               Objects.equals(survivalOffhand, that.survivalOffhand) &&
               Arrays.equals(creativeInventory, that.creativeInventory) &&
               Arrays.equals(creativeArmor, that.creativeArmor) &&
               Objects.equals(creativeOffhand, that.creativeOffhand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            Arrays.hashCode(survivalInventory),
            Arrays.hashCode(survivalArmor),
            survivalOffhand,
            Arrays.hashCode(creativeInventory),
            Arrays.hashCode(creativeArmor),
            creativeOffhand
        );
    }
}