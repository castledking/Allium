package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Furniture that works as a furnace.
 *
 * <p>A stove owns a furnace inventory and runs vanilla's furnace rules on it:
 * fuel burns down, the item in the input slot cooks using the server's own
 * cooking recipes (so custom recipes from Nexo work too), and the result
 * collects in the output slot. Everything — the three slots, burn and cook
 * progress, and experience not yet collected — is stored on the furniture's
 * display entity, so a stove keeps cooking across restarts and only while
 * its chunk is loaded.
 *
 * <p>Each active stove ticks on its own entity task and stops when it has
 * nothing left to do.
 */
final class StoveService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int RESULT = 2;

    private final Plugin plugin;
    private final Supplier<KitchenConfig> config;
    private final Map<UUID, Stove> loaded = new ConcurrentHashMap<>();

    StoveService(Plugin plugin, Supplier<KitchenConfig> config) {
        this.plugin = plugin;
        this.config = config;
    }

    KitchenConfig.Stove settings(String furnitureId) {
        return furnitureId == null ? null : config.get().furnaces().get(furnitureId.toLowerCase(java.util.Locale.ROOT));
    }

    /** Opens a stove's furnace menu. */
    void open(Player player, ItemDisplay base, KitchenConfig.Stove settings) {
        Stove stove = stove(base, settings);
        player.openInventory(stove.inventory);
        stove.pushProgress();
        stove.ensureTicking();
    }

    /** Resumes a stove whose chunk just loaded, if it was in the middle of something. */
    void resume(ItemDisplay base, KitchenConfig.Stove settings) {
        if (!base.getPersistentDataContainer().has(KitchenKeys.STOVE_SLOTS, PersistentDataType.LIST.byteArrays())) {
            return;
        }
        Stove stove = stove(base, settings);
        if (stove.hasWork()) {
            stove.ensureTicking();
        } else {
            unload(stove);
        }
    }

    /** Saves and forgets a stove whose chunk is unloading. */
    void unload(UUID baseId) {
        Stove stove = loaded.get(baseId);
        if (stove != null) unload(stove);
    }

    /** Drops a broken stove's contents and stored experience where it stood. */
    void broken(ItemDisplay base) {
        Stove stove = loaded.remove(base.getUniqueId());
        if (stove == null) {
            KitchenConfig.Stove any = new KitchenConfig.Stove("", "Furnace", KitchenConfig.StoveType.FURNACE, 1.0D);
            stove = new Stove(base, any);
        }
        stove.stop();
        for (HumanEntity viewer : List.copyOf(stove.inventory.getViewers())) {
            viewer.closeInventory();
        }
        Location at = base.getLocation().toCenterLocation();
        for (ItemStack stack : stove.inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                at.getWorld().dropItemNaturally(at, stack);
            }
        }
        stove.inventory.clear();
        stove.dropExperience(at);
    }

    /** Called after any click in a stove menu, once the click has been applied. */
    void changed(Inventory inventory) {
        if (inventory.getHolder() instanceof Stove stove) {
            stove.save();
            stove.ensureTicking();
        }
    }

    /** Pays out a stove's stored experience to the player taking its output. */
    void collectExperience(Player player, Inventory inventory) {
        if (inventory.getHolder() instanceof Stove stove && stove.experience > 0.0F) {
            stove.dropExperience(player.getLocation());
            stove.save();
        }
    }

    static boolean isStove(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Stove;
    }

    void shutdown() {
        for (Stove stove : List.copyOf(loaded.values())) {
            for (HumanEntity viewer : List.copyOf(stove.inventory.getViewers())) {
                viewer.closeInventory();
            }
            unload(stove);
        }
    }

    private Stove stove(ItemDisplay base, KitchenConfig.Stove settings) {
        Stove stove = loaded.computeIfAbsent(base.getUniqueId(), id -> new Stove(base, settings));
        stove.settings = settings;
        return stove;
    }

    private void unload(Stove stove) {
        stove.stop();
        stove.save();
        loaded.remove(stove.base.getUniqueId());
    }

    // ==================== one stove ====================

    final class Stove implements InventoryHolder {
        final ItemDisplay base;
        final Inventory inventory;
        KitchenConfig.Stove settings;
        int burnTime;
        int burnTotal;
        int cookTime;
        int cookTotal;
        float experience;
        private TaskHandle task;
        private int ticks;
        private ItemStack cachedInput;
        private CookingRecipe<?> cachedRecipe;

        Stove(ItemDisplay base, KitchenConfig.Stove settings) {
            this.base = base;
            this.settings = settings;
            this.inventory = Bukkit.createInventory(this, InventoryType.FURNACE, MINI.deserialize(settings.title()));
            load();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void ensureTicking() {
            if (task == null) {
                task = SchedulerAdapter.runEntityRepeating(plugin, base, this::tick, this::stop, 1L, 1L);
            }
        }

        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }

        boolean hasWork() {
            return burnTime > 0 || (canCook() && fuelTicks(inventory.getItem(FUEL)) > 0);
        }

        private void tick() {
            if (!base.isValid()) {
                stop();
                return;
            }
            boolean wasBurning = burnTime > 0;
            if (burnTime > 0) {
                burnTime--;
            }
            boolean cookable = canCook();
            if (burnTime <= 0 && cookable) {
                int ticks = fuelTicks(inventory.getItem(FUEL));
                if (ticks > 0) {
                    burnTime = burnTotal = ticks;
                    consumeFuel();
                }
            }
            if (burnTime > 0 && cookable) {
                if (cookTotal <= 0) {
                    cookTotal = cookTimeFor(cachedRecipe);
                }
                if (++cookTime >= cookTotal) {
                    cookTime = 0;
                    smelt();
                    cookTotal = canCook() ? cookTimeFor(cachedRecipe) : 0;
                }
            } else if (cookTime > 0) {
                // Vanilla lets progress drain twice as fast when the fire goes out.
                cookTime = burnTime > 0 ? 0 : Math.max(0, cookTime - 2);
            }
            pushProgress();
            if (wasBurning != (burnTime > 0) || ++ticks % 20 == 0) {
                save();
            }
            if (burnTime <= 0 && cookTime <= 0 && inventory.getViewers().isEmpty()) {
                save();
                stop();
                loaded.remove(base.getUniqueId());
            }
        }

        private boolean canCook() {
            ItemStack input = inventory.getItem(INPUT);
            if (input == null || input.getType().isAir()) {
                cachedInput = null;
                cachedRecipe = null;
                return false;
            }
            if (cachedInput == null || !cachedInput.isSimilar(input)) {
                cachedInput = input.clone();
                cachedRecipe = findRecipe(input);
                cookTime = 0;
                cookTotal = cachedRecipe == null ? 0 : cookTimeFor(cachedRecipe);
            }
            if (cachedRecipe == null) return false;
            ItemStack result = cachedRecipe.getResult();
            ItemStack out = inventory.getItem(RESULT);
            if (out == null || out.getType().isAir()) return true;
            return out.isSimilar(result) && out.getAmount() + result.getAmount() <= out.getMaxStackSize();
        }

        private void smelt() {
            ItemStack input = inventory.getItem(INPUT);
            ItemStack result = cachedRecipe.getResult().clone();
            ItemStack out = inventory.getItem(RESULT);
            if (out == null || out.getType().isAir()) {
                inventory.setItem(RESULT, result);
            } else {
                out.setAmount(out.getAmount() + result.getAmount());
            }
            experience += cachedRecipe.getExperience();
            input.setAmount(input.getAmount() - 1);
            // A wet sponge also fills an empty bucket in the fuel slot, as in vanilla.
            if (input.getType() == Material.WET_SPONGE) {
                ItemStack fuel = inventory.getItem(FUEL);
                if (fuel != null && fuel.getType() == Material.BUCKET && fuel.getAmount() == 1) {
                    inventory.setItem(FUEL, new ItemStack(Material.WATER_BUCKET));
                }
            }
            inventory.setItem(INPUT, input.getAmount() > 0 ? input : null);
            save();
        }

        private void consumeFuel() {
            ItemStack fuel = inventory.getItem(FUEL);
            if (fuel.getType() == Material.LAVA_BUCKET) {
                inventory.setItem(FUEL, new ItemStack(Material.BUCKET));
                return;
            }
            fuel.setAmount(fuel.getAmount() - 1);
            inventory.setItem(FUEL, fuel.getAmount() > 0 ? fuel : null);
        }

        private int cookTimeFor(CookingRecipe<?> recipe) {
            return recipe == null ? 0 : Math.max(1, (int) Math.round(recipe.getCookingTime() * settings.speed()));
        }

        private CookingRecipe<?> findRecipe(ItemStack input) {
            Class<?> wanted = switch (settings.type()) {
                case FURNACE -> FurnaceRecipe.class;
                case SMOKER -> SmokingRecipe.class;
                case BLAST_FURNACE -> BlastingRecipe.class;
            };
            Iterator<Recipe> recipes = Bukkit.recipeIterator();
            while (recipes.hasNext()) {
                Recipe recipe = recipes.next();
                if (wanted.isInstance(recipe) && recipe instanceof CookingRecipe<?> cooking
                        && cooking.getInputChoice().test(input)) {
                    return cooking;
                }
            }
            return null;
        }

        /** Sends burn and cook progress to everyone looking at the menu. */
        void pushProgress() {
            for (HumanEntity viewer : inventory.getViewers()) {
                InventoryView view = viewer.getOpenInventory();
                if (view.getTopInventory() != inventory) continue;
                view.setProperty(InventoryView.Property.BURN_TIME, burnTime);
                view.setProperty(InventoryView.Property.TICKS_FOR_CURRENT_FUEL, Math.max(1, burnTotal));
                view.setProperty(InventoryView.Property.COOK_TIME, cookTime);
                view.setProperty(InventoryView.Property.TICKS_FOR_CURRENT_SMELTING, Math.max(1, cookTotal));
            }
        }

        void dropExperience(Location at) {
            int orbs = (int) Math.floor(experience);
            // Vanilla rounds the fractional part up at random.
            if (Math.random() < experience - orbs) orbs++;
            experience = 0.0F;
            if (orbs > 0) {
                int total = orbs;
                at.getWorld().spawn(at, ExperienceOrb.class, orb -> orb.setExperience(total));
            }
        }

        // ==================== persistence ====================

        private void load() {
            PersistentDataContainer pdc = base.getPersistentDataContainer();
            List<byte[]> slots = pdc.get(KitchenKeys.STOVE_SLOTS, PersistentDataType.LIST.byteArrays());
            if (slots != null) {
                for (int i = 0; i < Math.min(3, slots.size()); i++) {
                    byte[] bytes = slots.get(i);
                    inventory.setItem(i, bytes.length == 0 ? null : ItemStack.deserializeBytes(bytes));
                }
            }
            int[] progress = pdc.get(KitchenKeys.STOVE_PROGRESS, PersistentDataType.INTEGER_ARRAY);
            if (progress != null && progress.length == 4) {
                burnTime = progress[0];
                burnTotal = progress[1];
                cookTime = progress[2];
                cookTotal = progress[3];
            }
            experience = pdc.getOrDefault(KitchenKeys.STOVE_EXPERIENCE, PersistentDataType.FLOAT, 0.0F);
        }

        void save() {
            if (!base.isValid()) return;
            PersistentDataContainer pdc = base.getPersistentDataContainer();
            byte[][] slots = new byte[3][];
            boolean empty = true;
            for (int i = 0; i < 3; i++) {
                ItemStack stack = inventory.getItem(i);
                boolean none = stack == null || stack.getType().isAir();
                slots[i] = none ? new byte[0] : stack.serializeAsBytes();
                empty &= none;
            }
            if (empty && burnTime <= 0 && experience <= 0.0F) {
                pdc.remove(KitchenKeys.STOVE_SLOTS);
                pdc.remove(KitchenKeys.STOVE_PROGRESS);
                pdc.remove(KitchenKeys.STOVE_EXPERIENCE);
                return;
            }
            pdc.set(KitchenKeys.STOVE_SLOTS, PersistentDataType.LIST.byteArrays(), List.of(slots));
            pdc.set(KitchenKeys.STOVE_PROGRESS, PersistentDataType.INTEGER_ARRAY,
                new int[] {burnTime, burnTotal, cookTime, cookTotal});
            pdc.set(KitchenKeys.STOVE_EXPERIENCE, PersistentDataType.FLOAT, experience);
        }
    }

    // ==================== fuel ====================

    /**
     * Burn time in ticks, following vanilla's fuel values. Items the furnace
     * menu accepts as fuel but that aren't listed here burn like a plank.
     */
    static int fuelTicks(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0;
        Material m = stack.getType();
        switch (m) {
            case LAVA_BUCKET: return 20000;
            case COAL_BLOCK: return 16000;
            case DRIED_KELP_BLOCK: return 4001;
            case BLAZE_ROD: return 2400;
            case COAL: case CHARCOAL: return 1600;
            case BOOKSHELF: case CHISELED_BOOKSHELF: case CRAFTING_TABLE: case CARTOGRAPHY_TABLE:
            case FLETCHING_TABLE: case SMITHING_TABLE: case LOOM: case LECTERN: case COMPOSTER:
            case BARREL: case CHEST: case TRAPPED_CHEST: case JUKEBOX: case NOTE_BLOCK:
            case DAYLIGHT_DETECTOR: case BAMBOO_BLOCK: case STRIPPED_BAMBOO_BLOCK: case BAMBOO_MOSAIC:
            case BAMBOO_MOSAIC_STAIRS: case LADDER:
                return 300;
            case BOW: case CROSSBOW: case FISHING_ROD: case WOODEN_SWORD: case WOODEN_SHOVEL:
            case WOODEN_PICKAXE: case WOODEN_AXE: case WOODEN_HOE: case BOWL: case STICK:
                return m == Material.STICK || m == Material.BOWL ? 100 : 200;
            case BAMBOO: case SCAFFOLDING: return 50;
            case BAMBOO_MOSAIC_SLAB: return 150;
            default: break;
        }
        if (Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.WOODEN_STAIRS.isTagged(m)
                || Tag.WOODEN_FENCES.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.WOODEN_TRAPDOORS.isTagged(m) || Tag.WOODEN_PRESSURE_PLATES.isTagged(m)
                || Tag.BANNERS.isTagged(m)) {
            return 300;
        }
        if (Tag.WOODEN_SLABS.isTagged(m)) return 150;
        if (Tag.ITEMS_BOATS.isTagged(m) || Tag.ITEMS_CHEST_BOATS.isTagged(m)) return 1200;
        if (Tag.WOODEN_DOORS.isTagged(m) || Tag.ALL_SIGNS.isTagged(m)) return 200;
        if (Tag.SAPLINGS.isTagged(m) || Tag.WOODEN_BUTTONS.isTagged(m) || Tag.WOOL.isTagged(m)) return 100;
        if (Tag.WOOL_CARPETS.isTagged(m)) return 67;
        return m.isFuel() ? 300 : 0;
    }
}
