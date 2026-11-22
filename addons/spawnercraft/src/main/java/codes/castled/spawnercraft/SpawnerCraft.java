package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class SpawnerCraft extends AlliumAddon implements Listener {
    private static final long GUIDE_PAGE_MILLIS = 3_000;

    private Heads heads;
    private HeadRecipes recipes;
    private NamespacedKey previewKey;
    private Path importedFile;

    @Override
    protected void onAddonEnable(AddonConnection core) {
        saveDefaultConfig();
        importedFile = getDataFolder().toPath().resolve("imported-recipes.yml");
        previewKey = new NamespacedKey(this, "recipe_preview");
        try {
            heads = new Heads(core.items(), Objects.requireNonNull(getResource("heads.tsv")),
                    Objects.requireNonNull(getResource("legacy-heads.tsv")));
            recipes = new HeadRecipes(heads, core.items(), this::cards);
            loadRecipes(readImported());
            if (core.powerTools().available()) {
                saveResourceIfMissing("spawner_changer.yml");
                var tool = PlushieImport.load(getDataFolder().toPath().resolve("spawner_changer.yml"));
                if (!tool.contains("item.custom-model-data")
                        && Material.matchMaterial(tool.getString("item.material", "AIR")) == Material.BLAZE_ROD) {
                    // Configurations saved before the pack shipped still inherit the bundled model.
                    try (var bundled = new InputStreamReader(Objects.requireNonNull(getResource("spawner_changer.yml")),
                            StandardCharsets.UTF_8)) {
                        int model = YamlConfiguration.loadConfiguration(bundled).getInt("item.custom-model-data");
                        tool.set("item.custom-model-data", model);
                    }
                }
                var changer = new SpawnerChanger(this);
                core.powerTools().registerAction("change_spawner", changer::activate);
                core.powerTools().registerTool(tool);
                getServer().getPluginManager().registerEvents(changer, this);
            }
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Could not initialize SpawnerCraft", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MobHeadDrops(heads, this::getConfig), this);
        if (getServer().getPluginManager().isPluginEnabled("Curios")) {
            try {
                CuriosTradeHeads.register(this, heads);
            } catch (LinkageError ignored) {
                // Older versions of Curios do not take trade-in heads; they pay the card's trade-item.
            }
        }
        getLogger().info("Loaded " + heads.ids().size() + " signed head definitions across " + heads.mobs().size() + " mob families.");
    }

    AddonConnection connection() { return allium(); }

    private void saveResourceIfMissing(String name) {
        if (!Files.exists(getDataFolder().toPath().resolve(name))) saveResource(name, false);
    }

    private HeadRecipes.Cards cards() {
        if (!getServer().getPluginManager().isPluginEnabled("Curios")) return null;
        try {
            return CuriosCards.find(getServer());
        } catch (LinkageError ignored) {
            // Older versions of Curios do not provide the card crafting service.
            return null;
        }
    }

    private YamlConfiguration readImported() throws IOException, InvalidConfigurationException {
        return Files.exists(importedFile) ? PlushieImport.load(importedFile) : new YamlConfiguration();
    }

    private void loadRecipes(YamlConfiguration imported) {
        recipes.load(getConfig().getConfigurationSection("recipes"), imported.getConfigurationSection("recipes"));
    }

    @Override
    protected void onAddonDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Guide guide) {
                guide.close();
                player.closeInventory();
            } else if (SpawnerChanger.isMenu(top)) {
                player.closeInventory();
            } else if (top instanceof CraftingInventory craft && preview(craft.getResult()) != null) {
                craft.setResult(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (action == Action.RIGHT_CLICK_BLOCK && event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        String core = heads.coreMob(event.getItem());
        String mob = core != null ? core : heads.mob(event.getItem());
        if (mob == null) return;
        event.setCancelled(true);
        if (event.getPlayer().hasPermission("spawnercraft.use")) openGuide(event.getPlayer(), mob, core != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCorePlace(BlockPlaceEvent event) {
        if (heads.coreMob(event.getItemInHand()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent event) {
        if (event.getInventory().getHolder() instanceof Guide) return;
        if (event.getView().getPlayer().hasPermission("spawnercraft.craft")) refresh(event.getInventory());
    }

    private void refresh(CraftingInventory inventory) {
        var match = recipes.match(inventory.getMatrix());
        if (match != null) {
            ItemStack stack = recipes.previewItem(match.recipe(), match.mob(), "§e" + Heads.title(match.mob()) + " " + match.recipe().name(),
                    "§7Click to craft one");
            var meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(previewKey, PersistentDataType.STRING, match.recipe().id() + ":" + match.mob());
            stack.setItemMeta(meta);
            inventory.setResult(stack);
        } else if (preview(inventory.getResult()) != null) {
            inventory.setResult(null);
        }
    }

    private String preview(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(previewKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Guide guide) {
            boolean cancelled = event.isCancelled();
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (!cancelled && guide.pages.size() > 1 && slot >= 0 && slot < top.getSize()
                    && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
                int step = event.getClick() == ClickType.RIGHT ? -1 : 1;
                allium().scheduler().runOnServer(() -> advanceGuide(player, guide, step));
            }
            return;
        }
        if (event.isCancelled() || !(top instanceof CraftingInventory inventory) || event.getRawSlot() != 0) return;
        String displayed = preview(event.getCurrentItem());
        if (displayed == null) return;
        event.setCancelled(true);
        if (!ordinaryClick(event) || !player.hasPermission("spawnercraft.craft") || !allium().canEditInventory(player)) return;
        ItemStack[] matrix = inventory.getMatrix();
        var match = recipes.match(matrix);
        if (match == null || !displayed.equals(match.recipe().id() + ":" + match.mob())) {
            refresh(inventory);
            return;
        }
        if (!recipes.deliver(player, match)) {
            player.sendMessage("§cThe recipe result is unavailable. Your ingredients are still in the grid.");
            return;
        }
        for (int i = 0; i < matrix.length; i++) {
            ItemStack item = matrix[i];
            if (item == null || item.getType().isAir()) continue;
            if (item.getAmount() == 1) matrix[i] = null;
            else item.setAmount(item.getAmount() - 1);
        }
        inventory.setMatrix(matrix);
        refresh(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Guide) event.setCancelled(true);
    }

    @EventHandler
    public void onGuideClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Guide guide) guide.close();
    }

    static boolean ordinaryClick(InventoryClickEvent event) {
        return switch (event.getClick()) {
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT -> true;
            default -> false;
        };
    }

    private void openGuide(Player player, String mob, boolean core) {
        List<HeadRecipes.Recipe> available = recipes.forMob(mob, core);
        if (available.isEmpty()) {
            player.sendMessage("§7No recipes are available for " + Heads.title(mob) + ".");
            return;
        }
        var guide = new Guide(mob, available);
        guide.inventory = Bukkit.createInventory(guide, InventoryType.WORKBENCH, guide.title());
        renderGuide(guide);
        InventoryView opened = player.openInventory(guide.inventory);
        if (opened != null && opened.getTopInventory() == guide.inventory && available.size() > 1) {
            guide.timer = allium().scheduler().repeatOnServer(
                    () -> advanceGuide(player, guide, 1), GUIDE_PAGE_MILLIS, GUIDE_PAGE_MILLIS);
        }
    }

    private boolean advanceGuide(Player player, Guide guide, int step) {
        InventoryView view = player.getOpenInventory();
        if (!player.isOnline() || view.getTopInventory() != guide.inventory) return false;
        guide.page = Math.floorMod(guide.page + step, guide.pages.size());
        view.setTitle(guide.title());
        renderGuide(guide);
        return true;
    }

    private void renderGuide(Guide guide) {
        var recipe = guide.pages.get(guide.page);
        ItemStack[] contents = new ItemStack[10];
        Heads.Head head = heads.find(guide.mob);
        for (int i = 0; i < 9; i++) {
            char symbol = recipe.pattern().charAt(i);
            contents[i + 1] = switch (symbol) {
                case 'H' -> heads.create(head);
                case 'C' -> heads.core(guide.mob);
                case '.', ' ' -> null;
                default -> showIngredient(recipe.ingredients().get(symbol));
            };
        }
        String title = "§e" + Heads.title(guide.mob) + " " + recipe.name();
        contents[0] = guide.pages.size() > 1
                ? recipes.previewItem(recipe, guide.mob, title, "§7Left-click for next recipe", "§7Right-click for previous recipe")
                : recipes.previewItem(recipe, guide.mob, title);
        guide.inventory.setContents(contents);
    }

    private ItemStack showIngredient(String reference) {
        if ("#wool".equals(reference)) return label(Material.WHITE_WOOL, "§fAny wool");
        return allium().items().create(reference, 1).stack().orElseGet(() -> label(Material.BARRIER, reference));
    }

    static ItemStack label(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class Guide implements InventoryHolder {
        private final String mob;
        private final List<HeadRecipes.Recipe> pages;
        private int page;
        private Inventory inventory;
        private TaskHandle timer;

        private Guide(String mob, List<HeadRecipes.Recipe> pages) {
            this.mob = mob;
            this.pages = pages;
        }

        private String title() {
            String name = pages.get(page).name() + " Recipe";
            return pages.size() == 1 ? name : name + " (" + (page + 1) + "/" + pages.size() + ")";
        }

        private void close() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            inventory.clear();
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "guide" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("guide") && sender instanceof Player player) {
            String mob = args.length == 2 ? args[1].toLowerCase(Locale.ROOT)
                    : heads.mob(player.getInventory().getItemInMainHand());
            if (mob == null) mob = heads.coreMob(player.getInventory().getItemInMainHand());
            if (mob == null || heads.find(mob) == null) player.sendMessage("§eHold a SpawnerCraft head or use /spawnercraft guide <mob>.");
            else openGuide(player, heads.find(mob).mob(), false);
            return true;
        }
        if (!sender.hasPermission("spawnercraft.admin")) return true;
        try {
            switch (sub) {
                case "give" -> give(sender, args);
                case "import" -> importPlushies(sender, args);
                case "reload" -> {
                    reloadConfig();
                    loadRecipes(readImported());
                    sender.sendMessage("§aReloaded SpawnerCraft drop chances and recipes.");
                }
                default -> { return false; }
            }
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            sender.sendMessage("§cSpawnerCraft: " + e.getMessage());
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5 || !(args[2].equals("head") || args[2].equals("core"))) {
            sender.sendMessage("§e/spawnercraft give <player> <head|core> <mob or head ID> [amount]");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null || !allium().canEditInventory(player)) {
            sender.sendMessage("§cPlayer inventory unavailable.");
            return;
        }
        String id = args[3].toLowerCase(Locale.ROOT);
        int amount = args.length == 5 ? Integer.parseInt(args[4]) : 1;
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Amount must be between 1 and 64");
        Heads.Head head = heads.find(id);
        if (head == null || (args[2].equals("core") && heads.type(head.mob()) == null)) {
            throw new IllegalArgumentException("Unknown or unavailable mob: " + id);
        }
        ItemStack stack = args[2].equals("core") ? heads.core(head.mob()) : heads.create(head);
        stack.setAmount(amount);
        player.getInventory().addItem(stack).values().forEach(extra ->
                player.getWorld().dropItemNaturally(player.getLocation(), extra));
        sender.sendMessage("§aGave " + player.getName() + " " + amount + " " + stack.getItemMeta().getDisplayName() + "§a.");
    }

    private void importPlushies(CommandSender sender, String[] args) throws IOException, InvalidConfigurationException {
        String configured = getConfig().getString("import-directory", "import");
        String sourceArg = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : configured;
        if (sourceArg.equals("$SF") || sourceArg.startsWith("$SF/")) {
            String sf = System.getenv("SF");
            if (sf == null || sf.isBlank()) throw new IllegalArgumentException("SF is not set for the server process");
            sourceArg = sf + sourceArg.substring(3);
        }
        Path source = Path.of(sourceArg);
        if (!source.isAbsolute()) source = getDataFolder().toPath().resolve(source);
        if (!Files.exists(source) && args.length == 1) {
            Path plugins = getServer().getPluginManager().getPlugin("Nexo") == null ? null
                    : getServer().getPluginManager().getPlugin("Nexo").getDataFolder().toPath();
            if (plugins != null) source = plugins;
        }
        Set<String> available = PlushieImport.discover(source, heads.mobs());
        var staged = readImported();
        int count = PlushieImport.merge(staged, heads.mobs(), available);
        if (count > 0) {
            PlushieImport.save(staged, importedFile);
            loadRecipes(staged);
        }
        sender.sendMessage("§aImported " + count + " plushie recipes.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("spawnercraft.admin");
        Stream<String> candidates = switch (args.length) {
            case 1 -> admin ? Stream.of("guide", "give", "import", "reload") : Stream.of("guide");
            case 2 -> args[0].equals("guide") ? heads.mobs().stream() : admin && args[0].equals("give")
                    ? Bukkit.getOnlinePlayers().stream().map(Player::getName) : Stream.empty();
            case 3 -> admin && args[0].equals("give") ? Stream.of("head", "core") : Stream.empty();
            case 4 -> admin && args[0].equals("give") ? (args[2].equals("head")
                    ? Stream.concat(heads.mobs().stream(), heads.ids().stream()).distinct()
                    : heads.mobs().stream().filter(mob -> heads.type(mob) != null)) : Stream.empty();
            default -> Stream.empty();
        };
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
