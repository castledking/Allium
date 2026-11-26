package codes.castled.allium.worth;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/** Uses LiteFish's configured drop price and the weight stored on the actual catch. */
record LiteFishWorth(Plugin plugin, Method readId, Method readWeight, Field drops, Field unitPrice) {
    record Quote(BigDecimal price) {}

    static LiteFishWorth connect(Plugin plugin) throws ReflectiveOperationException {
        var loader = plugin.getClass().getClassLoader();
        var nbt = Class.forName("dev.nekomadev.litecore.Utils.NBTUtils", false, loader);
        var drop = Class.forName("dev.nekomadev.liteFish.data.configs.DropConfig", false, loader);
        return new LiteFishWorth(plugin, nbt.getMethod("readNbtString", ItemStack.class, String.class),
                nbt.getMethod("readNbtDouble", ItemStack.class, String.class),
                plugin.getClass().getField("cache_drop"), drop.getField("price"));
    }

    Optional<Quote> quote(ItemStack stack) {
        if (!plugin.isEnabled() || !stack.hasItemMeta()) return Optional.empty();
        try {
            var id = readId.invoke(null, stack, "id");
            if (!(id instanceof String key) || key.isBlank() || !(drops.get(null) instanceof Map<?, ?> known)) return Optional.empty();
            Object drop = known.get(key);
            if (drop == null) return Optional.empty();
            double base = unitPrice.getDouble(drop);
            double weight = ((Number) readWeight.invoke(null, stack, "weight")).doubleValue();
            double value = base * (weight == 0 ? 1 : weight);
            if (!Double.isFinite(value) || value < 0 || weight < 0 || base < 0
                    || value > WorthSettings.MAX_PRICE.doubleValue()) return Optional.of(new Quote(BigDecimal.ZERO));
            return Optional.of(new Quote(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }
}
