package codes.castled.allium.spawnercraft;

import codes.castled.allium.PluginStart;
import codes.castled.allium.tradingcards.card.CardFactory;
import codes.castled.allium.tradingcards.card.Tier;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Random;

/**
 * Read-only recipe slideshow. Every page shares one inventory and view.
 */
public final class SpawnerCraftGuideGui {

    private static final String GUI_TITLE = "§8Spawner Crafting Guide";

    enum Page {
        SPAWNER("Spawner Crafting Guide"), PLUSHIE("Plushie Crafting Guide"), CARD("Card Crafting Guide");
        private final String title;
        Page(String title) { this.title = "§8" + title; }
        String title() { return title; }
    }

    record Recipe(Page page, ItemStack surrounding, ItemStack center, ItemStack result) {
        ItemStack[] contents() {
            ItemStack[] contents = new ItemStack[10];
            contents[0] = result.clone();
            for (int slot = 1; slot <= 9; slot++) {
                contents[slot] = (slot == 5 ? center : surrounding).clone();
            }
            return contents;
        }
    }

    static final class Guide implements InventoryHolder {
        private final List<Recipe> recipes;
        private Inventory inventory;
        private int index;
        private ScheduledTask timer;

        Guide(List<Recipe> recipes) { this.recipes = List.copyOf(recipes); }
        @Override public Inventory getInventory() { return inventory; }
        void attach(Inventory inventory) { this.inventory = inventory; }
        Recipe current() { return recipes.get(index); }

        void advance(InventoryView view) {
            if (view.getTopInventory() != inventory || recipes.size() < 2) return;
            index = (index + 1) % recipes.size();
            // setTitle updates the existing container, without closing/reopening
            // it or firing join/close logic. Then replace all recipe slots.
            view.setTitle(current().page().title());
            inventory.setContents(current().contents());
        }

        void stop() {
            if (timer != null) { timer.cancel(); timer = null; }
        }
    }

    private SpawnerCraftGuideGui() {}

    public static void open(Player player, EntityType entityType) {
        openHead(player, entityType, null);
    }

    /** Opens the recipes this particular head can craft; leaves decorative heads alone. */
    public static boolean openHead(Player player, EntityType entityType, ItemStack sourceHead) {
        PluginStart plugin = PluginStart.getInstance();
        List<Recipe> recipes = recipes(plugin, entityType, sourceHead);
        if (recipes.isEmpty()) return false;
        Guide guide = new Guide(recipes);
        Inventory gui = Bukkit.createInventory(guide, InventoryType.WORKBENCH, recipes.getFirst().page().title());
        guide.attach(gui);
        gui.setContents(guide.current().contents());
        InventoryView view = player.openInventory(gui);
        if (view == null || view.getTopInventory() != gui) return false;
        if (recipes.size() > 1) {
            guide.timer = player.getScheduler().runAtFixedRate(plugin, task -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != gui) {
                    guide.stop();
                    return;
                }
                guide.advance(player.getOpenInventory());
            }, guide::stop, 60L, 60L);
        }
        return true;
    }

    private static List<Recipe> recipes(PluginStart plugin, EntityType entityType) {
        return recipes(plugin, entityType, null);
    }

    private static List<Recipe> recipes(PluginStart plugin, EntityType entityType, ItemStack sourceHead) {
        List<Recipe> recipes = new ArrayList<>();
        ItemStack mobHead = sourceHead == null ? createDisplayHead(entityType) : sourceHead.clone();
        mobHead.setAmount(1);
        boolean spawnerIngredient = sourceHead == null || MobHeadRegistry.getEntityType(sourceHead,
            plugin.getConfig().getBoolean("spawnercraft.vanilla-heads-craft-spawners", false)) == entityType;
        boolean plushieIngredient = sourceHead == null || MobHeadRegistry.getEntityType(sourceHead,
            plugin.getConfig().getBoolean("spawnercraft.vanilla-heads-craft-plushies", true)) == entityType;
        if (spawnerIngredient && SpawnerHeadConfig.handles(entityType)) {
            recipes.add(new Recipe(Page.SPAWNER, mobHead, createDisplayCore(entityType), createResultSpawner(entityType)));
        }
        if (plushieIngredient && plugin != null && plugin.getConfig().getBoolean("spawnercraft.plushies", true)
            && plugin.getServer().getPluginManager().isPluginEnabled("Nexo")) {
            ItemStack plushie = PlushieCraftListener.createPlushie(
                PlushieCraftListener.plushieIdForMob(entityType.name().toLowerCase(Locale.ROOT)));
            if (plushie != null) recipes.add(new Recipe(Page.PLUSHIE, new ItemStack(Material.WHITE_WOOL), mobHead, plushie));
        }
        var cards = plugin == null ? null : plugin.getTradingCardsModule();
        if (spawnerIngredient && cards != null && cards.isEnabled() && cards.registry() != null && cards.factory() != null) {
            cards.registry().byMob(entityType.name().toLowerCase(Locale.ROOT)).ifPresent(definition ->
                cards.factory().create(definition, Tier.SIMPLE, cards.config().levelling().startLevel(),
                    50, CardFactory.startingSignatures(Tier.SIMPLE), false, cards.config(), new Random(0))
                    .ifPresent(card -> recipes.add(new Recipe(Page.CARD, mobHead, new ItemStack(Material.PAPER), card))));
        }
        return recipes;
    }

    public static boolean isGuide(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Guide;
    }

    static void advance(InventoryView view) {
        if (view.getTopInventory().getHolder() instanceof Guide guide) guide.advance(view);
    }

    static void close(Inventory inventory) {
        if (inventory.getHolder() instanceof Guide guide) {
            guide.stop();
            // None of the illustrated ingredients belong to the viewer.
            inventory.clear();
        }
    }

    public static void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (isGuide(inventory)) {
                close(inventory);
                player.closeInventory();
            }
        }
    }

    public static String getGuiTitle() {
        return GUI_TITLE;
    }

    private static ItemStack createDisplayHead(EntityType entityType) {
        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        String hexColor = SpawnerCoreManager.getMobColor(entityType);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Head");
            meta.setLore(MobHeadFactory.craftingLore());
            String texture = MobHeadTextureRegistry.getTexture(entityType);
            if (texture != null) {
                try {
                    String url = decodeTextureUrl(texture);
                    if (url != null) {
                        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                        PlayerTextures textures = profile.getTextures();
                        textures.setSkin(new URL(url));
                        profile.setTextures(textures);
                        meta.setOwnerProfile(profile);
                    }
                } catch (MalformedURLException ignored) {}
            }
            head.setItemMeta(meta);
        }
        return head;
    }

    private static String decodeTextureUrl(String base64) {
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(base64));
            int start = decoded.indexOf("\"url\":\"") + 7;
            int end = decoded.indexOf("\"", start);
            if (start > 6 && end > start) return decoded.substring(start, end);
        } catch (Exception ignored) {}
        return null;
    }

    private static ItemStack createDisplayCore(EntityType entityType) {
        ItemStack core = new ItemStack(Material.TRIAL_SPAWNER);
        ItemMeta meta = core.getItemMeta();
        if (meta != null) {
            String entityName = SpawnerCoreManager.formatEntityName(entityType);
            String hexColor = SpawnerCoreManager.getMobColor(entityType);
            meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Spawner Core");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7The center piece of the recipe.");
            lore.add("§7Obtained from §e/shop");
            meta.setLore(lore);
            core.setItemMeta(meta);
        }
        return core;
    }

    private static ItemStack createResultSpawner(EntityType entityType) {
        ItemStack spawner = new ItemStack(Material.SPAWNER);
        ItemMeta meta = spawner.getItemMeta();
        if (meta != null) {
            String entityName = SpawnerCoreManager.formatEntityName(entityType);
            String hexColor = SpawnerCoreManager.getMobColor(entityType);
            meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + entityName + " Spawner");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7Use this recipe to get spawners!");
            meta.setLore(lore);
            spawner.setItemMeta(meta);
        }
        return spawner;
    }
}
