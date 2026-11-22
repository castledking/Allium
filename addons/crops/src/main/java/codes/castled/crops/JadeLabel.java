package codes.castled.crops;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.Objects;

final class JadeLabel {
    private JadeLabel() {}

    static void apply(ItemStack stack, String id) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return;
            String tag = tags(meta);
            if (tag == null || tag.contains("$jade:stack")) return;
            String entry = "\"$jade:stack\":{id:\"" + id + "\"}";
            String patched;
            if (tag.startsWith("{")) {
                // Item NBT (before 1.20.5): the entry is a root tag.
                patched = tag.equals("{}") ? "{" + entry + "}" : "{" + entry + "," + tag.substring(1);
            } else {
                // Data components (1.20.5+): the entry goes inside custom_data, beside Bukkit's data.
                int at = tag.indexOf("custom_data={");
                if (at < 0) return;
                at += "custom_data={".length();
                patched = tag.substring(0, at) + entry + (tag.charAt(at) == '}' ? "" : ",") + tag.substring(at);
            }
            ItemStack rebuilt = Bukkit.getItemFactory().createItemStack(stack.getType().getKey() + patched);
            ItemMeta rebuiltMeta = rebuilt.getItemMeta();
            if (rebuiltMeta == null
                    || !rebuiltMeta.getPersistentDataContainer().getKeys().equals(meta.getPersistentDataContainer().getKeys())
                    || !Objects.equals(rebuiltMeta.getLore(), meta.getLore())
                    || !tags(rebuiltMeta).contains("$jade:stack")) {
                return;
            }
            stack.setItemMeta(rebuiltMeta);
        } catch (RuntimeException | LinkageError unavailable) {
            // No raw item text on this server: Jade keeps showing the item's own namespace.
        }
    }

    private static String tags(ItemMeta meta) {
        try {
            return (String) ItemMeta.class.getMethod("getAsComponentString").invoke(meta);
        } catch (ReflectiveOperationException oldApi) {
            return meta.getAsString();
        }
    }
}
