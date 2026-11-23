package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonPowerTools;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.Set;

final class SpawnerChanger implements Listener {
    private static final Set<String> RESTRICTED = Set.of(
            "villager", "zombie_villager", "wandering_trader", "mooshroom", "cow", "sheep", "chicken",
            "spider", "zombie", "skeleton", "iron_golem", "enderman", "slime", "magma_cube", "squid",
            "glow_squid", "pig", "blaze", "creeper");

    private final SpawnerCraft plugin;

    SpawnerChanger(SpawnerCraft plugin) {
        this.plugin = plugin;
    }

    boolean activate(AddonPowerTools.Context context, String value, Map<String, Object> parameters) {
        Block block = context.block();
        Player player = context.player();
        if (block == null || !(block.getState() instanceof CreatureSpawner spawner)
                || !(context.cause() instanceof PlayerInteractEvent event)
                || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY
                || player.getGameMode() == GameMode.SPECTATOR) return false;
        var tools = plugin.connection().powerTools();
        if (!tools.mayModify(player, block, "change_spawner", event)) return false;
        event.setCancelled(true);
        int toolSlot = context.hand().equals("off") ? 40 : player.getInventory().getHeldItemSlot();
        var menu = new Menu(block, spawner.getSpawnedType(), toolSlot, context.item().clone());
        menu.inventory = Bukkit.createInventory(menu, 9, "Select a Spawn Egg");
        menu.inventory.setItem(4, SpawnerCraft.label(Material.PAPER, "§eSelect an egg from your inventory"));
        player.openInventory(menu.inventory);
        return true;
    }

    static boolean isMenu(Inventory inventory) { return inventory.getHolder() instanceof Menu; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || !(event.getWhoClicked() instanceof Player player) || !SpawnerCraft.ordinaryClick(event)) return;
        if (event.getClickedInventory() == player.getInventory()) {
            ItemStack egg = event.getCurrentItem();
            EntityType type = eggType(egg);
            if (type == null) return;
            if (!allowed(player, type)) {
                player.sendMessage("§cYou cannot change a spawner to that mob.");
                return;
            }
            menu.egg = egg.clone();
            menu.egg.setAmount(1);
            menu.eggSlot = event.getSlot();
            menu.type = type;
            menu.inventory.setItem(4, menu.egg.clone());
            menu.inventory.setItem(6, SpawnerCraft.label(Material.LIME_CONCRETE, "§aConfirm Spawner Change"));
        } else if (event.getRawSlot() == 6 && menu.type != null && !menu.confirming) {
            menu.confirming = true;
            plugin.connection().runForEntity(player, () -> confirm(player, menu), () -> {}, 0L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (isMenu(event.getView().getTopInventory())) event.setCancelled(true);
    }

    private void confirm(Player player, Menu menu) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory) return;
        player.closeInventory();
        Block block = menu.block;
        PlayerInventory inv = player.getInventory();
        ItemStack tool = inv.getItem(menu.toolSlot);
        ItemStack egg = inv.getItem(menu.eggSlot);
        if (!player.getWorld().equals(block.getWorld()) || player.getLocation().distanceSquared(block.getLocation().add(.5, .5, .5)) > 36
                || tool == null || !menu.tool.isSimilar(tool) || egg == null || !menu.egg.isSimilar(egg)
                || !player.hasPermission("spawnercraft.changer.use") || !allowed(player, menu.type)
                || !plugin.connection().canEditInventory(player)) {
            player.sendMessage("§cThe spawner change is no longer available.");
            return;
        }
        if (menu.type == menu.original) {
            player.sendMessage("§eThis spawner already spawns that mob.");
            return;
        }
        if (plugin.connection().ownsRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)) {
            if (!(block.getState() instanceof CreatureSpawner spawner) || spawner.getSpawnedType() != menu.original
                    || !plugin.connection().powerTools().mayModify(player, block, "change_spawner", null)) {
                player.sendMessage("§cThe spawner change is no longer available.");
                return;
            }
            spawner.setSpawnedType(menu.type);
            if (!spawner.update(false, false)) return;
            consume(inv, menu.eggSlot, egg);
            consume(inv, menu.toolSlot, tool);
            player.sendMessage("§aSpawner changed to " + Heads.title(menu.type.getKey().getKey()) + ".");
            return;
        }
        // Cross-region border on Folia: take one egg and one tool on the player's region, mutate the spawner on its
        // chunk region, and refund on the player's region if the spawner changed or protection denied it.
        ItemStack spentEgg = egg.clone();
        spentEgg.setAmount(1);
        ItemStack spentTool = tool.clone();
        spentTool.setAmount(1);
        consume(inv, menu.eggSlot, egg);
        consume(inv, menu.toolSlot, tool);
        plugin.connection().runForRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, () -> {
            boolean updated = block.getState() instanceof CreatureSpawner spawner
                    && spawner.getSpawnedType() == menu.original
                    && plugin.connection().powerTools().mayModify(player, block, "change_spawner", null);
            if (updated) {
                CreatureSpawner spawner = (CreatureSpawner) block.getState();
                spawner.setSpawnedType(menu.type);
                updated = spawner.update(false, false);
            }
            boolean success = updated;
            plugin.connection().runForEntity(player, () -> {
                if (success) {
                    if (player.isOnline()) {
                        player.sendMessage("§aSpawner changed to " + Heads.title(menu.type.getKey().getKey()) + ".");
                    }
                } else {
                    refund(player, spentEgg);
                    refund(player, spentTool);
                    if (player.isOnline()) player.sendMessage("§cThe spawner change is no longer available.");
                }
            }, () -> {}, 0L);
        }, 0L);
    }

    private static void refund(Player player, ItemStack item) {
        if (!player.isOnline()) return;
        player.getInventory().addItem(item).values().forEach(extra ->
                player.getWorld().dropItemNaturally(player.getLocation(), extra));
    }

    private static void consume(PlayerInventory inventory, int slot, ItemStack item) {
        if (item.getAmount() <= 1) inventory.setItem(slot, null);
        else {
            item.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, item);
        }
    }

    private static boolean allowed(Player player, EntityType type) {
        String mob = type.getKey().getKey();
        return !RESTRICTED.contains(mob) || player.hasPermission("allium.spawnerchanger.any")
                || player.hasPermission("allium.spawnerchanger.use." + mob);
    }

    private static EntityType eggType(ItemStack egg) {
        if (egg == null || !egg.getType().name().endsWith("_SPAWN_EGG")) return null;
        String material = egg.getType().name().replaceFirst("_SPAWN_EGG$", "");
        EntityType type = null;
        // The enum historically used MUSHROOM_COW and PIG_ZOMBIE on older servers.
        for (EntityType candidate : EntityType.values()) {
            if (candidate == EntityType.UNKNOWN || !candidate.isAlive() || !candidate.isSpawnable()) continue;
            if (candidate.getKey().getKey().equalsIgnoreCase(material)) {
                type = candidate;
                break;
            }
        }
        return type;
    }

    private static final class Menu implements InventoryHolder {
        private final Block block;
        private final EntityType original;
        private final int toolSlot;
        private final ItemStack tool;
        private Inventory inventory;
        private ItemStack egg;
        private int eggSlot;
        private EntityType type;
        private boolean confirming;

        private Menu(Block block, EntityType original, int toolSlot, ItemStack tool) {
            this.block = block;
            this.original = original;
            this.toolSlot = toolSlot;
            this.tool = tool;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
