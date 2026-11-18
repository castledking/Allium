package codes.castled.allium.item;

import codes.castled.allium.mail.MailItemCodec;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Slot-positioned item arrays as text: a {@code slots:} line, then the stacks in the mail item
 * codec's versioned, data-version aware form (persistent data, signed identities and provider IDs
 * travel unchanged). Server thread only, like the item codec.
 */
public final class SlotCodec {

    private final MailItemCodec codec = new MailItemCodec();

    /** The non-empty slots of {@code contents}, or null when every slot is empty. */
    @Nullable
    public String encode(ItemStack[] contents) {
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                slots.add(i);
                stacks.add(stack);
            }
        }
        if (stacks.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("slots:");
        for (int i = 0; i < slots.size(); i++) {
            text.append(i == 0 ? "" : ",").append(slots.get(i));
        }
        return text.append('\n').append(codec.encode(stacks)).toString();
    }

    /** {@code size} slots; empty for a null payload. Refuses newer data versions and malformed slots. */
    public ItemStack[] decode(@Nullable String payload, int size) throws IOException {
        ItemStack[] out = new ItemStack[size];
        if (payload == null || payload.isEmpty()) {
            return out;
        }
        int newline = payload.indexOf('\n');
        if (!payload.startsWith("slots:") || newline < 0) {
            throw new IOException("stored items have no slot list");
        }
        String[] parts = payload.substring("slots:".length(), newline).split(",");
        List<ItemStack> stacks = codec.decode(payload.substring(newline + 1));
        if (stacks.size() != parts.length) {
            throw new IOException("stored slot count does not match its items");
        }
        for (int i = 0; i < parts.length; i++) {
            int slot;
            try {
                slot = Integer.parseInt(parts[i]);
            } catch (NumberFormatException bad) {
                throw new IOException("stored items have a malformed slot", bad);
            }
            if (slot < 0 || slot >= size || out[slot] != null) {
                throw new IOException("stored items have an invalid slot " + slot);
            }
            out[slot] = stacks.get(i);
        }
        return out;
    }
}
