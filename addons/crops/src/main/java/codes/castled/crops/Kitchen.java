package codes.castled.crops;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class Kitchen implements Listener {
    private record Step(Set<String> items, int amount, boolean filling, String name) {}
    private record Pie(String id, Set<String> fillings, String fillingName, String crust, String crustModel, String coldItem,
                       String bakedItem, String filled, List<String> progress, List<String> cold, List<String> baked,
                       List<Step> steps, Set<Integer> skipped, boolean standard) {}

    private final CropsPlugin plugin;
    private final ModelWorld world;
    private final ConfigurationSection config;
    private final Map<String, Pie> pies = new LinkedHashMap<>();
    private final Set<NamespacedKey> recipes = new HashSet<>();
    private final Map<String, Material> stoves = new LinkedHashMap<>();
    private final Map<UUID, Long> coolingDrops = new HashMap<>();
    private final String standardCrust;
    private final String standardModel;
    private final List<Step> steps;
    private final long cooling;
    private final String bag;
    private final String flour;
    private final int capacity;
    private final boolean hologram;
    private final double hologramHeight;
    private final long hologramCycle;
    private final List<String> stepLines;
    private final List<String> doneLines;
    private final Map<String, String> names = new HashMap<>();

    Kitchen(CropsPlugin plugin, ModelWorld world, ConfigurationSection config) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        standardCrust = config.getString("pies.crust.item", "");
        standardModel = config.getString("pies.crust.model", "");
        steps = readSteps(config.getMapList("pies.steps"));
        cooling = Catalog.duration(config.getString("pies.cooling.time", "10m"));
        bag = config.getString("bags.flour_bag.item", "");
        flour = config.getString("bags.flour_bag.stores", "");
        capacity = Math.max(1, config.getInt("bags.flour_bag.capacity", 256));
        hologram = config.getBoolean("pies.hologram.enabled", true);
        hologramHeight = config.getDouble("pies.hologram.height", 1.35);
        hologramCycle = Math.max(50, Catalog.duration(config.getString("pies.hologram.icon-cycle", "2s")));
        stepLines = lines("pies.hologram.step", List.of("#ICON: <required-item>", "&aAdd &7<amount>x &6<item>"));
        doneLines = lines("pies.hologram.done", List.of("&6Done!", "&7Pick up the pie and bake it!"));
        var stations = config.getConfigurationSection("furnaces.stations");
        if (stations != null && config.getBoolean("enabled", true) && config.getBoolean("furnaces.enabled", true)) {
            for (String id : stations.getKeys(false)) {
                Material carrier = Material.matchMaterial(stations.getString(id + ".type", "FURNACE"));
                if (carrier == Material.FURNACE || carrier == Material.SMOKER || carrier == Material.BLAST_FURNACE) {
                    stoves.put("allium:" + id, carrier);
                }
            }
        }
        var section = config.getConfigurationSection("pies.types");
        if (section != null && config.getBoolean("enabled", true) && config.getBoolean("pies.enabled", true)) {
            for (String id : section.getKeys(false)) {
                var row = section.getConfigurationSection(id);
                String cold = config.getString("pies.models.item").replace("{state}", "cold").replace("{type}", id);
                String baked = config.getString("pies.models.item").replace("{state}", "baked").replace("{type}", id);
                String filled = config.getString("pies.models.filled").replace("{type}", id);
                pies.put(id, new Pie(id, refs(row.get("filling")), row.getString("filling-name"), row.getString("crust-item", standardCrust),
                        row.getString("crust-model", standardModel), row.getString("cold-item", cold), row.getString("baked-item", baked),
                        row.getString("filled-model", filled), row.getStringList("progress-models"),
                        models(row, id, "cold"), models(row, id, "baked"), readSteps(row.getMapList("steps")),
                        Set.copyOf(row.getIntegerList("skip-steps")), row.getBoolean("opens-on-standard-crust")));
            }
        }
    }

    int pieCount() { return pies.size(); }
    List<String> pieIds() { return List.copyOf(pies.keySet()); }

    private List<String> models(ConfigurationSection row, String id, String state) {
        List<String> specific = row.getStringList(state + "-models");
        if (!specific.isEmpty()) return specific;
        String base = config.getString("pies.models.placed").replace("{state}", state).replace("{type}", id);
        return List.of("", "_slice1", "_slice2", "_slice3").stream().map(slice -> base.replace("{slice}", slice)).toList();
    }

    private static Set<String> refs(Object value) {
        if (value instanceof Collection<?> list) {
            Set<String> refs = new LinkedHashSet<>();
            for (Object item : list) refs.add(String.valueOf(item));
            return refs;
        }
        return value instanceof String s ? Set.of(s) : Set.of();
    }

    private static List<Step> readSteps(List<Map<?, ?>> rows) {
        return rows.stream().map(row -> new Step(refs(row.get("item")), Integer.parseInt(Catalog.string(row, "amount", "1")),
                Boolean.parseBoolean(Catalog.string(row, "filling", "false")), Catalog.string(row, "name", null))).toList();
    }

    private List<String> lines(String path, List<String> fallback) {
        if (config.isString(path)) return List.of(config.getString(path));
        return config.isList(path) ? config.getStringList(path) : fallback;
    }

    void registerRecipes(ConfigurationSection external) {
        if (!config.getBoolean("enabled", true)) return;
        for (String id : external.getKeys(false)) register(id, external.getConfigurationSection(id));
        for (String section : List.of("crafting", "smelting")) {
            var definitions = config.getConfigurationSection(section);
            if (definitions != null) for (String id : definitions.getKeys(false)) register(id, definitions.getConfigurationSection(id));
        }
        for (Pie pie : pies.values()) {
            if (recipes.contains(new NamespacedKey(plugin, "bake_" + pie.id() + "_pie"))
                    || recipes.contains(new NamespacedKey(plugin, "bake_" + pie.id()))) continue;
            var output = plugin.item(pie.bakedItem(), 1);
            NamespacedKey key = new NamespacedKey(plugin, "bake_" + pie.id() + "_pie");
            add(key, new FurnaceRecipe(key, output, choice(pie.coldItem()), 1, 200));
        }
    }

    private void register(String id, ConfigurationSection definition) {
        var key = new NamespacedKey(plugin, id);
        ItemStack output = plugin.item(definition.getString("result.item"), definition.getInt("result.amount", 1));
        if (output == null) throw new IllegalArgumentException("Missing result for " + id);
        if (definition.isString("ingredient")) {
            add(key, new FurnaceRecipe(key, output, choice(definition.get("ingredient")),
                    (float) definition.getDouble("experience", 0), definition.getInt("time", 200)));
        } else if (definition.contains("shape")) {
            var recipe = new ShapedRecipe(key, output);
            recipe.shape(definition.getStringList("shape").toArray(String[]::new));
            var keys = definition.getConfigurationSection("keys");
            for (String symbol : keys.getKeys(false)) recipe.setIngredient(symbol.charAt(0), choice(keys.get(symbol)));
            add(key, recipe);
        } else {
            var recipe = new ShapelessRecipe(key, output);
            for (Object ingredient : definition.getList("ingredients", List.of())) recipe.addIngredient(choice(ingredient));
            add(key, recipe);
        }
    }

    private RecipeChoice choice(Object ingredient) {
        List<ItemStack> items = refs(ingredient).stream().map(ref -> Objects.requireNonNull(plugin.item(ref, 1), ref)).toList();
        return new RecipeChoice.ExactChoice(items);
    }

    private void add(NamespacedKey key, Recipe recipe) {
        plugin.connection().items().registerRecipe(recipe);
        recipes.add(key);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void rightClick(PlayerInteractEvent event) {
        if (!config.getBoolean("enabled", true) || event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack held = event.getItem();
        String item = plugin.reference(held);
        Player player = event.getPlayer();
        Block target = event.getClickedBlock();
        if (item.equals(bag) && !bag.isEmpty() && (target == null || !target.getType().isInteractable())) {
            event.setCancelled(true);
            if (player.hasPermission("crops.use") && plugin.connection().canEditInventory(player)) withdraw(player, held);
            return;
        }
        if (event.isCancelled() || target == null || !player.hasPermission("crops.use")
                || !plugin.connection().canEditInventory(player)) return;
        if (knead(event, target, item)) return;
        if (event.getBlockFace() != BlockFace.UP || target.getType().isInteractable() && !player.isSneaking()) return;
        Material stove = stoves.get(item);
        if (stove != null) {
            placeStove(event, target, item, stove);
            return;
        }
        for (Pie pie : pies.values()) {
            if (item.equals(pie.bakedItem()) || item.equals(pie.coldItem())) {
                place(event, target, pie.id(), item.equals(pie.bakedItem()) ? "baked" : "cold");
                return;
            }
            if (!pie.crust().equals(standardCrust) && item.equals(pie.crust())) {
                place(event, target, pie.id(), "assembly");
                return;
            }
        }
        if (!pies.isEmpty() && item.equals(standardCrust)) place(event, target, "plain", "assembly");
    }

    private boolean knead(PlayerInteractEvent event, Block block, String held) {
        String ingredient = config.getString("kneading.flour.item");
        boolean fromBag = held.equals(bag) && !bag.isEmpty() && flour.equals(ingredient);
        if (!config.getBoolean("kneading.enabled", true) || block.getType() != Material.WATER_CAULDRON
                || !held.equals(ingredient) && !fromBag) return false;
        event.setCancelled(true);
        var player = event.getPlayer();
        int water = Math.max(1, config.getInt("kneading.water.required", 3));
        int required = Math.max(1, config.getInt("kneading.flour.required", 3));
        var hold = event.getItem();
        if (!(block.getBlockData() instanceof Levelled cauldron) || cauldron.getLevel() < water
                || !plugin.connection().mayModify(player, block, "knead", event)) return true;
        var data = block.getChunk().getPersistentDataContainer();
        var key = BlockKeys.at(plugin, "kneading", block);
        int prior = Math.max(0, Math.min(required - 1, data.getOrDefault(key, PersistentDataType.INTEGER, 0)));
        int use = fromBag ? 1 : Math.min(hold.getAmount(), required - prior);
        boolean complete = prior + use >= required;
        ItemStack dough = complete
                ? plugin.item(config.getString("kneading.result.item"), config.getInt("kneading.result.amount", 1))
                : null;
        if (complete && dough == null) return true;
        if (fromBag) {
            int stored = stored(hold);
            if (hold.getAmount() != 1 || stored == 0 || !writeBag(hold, stored - 1)) {
                player.sendMessage("§eThis flour bag is empty or stacked.");
                return true;
            }
            player.getInventory().setItemInMainHand(hold);
        } else CropsPlugin.spend(player, hold, use);
        if (!complete) {
            data.set(key, PersistentDataType.INTEGER, prior + use);
            player.sendMessage("§7Flour: §f" + (prior + use) + "/" + required);
            return true;
        }
        data.remove(key);
        int left = cauldron.getLevel() - water;
        if (left <= 0) block.setType(Material.CAULDRON);
        else {
            cauldron.setLevel(left);
            block.setBlockData(cauldron);
        }
        plugin.give(player, dough);
        block.getWorld().playSound(block.getLocation(), config.getString("kneading.sound", "block.mud.place"), 1, 1);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakKneadingStation(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.WATER_CAULDRON || block.getType() == Material.CAULDRON) {
            block.getChunk().getPersistentDataContainer().remove(BlockKeys.at(plugin, "kneading", block));
        }
    }

    private void place(PlayerInteractEvent event, Block support, String type, String state) {
        event.setCancelled(true);
        Block cell = support.getRelative(BlockFace.UP);
        if (!support.getType().isSolid() || !plugin.connection().mayModify(event.getPlayer(), cell, "place_pie", event)) return;
        long deadline = state.equals("baked") ? System.currentTimeMillis() + cooling : 0;
        ModelWorld.Placed pie = world.place(cell, "pie", type, state, 0, deadline, visual(type, state, 0));
        if (pie != null) CropsPlugin.spend(event.getPlayer(), event.getItem(), 1);
    }

    private void placeStove(PlayerInteractEvent event, Block support, String item, Material furnace) {
        event.setCancelled(true);
        Block cell = support.getRelative(BlockFace.UP);
        Player player = event.getPlayer();
        if (!support.getType().isSolid() || !plugin.connection().mayModify(player, cell, "place_stove", event)) return;
        var placed = world.place(cell, "stove", item, "", 0, 0, stoveVisual(item));
        if (placed == null) return;
        cell.setType(furnace);
        if (cell.getBlockData() instanceof org.bukkit.block.data.Directional direction) {
            direction.setFacing(player.getFacing().getOppositeFace());
            cell.setBlockData(direction);
        }
        CropsPlugin.spend(player, event.getItem(), 1);
    }

    private static Catalog.Visual stoveVisual(String id) {
        return new Catalog.Visual(id, ItemDisplayTransform.NONE, 1, .5, false);
    }

    void restore(ModelWorld.Placed placed) {
        if (placed.kind.equals("stove")) {
            if (stoves.get(placed.type) == placed.cell().getType()) world.show(placed, stoveVisual(placed.type));
            return;
        }
        if (placed.type.equals("plain") || pies.containsKey(placed.type)) {
            world.show(placed, visual(placed.type, placed.path, placed.stage));
            world.label(placed, frames(placed), hologramHeight, hologramCycle);
        }
    }

    /**
     * The step hologram, as legacy drew it: while a pie is assembled, what to add next, cycling
     * through every item the step takes (every filling, before the pie has one); once it is
     * finished, the done lines until it is picked up.
     */
    private List<ModelWorld.Frame> frames(ModelWorld.Placed placed) {
        if (!hologram) return List.of();
        Pie pie = pies.get(placed.type);
        if (placed.path.equals("ready")) {
            return List.of(frame(doneLines, pie == null ? null : pie.coldItem(), null, 1));
        }
        if (!placed.path.equals("assembly")) return List.of();
        List<Step> sequence = pie != null && !pie.steps().isEmpty() ? pie.steps() : steps;
        if (placed.stage >= sequence.size()) return List.of();
        Step step = sequence.get(placed.stage);
        List<ModelWorld.Frame> frames = new ArrayList<>();
        if (step.filling()) {
            // As assemble(): any pie made with the shared steps takes its filling here. One frame per
            // pie, showing its first filling, as legacy cycled through the flavours.
            for (Pie option : pies.values()) {
                if (!option.steps().isEmpty() || option.fillings().isEmpty()) continue;
                String filling = option.fillings().iterator().next();
                frames.add(frame(stepLines, filling, step.name() != null ? step.name() : option.fillingName(), step.amount()));
            }
        } else {
            for (String item : step.items()) frames.add(frame(stepLines, item, step.name(), step.amount()));
        }
        if (pie == null && placed.stage == 0) {
            // A plain crust can also open a pie with its own steps (deep-dish pizza).
            for (Pie option : pies.values()) {
                if (!option.standard() || option.steps().isEmpty()) continue;
                Step first = option.steps().get(0);
                for (String item : first.items()) frames.add(frame(stepLines, item, first.name(), first.amount()));
            }
        }
        return frames;
    }

    private ModelWorld.Frame frame(List<String> template, String item, String name, int amount) {
        List<String> above = new ArrayList<>(), below = new ArrayList<>();
        String icon = null;
        String label = name != null ? name : item == null ? "" : name(item);
        for (String line : template) {
            String text = line.replace("<required-item>", item == null ? "" : item)
                    .replace("<item>", label).replace("<amount>", Integer.toString(amount));
            if (text.regionMatches(true, 0, "#ICON:", 0, 6)) {
                if (icon == null) icon = text.substring(6).trim();
                continue;
            }
            (icon == null ? above : below).add(plugin.connection().text().legacy(text));
        }
        return new ModelWorld.Frame(List.copyOf(above), icon == null || icon.isEmpty() ? null : icon, List.copyOf(below));
    }

    /** An item's plain name for the hologram: its display name, or the vanilla item's name. */
    private String name(String reference) {
        return names.computeIfAbsent(reference, ref -> {
            ItemStack stack = plugin.item(ref, 1);
            if (stack != null && stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()) {
                return ChatColor.stripColor(stack.getItemMeta().getDisplayName());
            }
            String id = CropsPlugin.stripNamespace(ref).replace('_', ' ');
            StringBuilder out = new StringBuilder();
            for (String word : id.split(" ")) {
                if (word.isEmpty()) continue;
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        });
    }

    private Catalog.Visual visual(String type, String state, int stage) {
        Pie pie = pies.get(type);
        String item = standardModel;
        if (pie != null) {
            if (state.equals("assembly")) {
                item = stage == 0 ? pie.crustModel() : stage <= pie.progress().size()
                        ? pie.progress().get(stage - 1) : pie.filled();
            } else {
                item = (state.equals("baked") ? pie.baked() : pie.cold()).get(Math.max(0, Math.min(3, stage)));
            }
        }
        // Pie models carry no display transform, so they are drawn centred on the display, as in
        // legacy (NONE at the cell centre): the model's floor sits half a block below the centre,
        // which the .5 offset puts on the soil. GROUND with a .001 offset sank it into the soil.
        return new Catalog.Visual(item, ItemDisplayTransform.NONE, 1, .501, true);
    }

    void tick(ModelWorld.Placed placed) {
        if (placed.kind.equals("stove")) {
            if (stoves.get(placed.type) != placed.cell().getType()) world.remove(placed);
            else restore(placed);
            return;
        }
        var attack = placed.hitbox.getLastAttack();
        if (attack != null && attack.getTimestamp() > placed.lastAttack) {
            placed.lastAttack = attack.getTimestamp();
            Player player = attack.getPlayer().getPlayer();
            if (player != null && canUse(player, placed, null)) take(player, placed);
        }
        if (!placed.hitbox.isValid()) return;
        if (!placed.cell().getRelative(BlockFace.DOWN).getType().isSolid()) {
            take(null, placed);
            return;
        }
        if (placed.path.equals("baked") && placed.due <= System.currentTimeMillis()) change(placed, placed.type, "cold", placed.stage, 0);
        restore(placed);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        var placed = world.get(event.getRightClicked());
        if (placed == null || !placed.kind.equals("pie")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!canUse(player, placed, event)) return;
        if (player.isSneaking()) {
            take(player, placed);
        } else if (placed.path.equals("assembly")) {
            assemble(player, placed);
        } else if (placed.path.equals("baked")) {
            eat(player, placed);
        } else {
            player.sendMessage("§ePick up the pie and bake it.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        var placed = world.get(event.getEntity());
        if (placed == null || !placed.kind.equals("pie")) return;
        event.setCancelled(true);
        if (canUse(player, placed, event)) take(player, placed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectCells(BlockPlaceEvent event) {
        if (world.at(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakStove(BlockBreakEvent event) {
        var placed = world.at(event.getBlock());
        if (placed == null || !placed.kind.equals("stove")) return;
        boolean drop = event.isDropItems() && event.getPlayer().getGameMode() != GameMode.CREATIVE;
        event.setDropItems(false);
        plugin.connection().scheduler().runOnServer(() -> {
            if (event.isCancelled() || stoves.get(placed.type) == event.getBlock().getType()) return;
            world.remove(placed);
            if (drop) {
                ItemStack item = plugin.item(placed.type, 1);
                if (item != null) event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item);
            }
        });
    }

    private boolean canUse(Player player, ModelWorld.Placed placed, org.bukkit.event.Event source) {
        return player.hasPermission("crops.use") && plugin.connection().canEditInventory(player)
                && plugin.connection().mayModify(player, placed.cell(), "use_pie", source);
    }

    private void assemble(Player player, ModelWorld.Placed placed) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String input = plugin.reference(held);
        Pie pie = pies.get(placed.type);
        if (pie == null && placed.stage == 0) {
            pie = pies.values().stream().filter(candidate -> candidate.standard() && !candidate.steps().isEmpty()
                    && candidate.steps().get(0).items().contains(input)).findFirst().orElse(null);
        }
        List<Step> sequence = pie != null && !pie.steps().isEmpty() ? pie.steps() : steps;
        if (placed.stage >= sequence.size()) return;
        Step step = sequence.get(placed.stage);
        if (step.filling()) {
            pie = pies.values().stream().filter(candidate -> candidate.steps().isEmpty() && candidate.fillings().contains(input))
                    .findFirst().orElse(null);
            if (pie == null) { player.sendMessage("§eAdd a pie filling."); return; }
        } else if (!step.items().contains(input)) {
            player.sendMessage("§eAdd " + String.join(" or ", step.items()) + ".");
            return;
        }
        if (held.getAmount() < step.amount()) return;
        int next = placed.stage + 1;
        while (pie != null && pie.skipped().contains(next + 1)) next++;
        boolean finished = next >= sequence.size() && pie != null;
        String type = pie == null ? placed.type : pie.id();
        // "ready": finished here, shown as the cold pie with the done hologram until it is picked up.
        if (!change(placed, type, finished ? "ready" : "assembly", finished ? 0 : next, 0)) return;
        CropsPlugin.spend(player, held, step.amount());
        if (finished) player.sendMessage("§aThe pie is ready. Sneak-right-click to pick it up and bake it.");
    }

    private boolean change(ModelWorld.Placed placed, String type, String state, int stage, long due) {
        if (!world.show(placed, visual(type, state, stage))) return false;
        placed.type = type;
        placed.path = state;
        placed.stage = stage;
        placed.due = due;
        world.store(placed);
        world.label(placed, frames(placed), hologramHeight, hologramCycle);
        return true;
    }

    private void take(Player player, ModelWorld.Placed placed) {
        Pie pie = pies.get(placed.type);
        String item = pie == null ? standardCrust : switch (placed.path) {
            case "baked" -> pie.bakedItem();
            case "cold", "ready" -> pie.coldItem();
            default -> pie.crust();
        };
        // A part-eaten pie cannot turn back into a whole item.
        if (placed.stage == 0 || placed.path.equals("assembly")) {
            ItemStack result = plugin.item(item, 1);
            if (result == null) return;
            if (player == null) placed.hitbox.getWorld().dropItemNaturally(placed.hitbox.getLocation(), result);
            else plugin.give(player, result);
        }
        world.remove(placed);
    }

    private void eat(Player player, ModelWorld.Placed placed) {
        if (config.getBoolean("pies.eating.require-hunger", true) && player.getFoodLevel() >= 20
                && player.getGameMode() != GameMode.CREATIVE) return;
        if (placed.stage >= 3) world.remove(placed);
        else if (!change(placed, placed.type, "baked", placed.stage + 1, placed.due)) return;
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + config.getInt("pies.eating.nutrition", 3)));
        player.setSaturation(Math.min(player.getFoodLevel(), player.getSaturation() + (float) config.getDouble("pies.eating.saturation", .6)));
        player.playSound(player.getLocation(), config.getString("pies.eating.sound", "entity.generic.eat"), 1, 1);
    }

    @EventHandler
    public void dropped(ItemSpawnEvent event) {
        if (config.getBoolean("pies.cooling.dropped-items", true) && baked(event.getEntity().getItemStack()) != null) {
            coolingDrops.put(event.getEntity().getUniqueId(), System.currentTimeMillis() + cooling);
        }
    }

    private Pie baked(ItemStack item) {
        String reference = plugin.reference(item);
        return pies.values().stream().filter(pie -> pie.bakedItem().equals(reference)).findFirst().orElse(null);
    }

    void coolDrops() {
        long now = System.currentTimeMillis();
        coolingDrops.entrySet().removeIf(entry -> {
            if (!(Bukkit.getEntity(entry.getKey()) instanceof Item drop) || !drop.isValid()) return true;
            if (entry.getValue() > now) return false;
            Pie pie = baked(drop.getItemStack());
            if (pie != null) {
                ItemStack cold = plugin.item(pie.coldItem(), drop.getItemStack().getAmount());
                if (cold != null) drop.setItemStack(cold);
            }
            return true;
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void clickBag(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || bag.isEmpty() || flour.isEmpty()
                || !config.getBoolean("enabled", true) || !plugin.reference(event.getCurrentItem()).equals(bag)
                || !player.hasPermission("crops.use") || !plugin.connection().canEditInventory(player)) return;
        ItemStack bagItem = event.getCurrentItem();
        if (bagItem.getAmount() != 1) return;
        ItemStack cursor = event.getCursor();
        boolean empty = cursor == null || cursor.getType().isAir();
        boolean matching = !empty && plugin.reference(cursor).equals(flour);
        int stored = stored(bagItem);
        switch (event.getClick()) {
            case LEFT -> {
                if (!matching) return;
                event.setCancelled(true);
                int amount = Math.min(cursor.getAmount(), capacity - stored);
                if (amount <= 0) {
                    player.sendMessage("§eThe flour bag is full.");
                    return;
                }
                ItemStack remainder = cursor.clone();
                remainder.setAmount(cursor.getAmount() - amount);
                setBagClick(event, player, bagItem, stored + amount, remainder.getAmount() > 0 ? remainder : null);
            }
            case RIGHT, SHIFT_LEFT -> {
                boolean shift = event.isShiftClick();
                if (shift && !empty || !shift && !empty && !matching || stored == 0 && !matching) return;
                event.setCancelled(true);
                if (stored == 0 || matching && cursor.getAmount() >= cursor.getMaxStackSize()) return;
                ItemStack outgoing = matching ? cursor.clone() : plugin.item(flour, 1);
                if (outgoing == null) return;
                int amount = shift ? Math.min(stored, outgoing.getMaxStackSize()) : 1;
                outgoing.setAmount(amount + (matching ? cursor.getAmount() : 0));
                setBagClick(event, player, bagItem, stored - amount, outgoing);
            }
            default -> { }
        }
    }

    private void setBagClick(InventoryClickEvent event, Player player, ItemStack original, int amount, ItemStack cursor) {
        ItemStack changed = original.clone();
        if (!writeBag(changed, amount)) return;
        event.setCurrentItem(changed);
        player.setItemOnCursor(cursor);
    }

    private void withdraw(Player player, ItemStack item) {
        if (item.getAmount() != 1) return;
        int stored = stored(item), amount = player.isSneaking() ? Math.min(64, stored) : Math.min(1, stored);
        if (amount == 0) return;
        ItemStack result = plugin.item(flour, amount);
        if (result == null || !writeBag(item, stored - amount)) return;
        player.getInventory().setItemInMainHand(item);
        plugin.give(player, result);
    }

    private int stored(ItemStack item) {
        String value = plugin.connection().items().identify(item).map(identity -> identity.state().get("flour")).orElse("0");
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private boolean writeBag(ItemStack item, int stored) {
        if (!plugin.connection().items().updateState(item, Map.of("flour", Integer.toString(stored)))) return false;
        showBagAmount(plugin, config, item, stored);
        return true;
    }

    static void showBagAmount(CropsPlugin plugin, ConfigurationSection settings, ItemStack item, int stored) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        String lore = settings.getString("bags.flour_bag.lore", "&7Flour: &f<amount>/<capacity>")
                .replace("<amount>", Integer.toString(stored))
                .replace("<capacity>", Integer.toString(Math.max(1, settings.getInt("bags.flour_bag.capacity", 256))));
        meta.setLore(List.of(plugin.connection().text().legacy(lore)));
        item.setItemMeta(meta);
    }
}
