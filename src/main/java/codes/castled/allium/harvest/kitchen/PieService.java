package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.scheduler.SchedulerAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Placed pies: from an empty crust through assembly, then baked or cold, then
 * eaten a slice at a time.
 *
 * <p>A pie is an {@link ItemDisplay} showing the current model plus an
 * {@link Interaction} hitbox so it can be clicked. Both are persistent
 * entities and all of the pie's state lives in the display's
 * PersistentDataContainer — there is no database row to fall out of sync with,
 * and a pie is saved and unloaded with its chunk like any other entity.
 */
final class PieService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String DISPLAY = "display";
    private static final String HITBOX = "hitbox";

    enum Phase { ASSEMBLY, PIE }

    /** A resolved pie: its entities plus the state read from the display. */
    final class Pie {
        final ItemDisplay display;
        Phase phase;
        int step;
        int progress;
        String type;
        List<String> refunds;
        boolean baked;
        int bites;
        long coolsAt;

        private Pie(ItemDisplay display) {
            this.display = display;
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            this.phase = "PIE".equals(pdc.get(KitchenKeys.PIE_PHASE, PersistentDataType.STRING))
                ? Phase.PIE : Phase.ASSEMBLY;
            this.step = pdc.getOrDefault(KitchenKeys.PIE_STEP, PersistentDataType.INTEGER, 0);
            this.progress = pdc.getOrDefault(KitchenKeys.PIE_PROGRESS, PersistentDataType.INTEGER, 0);
            this.type = pdc.get(KitchenKeys.PIE_TYPE, PersistentDataType.STRING);
            String refundRaw = pdc.get(KitchenKeys.PIE_REFUNDS, PersistentDataType.STRING);
            this.refunds = new ArrayList<>(refundRaw == null || refundRaw.isEmpty()
                ? List.of() : List.of(refundRaw.split(";")));
            this.baked = pdc.getOrDefault(KitchenKeys.PIE_BAKED, PersistentDataType.BYTE, (byte) 0) == 1;
            this.bites = pdc.getOrDefault(KitchenKeys.PIE_BITES, PersistentDataType.INTEGER, 0);
            this.coolsAt = pdc.getOrDefault(KitchenKeys.COOLS_AT, PersistentDataType.LONG, 0L);
        }

        void save() {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            pdc.set(KitchenKeys.PIE_PHASE, PersistentDataType.STRING, phase.name());
            pdc.set(KitchenKeys.PIE_STEP, PersistentDataType.INTEGER, step);
            pdc.set(KitchenKeys.PIE_PROGRESS, PersistentDataType.INTEGER, progress);
            if (type == null) pdc.remove(KitchenKeys.PIE_TYPE);
            else pdc.set(KitchenKeys.PIE_TYPE, PersistentDataType.STRING, type);
            pdc.set(KitchenKeys.PIE_REFUNDS, PersistentDataType.STRING, String.join(";", refunds));
            pdc.set(KitchenKeys.PIE_BAKED, PersistentDataType.BYTE, (byte) (baked ? 1 : 0));
            pdc.set(KitchenKeys.PIE_BITES, PersistentDataType.INTEGER, bites);
            pdc.set(KitchenKeys.COOLS_AT, PersistentDataType.LONG, coolsAt);
        }

        boolean assemblyDone(KitchenConfig.Pies pies) {
            return phase == Phase.ASSEMBLY && step >= pies.steps().size();
        }

        Location blockCenter() {
            Location l = display.getLocation();
            return new Location(l.getWorld(), l.getBlockX() + 0.5D, l.getBlockY(), l.getBlockZ() + 0.5D);
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<KitchenConfig> config;
    private final ItemResolverChain items;
    private final PieHolograms holograms;

    PieService(Plugin plugin, Supplier<KitchenConfig> config, ItemResolverChain items, PieHolograms holograms) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.items = items;
        this.holograms = holograms;
    }

    KitchenConfig.Pies pies() {
        return config.get().pies();
    }

    // ==================== lookup ====================

    /** The pie an entity belongs to, whether it is the display or the hitbox. */
    Optional<Pie> of(Entity entity) {
        String part = entity.getPersistentDataContainer().get(KitchenKeys.PIE_PART, PersistentDataType.STRING);
        if (DISPLAY.equals(part) && entity instanceof ItemDisplay display && display.isValid()) {
            return Optional.of(new Pie(display));
        }
        if (HITBOX.equals(part)) {
            String id = entity.getPersistentDataContainer().get(KitchenKeys.PIE_DISPLAY, PersistentDataType.STRING);
            if (id != null && entity.getWorld().getEntity(UUID.fromString(id)) instanceof ItemDisplay display
                    && display.isValid()) {
                return Optional.of(new Pie(display));
            }
        }
        return Optional.empty();
    }

    static boolean isPieDisplay(Entity entity) {
        return DISPLAY.equals(entity.getPersistentDataContainer().get(KitchenKeys.PIE_PART, PersistentDataType.STRING));
    }

    static boolean isPieHitbox(Entity entity) {
        return HITBOX.equals(entity.getPersistentDataContainer().get(KitchenKeys.PIE_PART, PersistentDataType.STRING));
    }

    /** The pie standing in a block cell, if any. */
    Optional<Pie> at(Block block) {
        Location center = block.getLocation().add(0.5D, 0.5D, 0.5D);
        for (Entity entity : block.getWorld().getNearbyEntities(center, 0.5D, 0.5D, 0.5D, PieService::isPieDisplay)) {
            Location l = entity.getLocation();
            if (l.getBlockX() == block.getX() && l.getBlockY() == block.getY() && l.getBlockZ() == block.getZ()) {
                return of(entity);
            }
        }
        return Optional.empty();
    }

    // ==================== placing ====================

    /** Spawns an empty crust, ready for its first ingredient. */
    Pie placeCrust(Block cell, float yaw) {
        Pie pie = spawn(cell, yaw);
        pie.phase = Phase.ASSEMBLY;
        pie.save();
        refresh(pie);
        return pie;
    }

    /** Spawns a whole pie. {@code coolsAt} of 0 on a baked pie starts its cooling timer now. */
    Pie placePie(Block cell, float yaw, KitchenConfig.PieType type, boolean baked, long coolsAt) {
        Pie pie = spawn(cell, yaw);
        pie.phase = Phase.PIE;
        pie.type = type.id();
        pie.baked = baked;
        if (baked) {
            pie.coolsAt = coolsAt > 0L ? coolsAt : System.currentTimeMillis() + pies().cooling().millis();
        }
        pie.save();
        refresh(pie);
        scheduleCooling(pie.display, pie.coolsAt);
        return pie;
    }

    private Pie spawn(Block cell, float yaw) {
        World world = cell.getWorld();
        Location displayAt = new Location(world, cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D, yaw, 0.0F);
        ItemDisplay display = world.spawn(displayAt, ItemDisplay.class, entity -> {
            entity.setPersistent(true);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setShadowStrength(0.0F);
            entity.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf()));
            entity.getPersistentDataContainer().set(KitchenKeys.PIE_PART, PersistentDataType.STRING, DISPLAY);
        });
        Location hitboxAt = new Location(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
        Interaction hitbox = world.spawn(hitboxAt, Interaction.class, entity -> {
            entity.setPersistent(true);
            entity.setInteractionWidth(0.8F);
            entity.setInteractionHeight(0.3F);
            entity.setResponsive(true);
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            pdc.set(KitchenKeys.PIE_PART, PersistentDataType.STRING, HITBOX);
            pdc.set(KitchenKeys.PIE_DISPLAY, PersistentDataType.STRING, display.getUniqueId().toString());
        });
        display.getPersistentDataContainer().set(KitchenKeys.PIE_HITBOX, PersistentDataType.STRING,
            hitbox.getUniqueId().toString());
        return new Pie(display);
    }

    // ==================== visuals ====================

    /** Brings the displayed model and the hologram in line with the pie's state. */
    void refresh(Pie pie) {
        KitchenConfig.Pies pies = pies();
        if (pies == null) return;
        ItemRef model = model(pie, pies);
        if (model != null) {
            items.create(model, 1).ifPresentOrElse(pie.display::setItemStack,
                () -> logger.warning("[Kitchen] Pie model " + model + " does not resolve"));
        }
        showHologram(pie, pies);
    }

    private ItemRef model(Pie pie, KitchenConfig.Pies pies) {
        KitchenConfig.PieType type = pies.type(pie.type).orElse(null);
        if (pie.phase == Phase.PIE) {
            if (type == null) return null;
            return type.models(pie.baked).get(Math.min(pie.bites, KitchenConfig.Pies.SLICES - 1));
        }
        if (type == null) return pies.crustModel();
        return pie.assemblyDone(pies) ? type.coldModels().get(0) : type.filledModel();
    }

    void showHologram(Pie pie, KitchenConfig.Pies pies) {
        UUID id = pie.display.getUniqueId();
        if (!pies.hologram().enabled() || pie.phase != Phase.ASSEMBLY) {
            holograms.remove(id);
            return;
        }
        Location at = pie.blockCenter().add(0.0D, pies.hologram().height(), 0.0D);
        if (pie.assemblyDone(pies)) {
            holograms.show(id, at, List.of(pies.hologram().doneLines()));
            return;
        }
        KitchenConfig.Step step = pies.steps().get(pie.step);
        int remaining = step.amount() - pie.progress;
        List<List<String>> frames = new ArrayList<>();
        if (step.isFilling()) {
            // Before any filling is in, cycle through every flavour; after the
            // first one, the rest of the step has to match it.
            List<KitchenConfig.PieType> options = pie.type == null
                ? List.copyOf(pies.types().values())
                : pies.type(pie.type).map(List::of).orElse(List.of());
            for (KitchenConfig.PieType type : options) {
                frames.add(lines(pies.hologram().stepLines(), type.filling(),
                    step.name() == null ? type.fillingName() : step.name(), remaining));
            }
        } else {
            frames.add(lines(pies.hologram().stepLines(), step.item(), step.name(), remaining));
        }
        holograms.show(id, at, frames);
    }

    private static List<String> lines(List<String> template, ItemRef item, String name, int amount) {
        // DecentHolograms' #ICON takes a material name, or nexo:<id> with the
        // Nexo icon support in our DecentHolograms build.
        String icon = item.isVanilla() ? item.id().toUpperCase(java.util.Locale.ROOT) : item.toString();
        List<String> out = new ArrayList<>(template.size());
        for (String line : template) {
            out.add(line.replace("<required-item>", icon)
                .replace("<item>", name)
                .replace("<amount>", Integer.toString(amount)));
        }
        return out;
    }

    // ==================== assembly ====================

    /**
     * Adds the held item to a pie under assembly if it is what the current
     * step needs. Returns false when the held item is not wanted, so the
     * caller can tell the player what is.
     */
    boolean addIngredient(Player player, Pie pie, ItemStack held) {
        KitchenConfig.Pies pies = pies();
        if (pie.phase != Phase.ASSEMBLY || pie.assemblyDone(pies) || held == null || held.getType().isAir()) {
            return false;
        }
        KitchenConfig.Step step = pies.steps().get(pie.step);
        ItemRef ref = items.identify(held).orElse(null);
        if (ref == null) return false;
        if (step.isFilling()) {
            KitchenConfig.PieType chosen = pies.typeByFilling(ref).orElse(null);
            if (chosen == null) return false;
            // Once some filling is in, the rest has to match it.
            if (pie.type != null && !pie.type.equals(chosen.id())) return false;
            pie.type = chosen.id();
        } else if (!step.item().equals(ref)) {
            return false;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
            addRefund(pie, ref);
        }
        pie.progress++;
        if (pie.progress >= step.amount()) {
            pie.step++;
            pie.progress = 0;
        }
        pie.save();
        refresh(pie);
        Location at = pie.blockCenter().add(0.0D, 0.3D, 0.0D);
        at.getWorld().playSound(at, pie.assemblyDone(pies) ? "block.wool.place" : "block.honey_block.place", 0.8F, 1.1F);
        if (pie.assemblyDone(pies)) {
            at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 6, 0.25D, 0.1D, 0.25D);
        }
        return true;
    }

    private static void addRefund(Pie pie, ItemRef ref) {
        String key = ref + "*";
        for (int i = 0; i < pie.refunds.size(); i++) {
            String entry = pie.refunds.get(i);
            if (entry.startsWith(key)) {
                int count = Integer.parseInt(entry.substring(key.length())) + 1;
                pie.refunds.set(i, key + count);
                return;
            }
        }
        pie.refunds.add(key + 1);
    }

    /** What the current step needs, for a hint when the wrong item is used. */
    String currentNeed(Pie pie) {
        KitchenConfig.Pies pies = pies();
        if (pie.assemblyDone(pies)) return null;
        KitchenConfig.Step step = pies.steps().get(pie.step);
        int remaining = step.amount() - pie.progress;
        if (!step.isFilling()) return remaining + "x " + step.name();
        if (pie.type != null) {
            return remaining + "x " + pies.type(pie.type).map(KitchenConfig.PieType::fillingName).orElse("filling");
        }
        return remaining + "x filling (" + String.join(", ",
            pies.types().values().stream().map(KitchenConfig.PieType::fillingName).toList()) + ")";
    }

    // ==================== eating ====================

    /** Eats one slice. Returns false (with feedback) when the player can't. */
    boolean eat(Player player, Pie pie) {
        KitchenConfig.Pies pies = pies();
        KitchenConfig.PieType type = pies.type(pie.type).orElse(null);
        if (pie.phase != Phase.PIE || type == null) return false;
        KitchenConfig.Eating eating = pies.eating();
        if (eating.requireHunger() && player.getFoodLevel() >= 20 && player.getGameMode() != GameMode.CREATIVE) {
            player.sendActionBar(MINI.deserialize("<gray>You're not hungry.</gray>"));
            return false;
        }
        int food = Math.min(20, player.getFoodLevel() + eating.nutrition());
        player.setFoodLevel(food);
        player.setSaturation(Math.min(food, player.getSaturation() + eating.saturation()));

        Location at = pie.blockCenter().add(0.0D, 0.25D, 0.0D);
        at.getWorld().playSound(at, eating.sound(), 1.0F, 0.9F + (float) Math.random() * 0.2F);
        items.create(type.item(pie.baked), 1).ifPresent(stack ->
            at.getWorld().spawnParticle(Particle.ITEM, at, 8, 0.2D, 0.05D, 0.2D, 0.05D, stack));

        pie.bites++;
        if (pie.bites >= KitchenConfig.Pies.SLICES) {
            remove(pie);
            at.getWorld().playSound(at, "entity.player.burp", 0.6F, 1.0F);
            return true;
        }
        pie.save();
        refresh(pie);
        return true;
    }

    // ==================== pickup / removal ====================

    /**
     * Picks a pie up, giving back what it is worth: the crust and ingredients
     * of an unfinished pie, the (cold) pie once assembled, or the whole pie
     * item — keeping its cooling time — if nobody has eaten from it yet.
     *
     * @return false if the pie can't be picked up (a slice is missing)
     */
    boolean pickup(Player player, Pie pie) {
        KitchenConfig.Pies pies = pies();
        List<ItemStack> give = new ArrayList<>();
        if (pie.phase == Phase.ASSEMBLY && pie.assemblyDone(pies)) {
            pies.type(pie.type).flatMap(type -> items.create(type.coldItem(), 1)).ifPresent(give::add);
        } else if (pie.phase == Phase.ASSEMBLY) {
            items.create(pies.crust(), 1).ifPresent(give::add);
            for (String refund : pie.refunds) {
                int star = refund.lastIndexOf('*');
                if (star < 0) continue;
                try {
                    ItemRef ref = ItemRef.parse(refund.substring(0, star));
                    int amount = Integer.parseInt(refund.substring(star + 1));
                    items.create(ref, amount).ifPresent(give::add);
                } catch (IllegalArgumentException ignored) {
                    // Unparseable refund entry: skip it.
                }
            }
        } else {
            if (pie.bites > 0) {
                player.sendActionBar(MINI.deserialize("<gray>Someone's already eaten some of this.</gray>"));
                return false;
            }
            KitchenConfig.PieType type = pies.type(pie.type).orElse(null);
            if (type == null) return false;
            boolean stillWarm = pie.baked && System.currentTimeMillis() < pie.coolsAt;
            items.create(type.item(stillWarm), 1).ifPresent(stack -> {
                if (stillWarm) stampCooling(stack, pie.coolsAt);
                give.add(stack);
            });
        }
        remove(pie);
        for (ItemStack stack : give) {
            player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        player.getWorld().playSound(pie.blockCenter(), "entity.item.pickup", 0.6F, 1.0F);
        return true;
    }

    /** Removes a pie's entities and hologram without giving anything back. */
    void remove(Pie pie) {
        holograms.remove(pie.display.getUniqueId());
        String hitboxId = pie.display.getPersistentDataContainer().get(KitchenKeys.PIE_HITBOX, PersistentDataType.STRING);
        if (hitboxId != null) {
            Entity hitbox = pie.display.getWorld().getEntity(UUID.fromString(hitboxId));
            if (hitbox != null) hitbox.remove();
        }
        pie.display.remove();
    }

    /** Drops what a pie is worth at its position, for when its support is broken. */
    void breakPie(Pie pie) {
        KitchenConfig.Pies pies = pies();
        Location at = pie.blockCenter().add(0.0D, 0.2D, 0.0D);
        List<ItemRef> drops = new ArrayList<>();
        if (pie.phase == Phase.ASSEMBLY) {
            if (pie.assemblyDone(pies)) {
                pies.type(pie.type).ifPresent(type -> drops.add(type.coldItem()));
            } else {
                drops.add(pies.crust());
            }
        } else if (pie.bites == 0) {
            pies.type(pie.type).ifPresent(type -> drops.add(type.coldItem()));
        }
        remove(pie);
        for (ItemRef ref : drops) {
            items.create(ref, 1).ifPresent(stack -> at.getWorld().dropItemNaturally(at, stack));
        }
    }

    // ==================== cooling ====================

    /** Called when a pie display or a dropped item is loaded or spawned. */
    void track(Entity entity) {
        KitchenConfig.Pies pies = pies();
        if (pies == null) return;
        if (isPieDisplay(entity)) {
            of(entity).ifPresent(pie -> {
                if (pie.phase == Phase.ASSEMBLY) {
                    showHologram(pie, pies);
                } else if (pie.baked) {
                    scheduleCooling(entity, pie.coolsAt);
                }
            });
        } else if (entity instanceof Item item && pies.cooling().droppedItems()) {
            ItemStack stack = item.getItemStack();
            if (!isBakedPie(stack)) return;
            long coolsAt = coolsAt(stack);
            if (coolsAt <= 0L) {
                coolsAt = System.currentTimeMillis() + pies.cooling().millis();
                stampCooling(stack, coolsAt);
                item.setItemStack(stack);
            }
            scheduleCooling(item, coolsAt);
        }
    }

    void untrack(Entity entity) {
        if (isPieDisplay(entity)) {
            holograms.remove(entity.getUniqueId());
        }
    }

    private void scheduleCooling(Entity entity, long coolsAt) {
        if (coolsAt <= 0L) return;
        long delayTicks = Math.max(1L, (coolsAt - System.currentTimeMillis()) / 50L);
        SchedulerAdapter.runLaterEntity(plugin, entity, () -> coolNow(entity), delayTicks);
    }

    private void coolNow(Entity entity) {
        KitchenConfig.Pies pies = pies();
        if (pies == null || !entity.isValid()) return;
        if (entity instanceof Item item) {
            ItemStack stack = item.getItemStack();
            if (!isBakedPie(stack) || System.currentTimeMillis() < coolsAt(stack)) return;
            ItemRef ref = items.identify(stack).orElse(null);
            pies.byItem(ref).flatMap(entry -> items.create(entry.getKey().coldItem(), stack.getAmount()))
                .ifPresent(item::setItemStack);
            return;
        }
        of(entity).ifPresent(pie -> {
            if (pie.phase != Phase.PIE || !pie.baked || System.currentTimeMillis() < pie.coolsAt) return;
            pie.baked = false;
            pie.coolsAt = 0L;
            pie.save();
            refresh(pie);
        });
    }

    boolean isBakedPie(ItemStack stack) {
        KitchenConfig.Pies pies = pies();
        if (pies == null || stack == null || stack.getType().isAir()) return false;
        return items.identify(stack).flatMap(pies::byItem).map(Map.Entry::getValue).orElse(false);
    }

    static long coolsAt(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return 0L;
        return meta.getPersistentDataContainer().getOrDefault(KitchenKeys.COOLS_AT, PersistentDataType.LONG, 0L);
    }

    private static void stampCooling(ItemStack stack, long coolsAt) {
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(KitchenKeys.COOLS_AT, PersistentDataType.LONG, coolsAt);
        stack.setItemMeta(meta);
    }
}
