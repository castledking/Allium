package codes.castled.allium.mail;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a gift's item stacks for storage, versioned and data-version aware.
 *
 * <ul>
 *   <li>Each stack is Bukkit's own configuration serialization, which carries the stack's data
 *       version ({@code v}). The server's deserializer upgrades older items through Minecraft's
 *       data fixers, so a gift sent on 1.20.1 is claimed correctly on 26.3.</li>
 *   <li>The envelope records the writing server's data version. A newer gift is refused on an
 *       older server, rather than read wrongly, and stays in the mailbox.</li>
 *   <li>The stack travels as it is: its persistent data (an Allium tool's signed identity, state
 *       and MAC, a provider's ID), enchantments, damage and name are never rebuilt.</li>
 * </ul>
 *
 * <p>Server thread only: item meta and data fixing are server APIs.
 */
public final class MailItemCodec {

    public static final int FORMAT = 2;
    public static final int MAX_CHARS = 1 << 20;

    public static final class UnreadableException extends IOException {
        public UnreadableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @SuppressWarnings("deprecation")
    public static int serverDataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
    }

    public String encode(List<ItemStack> stacks) {
        if (stacks.isEmpty() || stacks.size() > 54) throw new IllegalArgumentException("gift requires 1..54 stacks");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        yaml.set("data-version", serverDataVersion());
        List<ItemStack> copies = new ArrayList<>();
        List<java.util.Map<String,Object>> persistent = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() < 1 || stack.getAmount() > 2304)
                throw new IllegalArgumentException("invalid gift stack");
            copies.add(stack.clone());
            persistent.add(PersistentItemData.capture(stack.getItemMeta().getPersistentDataContainer()));
        }
        yaml.set("items", copies);
        yaml.set("persistent-data", persistent);
        String text = yaml.saveToString();
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("a gift's items encode to more than " + MAX_CHARS + " characters");
        }
        return text;
    }

    /** The data version the items were written on, or -1 if unreadable. */
    public int dataVersionOf(String encoded) {
        try {
            return envelope(encoded)[1];
        } catch (IOException e) {
            return -1;
        }
    }

    public List<ItemStack> decode(String encoded) throws IOException {
        int[] envelope = envelope(encoded);
        int written = envelope[1];
        if (envelope[0] != FORMAT) throw new UnreadableException("unknown gift format", null);
        // Check before Bukkit constructs any ItemStack or invokes the data fixer.
        if (written > serverDataVersion()) throw new UnreadableException("gift was written by a newer Minecraft data version", null);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(encoded);
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new UnreadableException("the stored items are not valid", e);
        }
        if (yaml.getInt("format", -1) != FORMAT) {
            throw new UnreadableException("the stored items use an unknown format " + yaml.get("format"), null);
        }
        List<?> raw = yaml.getList("items");
        if (raw == null || raw.isEmpty() || raw.size() > 54) {
            throw new UnreadableException("the stored gift has no items", null);
        }
        List<?> persistent = yaml.getList("persistent-data");
        if (persistent == null || persistent.size() != raw.size()) throw new UnreadableException("missing typed persistent data", null);
        List<ItemStack> out = new ArrayList<>();
        for (int i=0;i<raw.size();i++) {
            Object o=raw.get(i);
            if (!(o instanceof ItemStack stack) || stack.getType().isAir() || stack.getAmount() < 1 || stack.getAmount() > 2304) {
                throw new UnreadableException("a stored item could not be read on this server", null);
            }
            try {
                var meta=stack.getItemMeta();
                PersistentItemData.restore(meta.getPersistentDataContainer(), (java.util.Map<?,?>) persistent.get(i));
                if (!stack.setItemMeta(meta)) throw new IllegalArgumentException("item metadata refused");
            } catch (RuntimeException failure) { throw new UnreadableException("invalid typed persistent data", failure); }
            out.add(stack);
        }
        return out;
    }

    private static int[] envelope(String encoded) throws IOException {
        if (encoded == null || encoded.length() > MAX_CHARS) throw new UnreadableException("stored items missing or too large", null);
        var header = java.util.regex.Pattern.compile("\\Aformat: ([0-9]{1,9})\\r?\\ndata-version: ([0-9]{1,9})\\r?\\n").matcher(encoded);
        if (!header.find()) throw new UnreadableException("invalid gift envelope", null);
        return new int[]{Integer.parseInt(header.group(1)), Integer.parseInt(header.group(2))};
    }
}
