package codes.castled.allium.legacy;

import codes.castled.allium.xp.XpBottles;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Uses the same stored item keys across Bukkit's metadata API generations. */
abstract class LegacyXpBottleData {
    private static final NamespacedKey AMOUNT = new NamespacedKey("allium", XpBottles.KEY_AMOUNT);
    private static final NamespacedKey SIGNER = new NamespacedKey("allium", XpBottles.KEY_SIGNER);

    static LegacyXpBottleData create(Server server) throws ReflectiveOperationException {
        try {
            return new BukkitData("org.bukkit.persistence.PersistentDataContainer",
                    "org.bukkit.persistence.PersistentDataType", "getPersistentDataContainer", "get", "set", "has");
        } catch (ClassNotFoundException unavailableUntil114) {
            try {
                return new BukkitData("org.bukkit.inventory.meta.tags.CustomItemTagContainer",
                        "org.bukkit.inventory.meta.tags.ItemTagType", "getCustomTagContainer",
                        "getCustomTag", "setCustomTag", "hasCustomTag");
            } catch (ClassNotFoundException unavailableUntil113) {
                return new UnhandledItemTags(server.getClass().getPackage().getName());
            }
        }
    }

    abstract void write(ItemMeta meta, int amount, String signer) throws ReflectiveOperationException;
    abstract int amount(ItemMeta meta) throws ReflectiveOperationException;

    private static final class BukkitData extends LegacyXpBottleData {
        private final Method container;
        private final Method set;
        private final Method get;
        private final Method has;
        private final Object integerType;
        private final Object stringType;

        private BukkitData(String containerClass, String typeClass, String containerMethod,
                           String getter, String setter, String tester) throws ReflectiveOperationException {
            Class<?> dataContainer = Class.forName(containerClass);
            Class<?> dataType = Class.forName(typeClass);
            container = ItemMeta.class.getMethod(containerMethod);
            set = dataContainer.getMethod(setter, NamespacedKey.class, dataType, Object.class);
            get = dataContainer.getMethod(getter, NamespacedKey.class, dataType);
            has = dataContainer.getMethod(tester, NamespacedKey.class, dataType);
            integerType = dataType.getField("INTEGER").get(null);
            stringType = dataType.getField("STRING").get(null);
        }

        @Override
        void write(ItemMeta meta, int amount, String signer) throws ReflectiveOperationException {
            Object data = container.invoke(meta);
            set.invoke(data, AMOUNT, integerType, amount);
            set.invoke(data, SIGNER, stringType, signer);
        }

        @Override
        int amount(ItemMeta meta) throws ReflectiveOperationException {
            Object data = container.invoke(meta);
            if (!(Boolean) has.invoke(data, AMOUNT, integerType)) return 0;
            Integer amount = (Integer) get.invoke(data, AMOUNT, integerType);
            return amount == null ? 0 : amount;
        }
    }

    private static final class UnhandledItemTags extends LegacyXpBottleData {
        private static final String PUBLIC_VALUES = "PublicBukkitValues";
        private static final int INT_TAG = 3;

        private final Field tags;
        private final Constructor<?> compound;
        private final Method setInt;
        private final Method setString;
        private final Method getInt;
        private final Method hasTag;

        private UnhandledItemTags(String craftPackage) throws ReflectiveOperationException {
            Class<?> craftMeta = Class.forName(craftPackage + ".inventory.CraftMetaItem");
            String nmsPackage = craftPackage.replace("org.bukkit.craftbukkit", "net.minecraft.server");
            Class<?> nbt = Class.forName(nmsPackage + ".NBTTagCompound");
            tags = craftMeta.getDeclaredField("unhandledTags");
            tags.setAccessible(true);
            compound = nbt.getConstructor();
            setInt = nbt.getMethod("setInt", String.class, int.class);
            setString = nbt.getMethod("setString", String.class, String.class);
            getInt = nbt.getMethod("getInt", String.class);
            hasTag = nbt.getMethod("hasKeyOfType", String.class, int.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        void write(ItemMeta meta, int amount, String signer) throws ReflectiveOperationException {
            Object values = compound.newInstance();
            setInt.invoke(values, AMOUNT.toString(), amount);
            setString.invoke(values, SIGNER.toString(), signer);
            ((Map<String, Object>) tags.get(meta)).put(PUBLIC_VALUES, values);
        }

        @Override
        int amount(ItemMeta meta) throws ReflectiveOperationException {
            Object values = ((Map<?, ?>) tags.get(meta)).get(PUBLIC_VALUES);
            if (!compound.getDeclaringClass().isInstance(values) || !(Boolean) hasTag.invoke(values, AMOUNT.toString(), INT_TAG)) {
                return 0;
            }
            return (Integer) getInt.invoke(values, AMOUNT.toString());
        }
    }
}
