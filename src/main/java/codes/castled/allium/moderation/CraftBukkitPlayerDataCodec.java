package codes.castled.allium.moderation;

import org.bukkit.Server;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;

/**
 * Reflective CraftBukkit/NMS {@link PlayerDataCodec} supporting both:
 * <ul>
 *   <li>Spigot 1.20.x legacy {@code NBTCompressedStreamTools} / {@code PlayerInventory} / {@code InventoryEnderChest}</li>
 *   <li>Paper 26.3+ codec {@code NbtIo} / {@code TagValueInput} / {@code TagValueOutput} / {@code EntityEquipment}</li>
 * </ul>
 */
public final class CraftBukkitPlayerDataCodec implements PlayerDataCodec {

    private interface Backend {
        LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type, int currentVersion) throws IOException;

        byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots, int currentVersion) throws IOException;
    }

    private final Server server;
    private final Backend backend;

    public CraftBukkitPlayerDataCodec(Server server) {
        this.server = Objects.requireNonNull(server, "server");
        this.backend = resolveBackend(server);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int currentDataVersion() {
        return server.getUnsafe().getDataVersion();
    }

    @Override
    public LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type) throws IOException {
        Objects.requireNonNull(compressedNbt, "compressedNbt");
        Objects.requireNonNull(type, "type");
        return backend.read(compressedNbt, type, currentDataVersion());
    }

    @Override
    public byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots) throws IOException {
        Objects.requireNonNull(baseCompressedNbt, "baseCompressedNbt");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(playerSlots, "playerSlots");
        return backend.write(baseCompressedNbt, type, playerSlots, currentDataVersion());
    }

    private static Backend resolveBackend(Server server) {
        String craftPkg = server.getClass().getPackage().getName();
        Class<?> craftInvClass = loadClass(craftPkg + ".inventory.CraftInventory");

        if (classExists("net.minecraft.world.level.storage.TagValueOutput")) {
            return new CodecBackend(server, craftInvClass);
        }
        return new LegacyBackend(server, craftInvClass);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, CraftBukkitPlayerDataCodec.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unsupported CraftBukkit class: " + name, e);
        }
    }

    private static ItemStack[] extractSlots(Inventory bukkitInv, int count) {
        ItemStack[] out = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            ItemStack item = bukkitInv.getItem(i);
            out[i] = (item == null || item.getType().isAir() || item.getAmount() <= 0) ? null : item.clone();
        }
        return out;
    }

    private static void applySlots(Inventory bukkitInv, ItemStack[] slots, int count) {
        for (int i = 0; i < count; i++) {
            ItemStack stack = (slots != null && i < slots.length) ? slots[i] : null;
            bukkitInv.setItem(i, (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) ? null : stack.clone());
        }
    }

    private static IOException unwrapIo(Throwable t) {
        Throwable cur = (t instanceof InvocationTargetException ite && ite.getCause() != null) ? ite.getCause() : t;
        if (cur instanceof IOException io) {
            return io;
        }
        if (cur instanceof RuntimeException re) {
            throw re;
        }
        return new IOException("Player data codec failure: " + cur.getMessage(), cur);
    }

    // ---------------------------------------------------------------------------------------------
    // Paper 26.3+ (and Mojang-mapped ValueInput/ValueOutput servers)
    // ---------------------------------------------------------------------------------------------
    private static final class CodecBackend implements Backend {
        private final Object registryAccess;
        private final Object discardingReporter;

        private final Method nbtReadCompressed;
        private final Method nbtWriteCompressed;
        private final Method nbtUnlimitedHeap;
        private final Method compoundGetIntOr;
        private final Method mcConvertTag;
        private final Object mcPlayerType;

        private final Method tagInputCreate;
        private final Method tagInputRead;
        private final Method tagInputListOrEmpty;

        private final Method tagOutputCreateWrapping;
        private final Method tagOutputList;
        private final Method tagOutputStore;
        private final Method tagOutputDiscard;
        private final Method tagOutputBuildResult;
        private final Method nbtUtilsAddCurrentDataVersion;

        private final Object itemStackWithSlotCodec;
        private final Object entityEquipmentCodec;

        private final Constructor<?> entityEquipmentCtor;
        private final Method equipmentSetAll;
        private final Method equipmentIsEmpty;

        private final Constructor<?> playerInvCtor;
        private final Method playerInvLoad;
        private final Method playerInvSave;

        private final Constructor<?> enderChestCtor;
        private final Method enderChestFromSlots;
        private final Method enderChestStoreAsSlots;

        private final Constructor<?> craftInvCtor;

        CodecBackend(Server server, Class<?> craftInvClass) {
            try {
                Object mcServer = server.getClass().getMethod("getServer").invoke(server);
                this.registryAccess = mcServer.getClass().getMethod("registryAccess").invoke(mcServer);

                Class<?> problemReporterClass = Class.forName("net.minecraft.util.ProblemReporter");
                this.discardingReporter = problemReporterClass.getField("DISCARDING").get(null);

                Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
                Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
                Class<?> nbtAccounterClass = Class.forName("net.minecraft.nbt.NbtAccounter");
                this.nbtUnlimitedHeap = nbtAccounterClass.getMethod("unlimitedHeap");
                this.nbtReadCompressed = nbtIoClass.getMethod("readCompressed", InputStream.class, nbtAccounterClass);
                this.nbtWriteCompressed = nbtIoClass.getMethod("writeCompressed", compoundTagClass, OutputStream.class);
                this.compoundGetIntOr = compoundTagClass.getMethod("getIntOr", String.class, int.class);

                Class<?> mcConverterClass = Class.forName("ca.spottedleaf.dataconverter.minecraft.MCDataConverter");
                Class<?> mcRegistryClass = Class.forName("ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry");
                Class<?> mcDataTypeClass = Class.forName("ca.spottedleaf.dataconverter.minecraft.datatypes.MCDataType");
                this.mcPlayerType = mcRegistryClass.getField("PLAYER").get(null);
                this.mcConvertTag = mcConverterClass.getMethod("convertTag", mcDataTypeClass, compoundTagClass, int.class, int.class);

                Class<?> holderLookupProviderClass = Class.forName("net.minecraft.core.HolderLookup$Provider");
                Class<?> codecClass = Class.forName("com.mojang.serialization.Codec");

                Class<?> tagInputClass = Class.forName("net.minecraft.world.level.storage.TagValueInput");
                Class<?> valueInputClass = Class.forName("net.minecraft.world.level.storage.ValueInput");
                Class<?> typedInputListClass = Class.forName("net.minecraft.world.level.storage.ValueInput$TypedInputList");
                this.tagInputCreate = tagInputClass.getMethod("create", problemReporterClass, holderLookupProviderClass, compoundTagClass);
                this.tagInputRead = valueInputClass.getMethod("read", String.class, codecClass);
                this.tagInputListOrEmpty = valueInputClass.getMethod("listOrEmpty", String.class, codecClass);

                Class<?> tagOutputClass = Class.forName("net.minecraft.world.level.storage.TagValueOutput");
                Class<?> valueOutputClass = Class.forName("net.minecraft.world.level.storage.ValueOutput");
                Class<?> typedOutputListClass = Class.forName("net.minecraft.world.level.storage.ValueOutput$TypedOutputList");
                this.tagOutputCreateWrapping = tagOutputClass.getMethod("createWrappingWithContext", problemReporterClass, holderLookupProviderClass, compoundTagClass);
                this.tagOutputList = valueOutputClass.getMethod("list", String.class, codecClass);
                this.tagOutputStore = valueOutputClass.getMethod("store", String.class, codecClass, Object.class);
                this.tagOutputDiscard = valueOutputClass.getMethod("discard", String.class);
                this.tagOutputBuildResult = tagOutputClass.getMethod("buildResult");

                Class<?> nbtUtilsClass = Class.forName("net.minecraft.nbt.NbtUtils");
                this.nbtUtilsAddCurrentDataVersion = nbtUtilsClass.getMethod("addCurrentDataVersion", valueOutputClass);

                Class<?> itemStackWithSlotClass = Class.forName("net.minecraft.world.ItemStackWithSlot");
                this.itemStackWithSlotCodec = itemStackWithSlotClass.getField("CODEC").get(null);

                Class<?> equipmentClass = Class.forName("net.minecraft.world.entity.EntityEquipment");
                this.entityEquipmentCodec = equipmentClass.getField("CODEC").get(null);
                this.entityEquipmentCtor = equipmentClass.getConstructor();
                this.equipmentSetAll = equipmentClass.getMethod("setAll", equipmentClass);
                this.equipmentIsEmpty = equipmentClass.getMethod("isEmpty");

                Class<?> nmsPlayerClass = Class.forName("net.minecraft.world.entity.player.Player");
                Class<?> nmsInvClass = Class.forName("net.minecraft.world.entity.player.Inventory");
                this.playerInvCtor = nmsInvClass.getConstructor(nmsPlayerClass, equipmentClass);
                this.playerInvLoad = nmsInvClass.getMethod("load", typedInputListClass);
                this.playerInvSave = nmsInvClass.getMethod("save", typedOutputListClass);

                Class<?> enderChestClass = Class.forName("net.minecraft.world.inventory.PlayerEnderChestContainer");
                this.enderChestCtor = enderChestClass.getConstructor(nmsPlayerClass);
                this.enderChestFromSlots = enderChestClass.getMethod("fromSlots", typedInputListClass);
                this.enderChestStoreAsSlots = enderChestClass.getMethod("storeAsSlots", typedOutputListClass);

                Class<?> containerClass = Class.forName("net.minecraft.world.Container");
                this.craftInvCtor = craftInvClass.getConstructor(containerClass);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize Paper/Mojang player data codec backend", e);
            }
        }

        private Object readAndUpgrade(byte[] compressedNbt, int currentVersion) throws IOException {
            try {
                Object accounter = nbtUnlimitedHeap.invoke(null);
                Object root = nbtReadCompressed.invoke(null, new ByteArrayInputStream(compressedNbt), accounter);
                int fileVersion = (int) compoundGetIntOr.invoke(root, "DataVersion", 0);
                if (fileVersion > currentVersion) {
                    throw new DataVersionTooNewException(fileVersion, currentVersion);
                }
                if (fileVersion > 0 && fileVersion < currentVersion) {
                    root = mcConvertTag.invoke(null, mcPlayerType, root, fileVersion, currentVersion);
                }
                return root;
            } catch (IOException io) {
                throw io;
            } catch (ReflectiveOperationException e) {
                throw unwrapIo(e);
            }
        }

        @Override
        public LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type, int currentVersion) throws IOException {
            try {
                Object root = readAndUpgrade(compressedNbt, currentVersion);
                int fileVersion = (int) compoundGetIntOr.invoke(root, "DataVersion", 0);
                Object input = tagInputCreate.invoke(null, discardingReporter, registryAccess, root);

                if (type == InspectionLayout.InspectionType.INVSEE) {
                    Object eq = entityEquipmentCtor.newInstance();
                    Object inv = playerInvCtor.newInstance(null, eq);
                    Object list = tagInputListOrEmpty.invoke(input, "Inventory", itemStackWithSlotCodec);
                    playerInvLoad.invoke(inv, list);
                    Optional<?> loadedEq = (Optional<?>) tagInputRead.invoke(input, "equipment", entityEquipmentCodec);
                    if (loadedEq.isPresent()) {
                        equipmentSetAll.invoke(eq, loadedEq.get());
                    }
                    Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(inv);
                    return new LoadedData(fileVersion, extractSlots(bukkitInv, InspectionLayout.INVSEE_PLAYER_SLOT_COUNT));
                }

                Object ec = enderChestCtor.newInstance(new Object[]{null});
                Object list = tagInputListOrEmpty.invoke(input, "EnderItems", itemStackWithSlotCodec);
                enderChestFromSlots.invoke(ec, list);
                Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(ec);
                return new LoadedData(fileVersion, extractSlots(bukkitInv, InspectionLayout.ENDERCHEST_GUI_SIZE));
            } catch (IOException io) {
                throw io;
            } catch (ReflectiveOperationException e) {
                throw unwrapIo(e);
            }
        }

        @Override
        public byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots, int currentVersion) throws IOException {
            try {
                Object root = readAndUpgrade(baseCompressedNbt, currentVersion);
                Object output = tagOutputCreateWrapping.invoke(null, discardingReporter, registryAccess, root);

                if (type == InspectionLayout.InspectionType.INVSEE) {
                    Object eq = entityEquipmentCtor.newInstance();
                    // Preserve any non-player equipment slots already present in rootTag before applying player slots 0..40.
                    Object input = tagInputCreate.invoke(null, discardingReporter, registryAccess, root);
                    Optional<?> existingEq = (Optional<?>) tagInputRead.invoke(input, "equipment", entityEquipmentCodec);
                    if (existingEq.isPresent()) {
                        equipmentSetAll.invoke(eq, existingEq.get());
                    }
                    Object inv = playerInvCtor.newInstance(null, eq);
                    Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(inv);
                    applySlots(bukkitInv, playerSlots, InspectionLayout.INVSEE_PLAYER_SLOT_COUNT);

                    Object outList = tagOutputList.invoke(output, "Inventory", itemStackWithSlotCodec);
                    playerInvSave.invoke(inv, outList);
                    if ((boolean) equipmentIsEmpty.invoke(eq)) {
                        tagOutputDiscard.invoke(output, "equipment");
                    } else {
                        tagOutputStore.invoke(output, "equipment", entityEquipmentCodec, eq);
                    }
                } else {
                    Object ec = enderChestCtor.newInstance(new Object[]{null});
                    Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(ec);
                    applySlots(bukkitInv, playerSlots, InspectionLayout.ENDERCHEST_GUI_SIZE);

                    Object outList = tagOutputList.invoke(output, "EnderItems", itemStackWithSlotCodec);
                    enderChestStoreAsSlots.invoke(ec, outList);
                }

                nbtUtilsAddCurrentDataVersion.invoke(null, output);
                Object built = tagOutputBuildResult.invoke(output);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                nbtWriteCompressed.invoke(null, built, baos);
                return baos.toByteArray();
            } catch (IOException io) {
                throw io;
            } catch (ReflectiveOperationException e) {
                throw unwrapIo(e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Spigot 1.20.x legacy NBT backend
    // ---------------------------------------------------------------------------------------------
    private static final class LegacyBackend implements Backend {
        private final Object dataFixer;
        private final Object playerDataFixType;

        private final Method nbtReadCompressed;
        private final Method nbtWriteCompressed;
        private final Method dataFixUpdate;

        private final Method compoundGetInt;
        private final Method compoundPutInt;
        private final Method compoundGetList;
        private final Method compoundPutTag;
        private final Constructor<?> nbtListCtor;

        private final Constructor<?> playerInvCtor;
        private final Method playerInvLoad;
        private final Method playerInvSave;

        private final Constructor<?> enderChestCtor;
        private final Method enderChestLoad;
        private final Method enderChestSave;

        private final Constructor<?> craftInvCtor;

        LegacyBackend(Server server, Class<?> craftInvClass) {
            try {
                this.dataFixer = resolveDataFixer(server);

                Class<?> dataFixTypesClass = Class.forName("net.minecraft.util.datafix.DataFixTypes");
                // DataFixTypes.b is PLAYER on Spigot 1.20.1
                this.playerDataFixType = dataFixTypesClass.getField("b").get(null);

                Class<?> compoundClass = Class.forName("net.minecraft.nbt.NBTTagCompound");
                Class<?> listClass = Class.forName("net.minecraft.nbt.NBTTagList");
                Class<?> nbtBaseClass = Class.forName("net.minecraft.nbt.NBTBase");
                Class<?> compressedToolsClass = Class.forName("net.minecraft.nbt.NBTCompressedStreamTools");
                Class<?> fixerInterface = Class.forName("com.mojang.datafixers.DataFixer");

                this.nbtReadCompressed = compressedToolsClass.getMethod("a", InputStream.class);
                this.nbtWriteCompressed = compressedToolsClass.getMethod("a", compoundClass, OutputStream.class);
                this.dataFixUpdate = dataFixTypesClass.getMethod("a", fixerInterface, compoundClass, int.class);

                this.compoundGetInt = compoundClass.getMethod("h", String.class);
                this.compoundPutInt = compoundClass.getMethod("a", String.class, int.class);
                this.compoundGetList = compoundClass.getMethod("c", String.class, int.class);
                this.compoundPutTag = compoundClass.getMethod("a", String.class, nbtBaseClass);
                this.nbtListCtor = listClass.getConstructor();

                Class<?> entityHumanClass = Class.forName("net.minecraft.world.entity.player.EntityHuman");
                Class<?> playerInvClass = Class.forName("net.minecraft.world.entity.player.PlayerInventory");
                this.playerInvCtor = playerInvClass.getConstructor(entityHumanClass);
                this.playerInvLoad = playerInvClass.getMethod("b", listClass);
                this.playerInvSave = playerInvClass.getMethod("a", listClass);

                Class<?> enderChestClass = Class.forName("net.minecraft.world.inventory.InventoryEnderChest");
                this.enderChestCtor = enderChestClass.getConstructor(entityHumanClass);
                this.enderChestLoad = enderChestClass.getMethod("a", listClass);
                this.enderChestSave = enderChestClass.getMethod("g");

                Class<?> iInventoryClass = Class.forName("net.minecraft.world.IInventory");
                this.craftInvCtor = craftInvClass.getConstructor(iInventoryClass);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize Spigot 1.20 player data codec backend", e);
            }
        }

        private static Object resolveDataFixer(Server server) throws ReflectiveOperationException {
            Object playerList = server.getClass().getMethod("getHandle").invoke(server);
            Object storage = null;
            for (Class<?> type = playerList.getClass(); type != null && storage == null; type = type.getSuperclass()) {
                for (Field f : type.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        if (f.getType().getMethod("getPlayerDir").getReturnType() == java.io.File.class) {
                            f.setAccessible(true);
                            storage = f.get(playerList);
                            break;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (storage == null) {
                throw new IllegalStateException("Player storage field not found on " + playerList.getClass().getName());
            }
            Class<?> dataFixerClass = Class.forName("com.mojang.datafixers.DataFixer");
            for (Class<?> type = storage.getClass(); type != null; type = type.getSuperclass()) {
                for (Field f : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && dataFixerClass.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object fixer = f.get(storage);
                        if (fixer != null) {
                            return fixer;
                        }
                    }
                }
            }
            throw new IllegalStateException("DataFixer field not found on " + storage.getClass().getName());
        }

        private Object readAndUpgrade(byte[] compressedNbt, int currentVersion) throws IOException {
            try {
                Object root = nbtReadCompressed.invoke(null, new ByteArrayInputStream(compressedNbt));
                int fileVersion = (int) compoundGetInt.invoke(root, "DataVersion");
                if (fileVersion > currentVersion) {
                    throw new DataVersionTooNewException(fileVersion, currentVersion);
                }
                if (fileVersion > 0 && fileVersion < currentVersion) {
                    root = dataFixUpdate.invoke(playerDataFixType, dataFixer, root, fileVersion);
                }
                return root;
            } catch (IOException io) {
                throw io;
            } catch (ReflectiveOperationException e) {
                throw unwrapIo(e);
            }
        }

        @Override
        public LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type, int currentVersion) throws IOException {
            try {
                Object root = readAndUpgrade(compressedNbt, currentVersion);
                int fileVersion = (int) compoundGetInt.invoke(root, "DataVersion");

                if (type == InspectionLayout.InspectionType.INVSEE) {
                    Object list = compoundGetList.invoke(root, "Inventory", 10);
                    Object inv = playerInvCtor.newInstance(new Object[]{null});
                    playerInvLoad.invoke(inv, list);
                    Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(inv);
                    return new LoadedData(fileVersion, extractSlots(bukkitInv, InspectionLayout.INVSEE_PLAYER_SLOT_COUNT));
                }

                Object list = compoundGetList.invoke(root, "EnderItems", 10);
                Object ec = enderChestCtor.newInstance(new Object[]{null});
                enderChestLoad.invoke(ec, list);
                Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(ec);
                return new LoadedData(fileVersion, extractSlots(bukkitInv, InspectionLayout.ENDERCHEST_GUI_SIZE));
            } catch (IOException io) {
                throw io;
            } catch (ReflectiveOperationException e) {
                throw unwrapIo(e);
            }
        }

        @Override
        public byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots, int currentVersion) throws IOException {
            try {
                Object root = readAndUpgrade(baseCompressedNbt, currentVersion);
                if (type == InspectionLayout.InspectionType.INVSEE) {
                    Object inv = playerInvCtor.newInstance(new Object[]{null});
                    Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(inv);
                    applySlots(bukkitInv, playerSlots, InspectionLayout.INVSEE_PLAYER_SLOT_COUNT);
                    Object outList = playerInvSave.invoke(inv, nbtListCtor.newInstance());
                    compoundPutTag.invoke(root, "Inventory", outList);
                } else {
                    Object ec = enderChestCtor.newInstance(new Object[]{null});
                    Inventory bukkitInv = (Inventory) craftInvCtor.newInstance(ec);
                    applySlots(bukkitInv, playerSlots, InspectionLayout.ENDERCHEST_GUI_SIZE);
                    Object outList = enderChestSave.invoke(ec);
                    compoundPutTag.invoke(root, "EnderItems", outList);
                }
                compoundPutInt.invoke(root, "DataVersion", currentVersion);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                nbtWriteCompressed.invoke(null, root, baos);
                return baos.toByteArray();
            } catch (IOException io) {
                throw io;
            } catch (ReflectiveOperationException e) {
                throw unwrapIo(e);
            }
        }
    }
}
