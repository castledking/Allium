package net.survivalfun.core.inventory;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

public class InventorySnapshot {
    private final UUID playerId;
    private final long timestamp;
    private final String reason;
    private String inventoryData;
    private String armorData;
    private String extraData;
    private String offhandData;
    private String enderChestData;
    private int experience;
    private Location location;
    private String worldName;

    public InventorySnapshot(Player player, String reason) {
        this(player.getUniqueId(), 
             System.currentTimeMillis(), 
             reason,
             toBase64(player.getInventory().getContents()),
             toBase64(player.getInventory().getArmorContents()),
             toBase64(new ItemStack[]{player.getInventory().getItemInOffHand()}),
             toBase64(player.getEnderChest().getContents()),
             player.getTotalExperience(),
             player.getLocation(),
             player.getWorld().getName());
    }
    
    public InventorySnapshot(UUID playerId, long timestamp, String reason, 
                           String inventoryData, String armorData, String offhandData, String enderChestData,
                           int experience, Location location, String worldName) {
        this.playerId = playerId;
        this.timestamp = timestamp;
        this.reason = reason;
        this.inventoryData = inventoryData;
        this.armorData = armorData;
        this.offhandData = offhandData;
        this.enderChestData = enderChestData != null ? enderChestData : "";
        this.experience = experience;
        this.location = location;
        this.worldName = worldName;
        this.extraData = "";
    }

    // Getters and serialization methods
    public UUID getPlayerId() { return playerId; }
    public long getTimestamp() { return timestamp; }
    public String getReason() { return reason; }
    public int getExperience() { return experience; }
    public Location getLocation() { return location; }
    public String getWorldName() { return worldName; }
    
    public String getInventoryData() { return inventoryData; }
    
    public String getArmorData() { return armorData; }

    public String getEnderChestData() { return enderChestData; }

    public ItemStack[] getInventoryContents() {
        return fromBase64(inventoryData);
    }

    public ItemStack[] getArmorContents() {
        return fromBase64(armorData);
    }

    public ItemStack[] getEnderChestContents() {
        ItemStack[] contents = fromBase64(enderChestData);
        if (contents.length == 27) {
            return contents;
        }
        return Arrays.copyOf(contents, 27);
    }

    public String getOffhandData() { return offhandData; }

    public ItemStack getOffhandItem() {
        ItemStack[] offhand = fromBase64(offhandData);
        return offhand.length > 0 ? offhand[0] : null;
    }

    public static String toBase64(ItemStack[] items) {
        if (items == null) {
            items = new ItemStack[0];
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save item stacks.", e);
        }
    }

    public static ItemStack[] fromBase64(String data) {
        if (data == null || data.isEmpty()) {
            Text.sendDebugLog(WARN, "Attempted to deserialize null or empty inventory data");
            return new ItemStack[0];
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            
            int length = dataInput.readInt();
            Text.sendDebugLog(INFO, "Deserializing inventory with " + length + " items");
            ItemStack[] items = new ItemStack[length];
            
            for (int i = 0; i < length; i++) {
                try {
                    Object obj = dataInput.readObject();
                    if (obj instanceof ItemStack) {
                        items[i] = (ItemStack) obj;
                        if (items[i] != null) {
                            Text.sendDebugLog(INFO, "Deserialized item at index " + i + ": " + items[i].getType() + " x" + items[i].getAmount());
                        } else {
                            Text.sendDebugLog(INFO, "Deserialized null item at index " + i);
                        }
                    } else {
                        Text.sendDebugLog(WARN, "Unexpected object type at index " + i + ": " + (obj != null ? obj.getClass().getName() : "null"));
                        items[i] = null;
                    }
                } catch (Exception e) {
                    Text.sendDebugLog(WARN, "Error deserializing item at index " + i + ": " + e.getMessage());
                    items[i] = null; // Set to null instead of failing the whole array
                }
            }
            return items;
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error while deserializing inventory data: " + e.getMessage());
            e.printStackTrace();
            return new ItemStack[0];
        }
    }
}
