package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.harvest.item.ItemResolverChain;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

/**
 * The kneading space: water plus flour turns into dough.
 *
 * <p>A cauldron keeps its water the vanilla way — buckets and bottles fill it
 * as usual and its level is the water count, so there is nothing to get out of
 * sync. Other stations (Nexo furniture or blocks) have no water of their own,
 * so buckets and bottles poured into them are counted instead. Flour, and
 * the water count of non-cauldron stations, are kept in the chunk's
 * PersistentDataContainer under the station's block position.
 */
final class KneadingService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Supplier<KitchenConfig> config;
    private final ItemResolverChain items;
    private final BagListener bags;

    KneadingService(Supplier<KitchenConfig> config, ItemResolverChain items, BagListener bags) {
        this.config = config;
        this.items = items;
        this.bags = bags;
    }

    KitchenConfig.Kneading settings() {
        return config.get().kneading();
    }

    boolean isStationBlock(Block block) {
        KitchenConfig.Kneading k = settings();
        return k != null && k.blocks().contains(block.getType());
    }

    boolean isNexoStation(String nexoId) {
        KitchenConfig.Kneading k = settings();
        return k != null && nexoId != null && k.nexoStations().contains(nexoId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Handles a right-click on a station.
     *
     * @param station the station's block; for furniture, the block its base stands in
     * @return true if the click was consumed and vanilla handling should be cancelled
     */
    boolean interact(Player player, Block station, EquipmentSlot hand) {
        KitchenConfig.Kneading k = settings();
        if (k == null) return false;
        ItemStack held = player.getInventory().getItem(hand);
        boolean cauldron = isCauldron(station);
        State state = read(station);

        if (!cauldron && held != null) {
            int units = held.getType() == Material.WATER_BUCKET ? k.bucketUnits()
                : isWaterBottle(held) ? k.bottleUnits() : 0;
            if (units > 0) {
                if (state.water >= k.waterRequired()) {
                    status(player, k, state, cauldron, station, "<yellow>There is already enough water.</yellow>");
                    return true;
                }
                state.water = Math.min(k.waterRequired(), state.water + units);
                emptyContainer(player, hand, held);
                station.getWorld().playSound(center(station), held.getType() == Material.WATER_BUCKET
                    ? "item.bucket.empty" : "item.bottle.empty", 1.0F, 1.0F);
                return finish(player, k, station, state, cauldron);
            }
        }

        if (held != null && (items.matches(k.flour(), held) || bags.isBagOf(held, k.flour()))) {
            if (state.flour >= k.flourRequired()) {
                status(player, k, state, cauldron, station, "<yellow>There is already enough flour.</yellow>");
                return true;
            }
            boolean fromBag = !items.matches(k.flour(), held);
            if (fromBag) {
                if (!bags.takeOne(held, k.flour())) {
                    player.sendActionBar(MINI.deserialize("<red>The bag is empty.</red>"));
                    return true;
                }
            } else if (player.getGameMode() != GameMode.CREATIVE) {
                held.setAmount(held.getAmount() - 1);
            }
            state.flour++;
            station.getWorld().playSound(center(station), "block.sand.place", 0.8F, 1.2F);
            return finish(player, k, station, state, cauldron);
        }

        if (held == null || held.getType().isAir()) {
            status(player, k, state, cauldron, station, null);
            return true;
        }
        return false;
    }

    /** Pops out dough once everything is in, otherwise shows progress. */
    private boolean finish(Player player, KitchenConfig.Kneading k, Block station, State state, boolean cauldron) {
        int water = cauldron ? cauldronLevel(station) : state.water;
        if (water >= k.waterRequired() && state.flour >= k.flourRequired()) {
            if (cauldron) {
                setCauldronLevel(station, cauldronLevel(station) - k.waterRequired());
            }
            state.water = 0;
            state.flour = 0;
            write(station, state);
            items.create(k.result(), k.resultAmount()).ifPresent(dough -> {
                Location top = center(station).add(0.0D, 0.6D, 0.0D);
                station.getWorld().dropItem(top, dough, item -> item.setVelocity(new Vector(0.0D, 0.2D, 0.0D)));
            });
            station.getWorld().playSound(center(station), k.sound(), 1.0F, 1.0F);
            player.sendActionBar(MINI.deserialize("<green>Dough's ready!</green>"));
            return true;
        }
        write(station, state);
        status(player, k, state, cauldron, station, null);
        return true;
    }

    private void status(Player player, KitchenConfig.Kneading k, State state, boolean cauldron,
                        Block station, String prefix) {
        int water = cauldron ? cauldronLevel(station) : state.water;
        String line = "<aqua>Water <white>" + Math.min(water, k.waterRequired()) + "/" + k.waterRequired()
            + "</white></aqua> <dark_gray>·</dark_gray> <gold>Flour <white>" + state.flour + "/"
            + k.flourRequired() + "</white></gold>";
        player.sendActionBar(MINI.deserialize(prefix == null ? line : prefix + " " + line));
    }

    // ==================== water ====================

    private static boolean isCauldron(Block block) {
        return block.getType() == Material.CAULDRON || block.getType() == Material.WATER_CAULDRON;
    }

    private static int cauldronLevel(Block block) {
        return block.getType() == Material.WATER_CAULDRON && block.getBlockData() instanceof Levelled levelled
            ? levelled.getLevel() : 0;
    }

    private static void setCauldronLevel(Block block, int level) {
        if (level <= 0) {
            block.setType(Material.CAULDRON);
            return;
        }
        if (block.getBlockData() instanceof Levelled levelled) {
            levelled.setLevel(Math.min(levelled.getMaximumLevel(), level));
            block.setBlockData(levelled);
        }
    }

    private static boolean isWaterBottle(ItemStack stack) {
        return stack.getType() == Material.POTION && stack.getItemMeta() instanceof PotionMeta meta
            && meta.getBasePotionType() == PotionType.WATER;
    }

    private static void emptyContainer(Player player, EquipmentSlot hand, ItemStack held) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack empty = new ItemStack(held.getType() == Material.WATER_BUCKET ? Material.BUCKET : Material.GLASS_BOTTLE);
        if (held.getAmount() <= 1) {
            player.getInventory().setItem(hand, empty);
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().addItem(empty).values()
            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    // ==================== state ====================

    private static final class State {
        int water;
        int flour;
    }

    private static State read(Block block) {
        State state = new State();
        String raw = block.getChunk().getPersistentDataContainer()
            .get(KitchenKeys.knead(block.getX(), block.getY(), block.getZ()), PersistentDataType.STRING);
        if (raw != null) {
            String[] parts = raw.split(",");
            try {
                state.water = Integer.parseInt(parts[0]);
                state.flour = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            } catch (NumberFormatException ignored) {
                // Corrupt entry: start over.
            }
        }
        return state;
    }

    private static void write(Block block, State state) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        var key = KitchenKeys.knead(block.getX(), block.getY(), block.getZ());
        if (state.water <= 0 && state.flour <= 0) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.STRING, state.water + "," + state.flour);
        }
    }

    /** Forgets a station's progress, e.g. when the block is broken. */
    static void clear(Block block) {
        block.getChunk().getPersistentDataContainer()
            .remove(KitchenKeys.knead(block.getX(), block.getY(), block.getZ()));
    }

    private static Location center(Block block) {
        return block.getLocation().add(0.5D, 0.5D, 0.5D);
    }
}
