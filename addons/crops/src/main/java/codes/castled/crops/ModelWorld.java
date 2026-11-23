package codes.castled.crops;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The persistent interaction entity owns the state. Displays are rebuilt whenever its chunk loads. */
final class ModelWorld implements Listener {
    record Cell(UUID world, int x, int y, int z) {
        static Cell of(Location loc) { return new Cell(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()); }
    }

    /** One look of a hologram: text lines above and below an item icon (null: no icon). */
    record Frame(List<String> above, String icon, List<String> below) {}

    /** Text display line height at scale 1, and the size the hologram icon is drawn at. */
    private static final double LINE = .25, ICON = .5;

    static final class Placed {
        final Interaction hitbox;
        final Cell cell;
        final String kind;
        String type;
        String path;
        int stage;
        long due;
        String fertilizer = "";
        double growthSpeed = 1;
        long lastAttack;
        volatile boolean active = true;
        ItemDisplay display;
        Catalog.Visual visual;
        List<Frame> frames = List.of();
        int frame = -1;
        TextDisplay above, below;
        ItemDisplay icon;

        Placed(Interaction hitbox, String kind, String type, String path, int stage, long due) {
            this.hitbox = hitbox;
            this.cell = Cell.of(hitbox.getLocation());
            this.kind = kind;
            this.type = type;
            this.path = path;
            this.stage = stage;
            this.due = due;
            var attack = hitbox.getLastAttack();
            lastAttack = attack == null ? -1 : attack.getTimestamp();
        }

        Block cell() { return hitbox.getLocation().getBlock(); }
    }

    private final CropsPlugin plugin;
    private final NamespacedKey key;
    private final Map<Cell, Placed> cells = new ConcurrentHashMap<>();
    private final Map<UUID, Placed> hitboxes = new ConcurrentHashMap<>();
    private Consumer<Placed> onLoad;

    ModelWorld(CropsPlugin plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "placement");
    }

    void start(Consumer<Placed> onLoad) {
        this.onLoad = onLoad;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.connection().isFolia()) {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    int cx = chunk.getX(), cz = chunk.getZ();
                    plugin.connection().onRegion(world, cx, cz, () -> {
                        if (!world.isChunkLoaded(cx, cz)) return;
                        for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                            if (entity instanceof Interaction interaction && interaction.isValid()) attach(interaction);
                        }
                    });
                }
            }
        } else {
            for (World world : Bukkit.getWorlds()) world.getEntitiesByClass(Interaction.class).forEach(this::attach);
        }
    }

    Placed at(Block block) { return cells.get(Cell.of(block.getLocation())); }
    Placed at(Cell cell) { return cells.get(cell); }
    Placed get(Entity entity) { return hitboxes.get(entity.getUniqueId()); }
    Collection<Placed> active() { return List.copyOf(hitboxes.values()); }

    Placed place(Block cell, String kind, String type, String path, int stage, long due, Catalog.Visual visual) {
        if (!cell.getType().isAir() || at(cell) != null) return null;
        Location center = cell.getLocation().add(.5, 0, .5);
        Interaction hitbox = center.getWorld().spawn(center, Interaction.class, interaction -> {
            interaction.setInteractionWidth(.9F);
            interaction.setInteractionHeight(.9F);
            interaction.setResponsive(true);
        });
        Placed state = new Placed(hitbox, kind, type, path, stage, due);
        if (!show(state, visual)) {
            hitbox.remove();
            return null;
        }
        store(state);
        remember(state);
        return state;
    }

    void store(Placed placed) {
        String data = String.join("|", placed.kind, placed.type, placed.path,
                Integer.toString(placed.stage), Long.toString(placed.due), placed.fertilizer,
                Double.toString(placed.growthSpeed));
        placed.hitbox.getPersistentDataContainer().set(key, PersistentDataType.STRING, data);
    }

    boolean show(Placed placed, Catalog.Visual visual) {
        if (visual.equals(placed.visual) && placed.display != null && placed.display.isValid()) return true;
        var item = plugin.item(visual.item(), 1);
        if (item == null) return false;
        var cell = placed.cell();
        var soil = cell.getRelative(org.bukkit.block.BlockFace.DOWN);
        double height = visual.align() ? soil.getBoundingBox().getMaxY() - cell.getY() : 0;
        var center = cell.getLocation().add(.5, .5, .5);
        if (placed.display == null || !placed.display.isValid()) {
            placed.display = center.getWorld().spawn(center, ItemDisplay.class, spawned -> spawned.setPersistent(false));
        }
        placed.display.setItemStack(item);
        placed.display.setItemDisplayTransform(visual.transform());
        placed.display.setTransformation(new Transformation(
                new Vector3f(0, (float) (height + visual.offset() - .5), 0), new Quaternionf(),
                new Vector3f(visual.scale()), new Quaternionf()));
        placed.visual = visual;
        if (placed.kind.equals("stove")) {
            placed.hitbox.setInteractionWidth(0);
            placed.hitbox.setInteractionHeight(0);
        } else placed.hitbox.setInteractionHeight(placed.kind.equals("pie") ? .3F : Math.min(2, visual.scale()));
        return true;
    }

    /**
     * Shows {@code frames} as a hologram whose top is {@code height} above the cell's floor, one
     * frame at a time, each for {@code cycle} milliseconds. No frames removes it.
     */
    void label(Placed placed, List<Frame> frames, double height, long cycle) {
        if (frames.isEmpty()) {
            clearLabel(placed);
            return;
        }
        if (!frames.equals(placed.frames)) {
            placed.frames = List.copyOf(frames);
            placed.frame = -1;
        }
        int index = frames.size() == 1 ? 0 : (int) (System.currentTimeMillis() / Math.max(50, cycle) % frames.size());
        if (index == placed.frame && labelShown(placed)) return;
        Frame frame = frames.get(index);
        var at = placed.cell().getLocation().add(.5, height, .5);
        at.subtract(0, frame.above().size() * LINE, 0);
        placed.above = text(placed.above, at, frame.above());
        ItemStack item = frame.icon() == null ? null : plugin.item(frame.icon(), 1);
        if (item != null) at.subtract(0, ICON, 0);
        placed.icon = icon(placed.icon, at.clone().add(0, ICON / 2, 0), item);
        at.subtract(0, frame.below().size() * LINE, 0);
        placed.below = text(placed.below, at, frame.below());
        placed.frame = index;
    }

    private static boolean labelShown(Placed placed) {
        for (Display part : new Display[]{placed.above, placed.icon, placed.below}) {
            if (part != null && !part.isValid()) return false;
        }
        return true;
    }

    private TextDisplay text(TextDisplay shown, Location at, List<String> lines) {
        if (lines.isEmpty()) {
            if (shown != null) shown.remove();
            return null;
        }
        boolean reused = shown != null && shown.isValid();
        TextDisplay display = reused ? shown : at.getWorld().spawn(at, TextDisplay.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
        });
        if (reused) plugin.connection().teleport(display, at, TeleportCause.PLUGIN);
        display.setText(String.join("\n", lines));
        return display;
    }

    private ItemDisplay icon(ItemDisplay shown, Location at, ItemStack item) {
        if (item == null) {
            if (shown != null) shown.remove();
            return null;
        }
        boolean reused = shown != null && shown.isValid();
        ItemDisplay display = reused ? shown : at.getWorld().spawn(at, ItemDisplay.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setBillboard(Display.Billboard.CENTER);
            // GUI: the item as it looks in an inventory, which is the icon the pack gives Kitchen models.
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            spawned.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f((float) ICON), new Quaternionf()));
        });
        if (reused) plugin.connection().teleport(display, at, TeleportCause.PLUGIN);
        display.setItemStack(item);
        return display;
    }

    private static void clearLabel(Placed placed) {
        for (Display part : new Display[]{placed.above, placed.icon, placed.below}) if (part != null) part.remove();
        placed.above = placed.below = null;
        placed.icon = null;
        placed.frames = List.of();
        placed.frame = -1;
    }

    void remove(Placed placed) {
        forget(placed);
        if (!plugin.connection().isFolia() || plugin.connection().ownsEntity(placed.hitbox)) {
            placed.hitbox.remove();
        }
    }

    private void remember(Placed placed) {
        cells.put(placed.cell, placed);
        hitboxes.put(placed.hitbox.getUniqueId(), placed);
    }

    void forgetRetired(Placed placed) {
        placed.active = false;
        cells.remove(placed.cell, placed);
        hitboxes.remove(placed.hitbox.getUniqueId());
    }

    private void forget(Placed placed) {
        placed.active = false;
        cells.remove(placed.cell, placed);
        hitboxes.remove(placed.hitbox.getUniqueId());
        if (!plugin.connection().isFolia() || plugin.connection().ownsEntity(placed.hitbox)) {
            if (placed.display != null) placed.display.remove();
            clearLabel(placed);
        } else {
            placed.display = null;
            placed.above = placed.below = null;
            placed.icon = null;
            placed.frames = List.of();
            placed.frame = -1;
        }
    }

    private void attach(Interaction entity) {
        String raw = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null || get(entity) != null) return;
        try {
            String[] data = raw.split("\\|", -1);
            if (data.length != 5 && data.length != 7) throw new IllegalArgumentException();
            var state = new Placed(entity, data[0], data[1], data[2], Integer.parseInt(data[3]), Long.parseLong(data[4]));
            if (data.length == 7) {
                state.fertilizer = data[5];
                state.growthSpeed = Double.parseDouble(data[6]);
                if (!Double.isFinite(state.growthSpeed) || state.growthSpeed <= 0) throw new IllegalArgumentException();
            }
            if (at(state.cell()) != null) return;
            remember(state);
            onLoad.accept(state);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Invalid placement data on " + entity.getUniqueId());
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        // The world finishes adding the restored interaction entities before spawning their views.
        Chunk chunk = event.getChunk();
        List<Entity> entities = List.copyOf(event.getEntities());
        plugin.connection().runForRegion(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
            for (Entity entity : entities) if (entity instanceof Interaction interaction && interaction.isValid()) attach(interaction);
        }, 0L);
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            Placed placed = get(entity);
            if (placed != null) forget(placed);
        }
    }

    void stop() { active().forEach(this::forget); }
}
