package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.scheduler.SchedulerAdapter;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Player-facing pie behaviour:
 *
 * <ul>
 *   <li>right-click the top of a block with a crust or a whole pie to put it down</li>
 *   <li>right-click a crust with the next ingredient to add it</li>
 *   <li>right-click a whole pie to eat a slice</li>
 *   <li>punch it, or sneak and right-click, to pick it up</li>
 * </ul>
 */
final class PieListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final PieService pies;
    private final ItemResolverChain items;
    private final BuildPermission permission;

    PieListener(Plugin plugin, PieService pies, ItemResolverChain items, BuildPermission permission) {
        this.plugin = plugin;
        this.pies = pies;
        this.items = items;
        this.permission = permission;
    }

    // ==================== placing ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getBlockFace() != BlockFace.UP) return;
        KitchenConfig.Pies config = pies.pies();
        ItemStack held = event.getItem();
        Block clicked = event.getClickedBlock();
        if (config == null || held == null || clicked == null) return;
        Player player = event.getPlayer();
        // Same rule as placing a block: clicking a chest or door uses it
        // unless the player is sneaking.
        if (clicked.getType().isInteractable() && !player.isSneaking()) return;

        ItemRef ref = items.identify(held).orElse(null);
        if (ref == null) return;
        boolean crust = ref.equals(config.crust());
        Optional<Map.Entry<KitchenConfig.PieType, Boolean>> whole = crust ? Optional.empty() : config.byItem(ref);
        if (!crust && whole.isEmpty()) return;

        event.setCancelled(true);
        Block cell = clicked.getRelative(BlockFace.UP);
        if (!clicked.getType().isSolid() || !cell.getType().isAir() || pies.at(cell).isPresent()) {
            player.sendActionBar(MINI.deserialize("<red>There's no room to put that down here.</red>"));
            return;
        }
        if (!permission.canBuild(player, cell.getLocation())) return;

        float yaw = Math.round((player.getYaw() + 180.0F) / 90.0F) * 90.0F;
        if (crust) {
            pies.placeCrust(cell, yaw);
            player.sendActionBar(MINI.deserialize("<green>Crust down. Follow the steps above it.</green>"));
        } else {
            KitchenConfig.PieType type = whole.get().getKey();
            boolean baked = whole.get().getValue();
            long coolsAt = baked ? PieService.coolsAt(held) : 0L;
            // A pie that went cold in someone's pocket comes out cold.
            if (baked && coolsAt > 0L && coolsAt <= System.currentTimeMillis()) {
                baked = false;
                coolsAt = 0L;
            }
            pies.placePie(cell, yaw, type, baked, coolsAt);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        cell.getWorld().playSound(cell.getLocation().add(0.5D, 0.0D, 0.5D), "block.wool.place", 1.0F, 1.0F);
    }

    // ==================== clicking a pie ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractPie(PlayerInteractEntityEvent event) {
        if (!PieService.isPieHitbox(event.getRightClicked())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        PieService.Pie pie = pies.of(event.getRightClicked()).orElse(null);
        KitchenConfig.Pies config = pies.pies();
        if (pie == null || config == null) return;
        Player player = event.getPlayer();

        boolean eating = pie.phase == PieService.Phase.PIE && !player.isSneaking();
        if ((!eating || config.eating().requireBuildPermission())
                && !permission.canBuild(player, pie.blockCenter())) {
            return;
        }
        if (player.isSneaking()) {
            pies.pickup(player, pie);
            return;
        }
        if (pie.phase == PieService.Phase.PIE) {
            pies.eat(player, pie);
            return;
        }
        if (pie.assemblyDone(config)) {
            player.sendActionBar(MINI.deserialize("<gold>Done!</gold> <gray>Pick it up and bake it.</gray>"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!pies.addIngredient(player, pie, held)) {
            String need = pies.currentNeed(pie);
            if (need != null) {
                player.sendActionBar(MINI.deserialize("<gray>This needs <white>" + need + "</white> next.</gray>"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPunchPie(EntityDamageByEntityEvent event) {
        if (!PieService.isPieHitbox(event.getEntity())) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player)) return;
        pies.of(event.getEntity()).ifPresent(pie -> {
            if (permission.canBuild(player, pie.blockCenter())) {
                pies.pickup(player, pie);
            }
        });
    }

    // ==================== the world around a pie ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSupportBroken(BlockBreakEvent event) {
        pies.at(event.getBlock().getRelative(BlockFace.UP)).ifPresent(pies::breakPie);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIntoPie(BlockPlaceEvent event) {
        if (pies.at(event.getBlockPlaced()).isPresent()) {
            event.setCancelled(true);
        }
    }

    // ==================== loading and cooling ====================

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            pies.track(entity);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            pies.untrack(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        pies.track(event.getEntity());
    }

    /** Picks up pies in chunks that were already loaded before the kitchen started. */
    void bootstrapLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                SchedulerAdapter.runAtLocation(plugin, chunk.getBlock(8, 64, 8).getLocation(), () -> {
                    for (Entity entity : chunk.getEntities()) {
                        pies.track(entity);
                    }
                });
            }
        }
    }
}
