package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.ItemResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.function.Supplier;

final class HeadRecipes {
    static final NamespacedKey CRAFTED_SPAWNER_KEY = new NamespacedKey("spawnercraft", "crafted_spawner");

    enum DeliveryStatus {
        DELIVERED,
        COMMAND_DISPATCHED,
        COMMAND_SCHEDULED,
        COMMAND_REFUSED,
        UNAVAILABLE,
        MATRIX_CHANGED
    }

    record DeliveryResult(DeliveryStatus status, String detail) {
        boolean succeeded() {
            return status == DeliveryStatus.DELIVERED
                    || status == DeliveryStatus.COMMAND_DISPATCHED
                    || status == DeliveryStatus.COMMAND_SCHEDULED;
        }
    }

    @FunctionalInterface
    interface CommandDispatcher {
        boolean dispatch(String command);
    }

    record Recipe(String id, String name, String mob, String pattern, Map<Character, String> ingredients,
                  String command, String item, boolean spawner, boolean card, Material preview, Map<String, Double> weights) {
        boolean isCommand() {
            return command != null && !command.isEmpty();
        }
    }
    record Match(Recipe recipe, String mob) {}
    interface Cards {
        boolean supports(String mob);
        OptionalInt previewModel(String mob, Map<String, Double> weights);
        Optional<ItemStack> craft(String mob, Map<String, Double> weights);
    }

    private final Heads heads;
    private final AddonItems items;
    private final AddonConnection connection;
    private final Supplier<Cards> cards;
    private final CommandDispatcher commandDispatcher;
    private volatile List<Recipe> recipes = List.of();

    HeadRecipes(Heads heads, AddonItems items, Supplier<Cards> cards) {
        this(heads, items, null, cards, cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
    }

    HeadRecipes(Heads heads, AddonConnection connection, Supplier<Cards> cards) {
        this(heads, connection.items(), connection, cards, cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
    }

    HeadRecipes(Heads heads, AddonItems items, AddonConnection connection, Supplier<Cards> cards,
                CommandDispatcher commandDispatcher) {
        this.heads = heads;
        this.items = items;
        this.connection = connection;
        this.cards = cards;
        this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher");
    }

    void load(ConfigurationSection config, ConfigurationSection imported) {
        Map<String, Recipe> staged = new LinkedHashMap<>();
        read(imported, staged);
        read(config, staged);
        recipes = List.copyOf(staged.values());
    }

    private static void read(ConfigurationSection section, Map<String, Recipe> staged) {
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            var row = section.getConfigurationSection(id);
            if (row == null) throw new IllegalArgumentException("Recipe " + id + " must be a section");
            if (!row.getBoolean("enabled", true)) {
                staged.remove(id);
                continue;
            }
            List<String> rows = row.getStringList("pattern");
            String pattern = String.join("", rows);
            var result = row.getConfigurationSection("result");
            if (rows.size() != 3 || rows.stream().anyMatch(line -> line.length() != 3)
                    || !pattern.contains("H") || result == null) {
                throw new IllegalArgumentException("Recipe " + id + " needs a 3x3 pattern containing H and a result");
            }
            Map<Character, String> ingredients = new HashMap<>();
            var ingredientSection = row.getConfigurationSection("ingredients");
            for (char symbol : pattern.toCharArray()) {
                if ("HC. ".indexOf(symbol) >= 0 || ingredients.containsKey(symbol)) continue;
                String ingredient = ingredientSection == null ? "" : ingredientSection.getString(String.valueOf(symbol), "").trim();
                if (!ingredient.equals("#wool") && !ingredient.matches("[a-zA-Z0-9_]+(?::[a-z0-9_./-]+)?")) {
                    throw new IllegalArgumentException("Recipe " + id + " has an invalid ingredient for " + symbol);
                }
                ingredients.put(symbol, ingredient);
            }
            String command = result.getString("command", "").trim().replaceFirst("^/", "");
            String item = result.getString("item", "").trim();
            boolean spawner = result.getBoolean("spawner");
            boolean card = result.getBoolean("card");
            if (command.isEmpty() && (item.isEmpty() ? 0 : 1) + (spawner ? 1 : 0) + (card ? 1 : 0) != 1) {
                throw new IllegalArgumentException("Recipe " + id + " needs one item, command, spawner or card result");
            }
            if (command.contains("\n") || command.contains("\r")) {
                throw new IllegalArgumentException("Recipe " + id + " must use a single give command");
            }
            Material preview = Material.matchMaterial(result.getString("preview", "PAPER"));
            if (preview == null || !preview.isItem() || preview.isAir()) {
                throw new IllegalArgumentException("Recipe " + id + " has an invalid result preview material");
            }
            Map<String, Double> weights = new LinkedHashMap<>();
            var weightsSection = result.getConfigurationSection("weights");
            if (weightsSection != null) for (String tier : weightsSection.getKeys(false)) {
                double weight = weightsSection.getDouble(tier);
                if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Invalid card weight for " + tier);
                weights.put(tier, weight);
            }
            staged.put(id, new Recipe(id, row.getString("name", id), row.getString("mob", ""),
                    pattern, Map.copyOf(ingredients), command, item, spawner, card, preview, Map.copyOf(weights)));
        }
    }

    List<Recipe> forMob(String mob, boolean coreOnly) {
        return recipes.stream().filter(recipe -> (!coreOnly || recipe.pattern().contains("C"))
                && applies(recipe, mob)).toList();
    }

    ItemStack previewItem(Recipe recipe, String mob, String title, String... lore) {
        ItemStack stack = SpawnerCraft.label(recipe.preview(), title, lore);
        if (!recipe.card() || !recipe.command().isEmpty() || stack.getType() != Material.PAPER) return stack;
        Cards service = cards.get();
        if (service != null) service.previewModel(mob, recipe.weights()).ifPresent(model -> {
            var meta = stack.getItemMeta();
            meta.setCustomModelData(model);
            stack.setItemMeta(meta);
        });
        return stack;
    }

    private boolean applies(Recipe recipe, String mob) {
        if (!recipe.mob().isEmpty() && !recipe.mob().equals(mob)) return false;
        if (recipe.command().isEmpty() && recipe.spawner() && heads.type(mob) == null) return false;
        if (recipe.command().isEmpty() && recipe.card()) {
            Cards service = cards.get();
            return service != null && service.supports(mob);
        }
        return true;
    }

    Match match(ItemStack[] matrix) {
        if (matrix.length != 9) return null;
        // Decode each signed head once even when the recipe list contains many imported mobs.
        String[] mobs = new String[9];
        String mob = null;
        for (int i = 0; i < matrix.length; i++) {
            mobs[i] = heads.mob(matrix[i]);
            if (mobs[i] == null) continue;
            if (mob != null && !mob.equals(mobs[i])) return null;
            mob = mobs[i];
        }
        if (mob == null) return null;
        for (Recipe recipe : forMob(mob, false)) {
            if (matches(recipe, matrix, mobs, mob)) return new Match(recipe, mob);
        }
        return null;
    }

    private boolean matches(Recipe recipe, ItemStack[] matrix, String[] mobs, String mob) {
        for (int i = 0; i < matrix.length; i++) {
            char symbol = recipe.pattern().charAt(i);
            ItemStack item = matrix[i];
            boolean match = switch (symbol) {
                case 'H' -> mob.equals(mobs[i]);
                case 'C' -> mob.equals(heads.coreMob(item));
                case '.', ' ' -> item == null || item.getType().isAir() || item.getAmount() <= 0;
                default -> mobs[i] == null && matchesIngredient(item, recipe.ingredients().get(symbol));
            };
            if (!match) return false;
        }
        return true;
    }

    private boolean matchesIngredient(ItemStack item, String ingredient) {
        if (item == null || item.getAmount() <= 0 || item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta) return false;
        if (ingredient.equals("#wool")) return Tag.WOOL.isTagged(item.getType());
        if (ingredient.contains(":") && !ingredient.startsWith("minecraft:")) {
            return items.identify(item).filter(id -> id.appearance().equals(ingredient)).isPresent();
        }
        return item.getType() == Material.matchMaterial(ingredient);
    }

    private record PreparedOutput(ItemStack stack, String unavailableReason) {}
    private record SlotFingerprint(Material material, int amount) {}

    /**
     * Executes a full crafting transaction with matrix revalidation after any callbacks.
     *
     * <p>Typed outputs ({@code spawner}, {@code card}, {@code item}) build and verify the reward
     * before revalidating and consuming the live crafting matrix, then grant the item.
     * Command outputs are treated as best-effort console effects: on Spigot/Paper, the command is
     * dispatched first and ingredients are consumed only if dispatch succeeds and the live matrix
     * is unchanged; on Folia, the live matrix is consumed on the player thread immediately before
     * scheduling the best-effort console command on the global scheduler.
     */
    DeliveryResult craft(Player player, CraftingInventory inventory, String displayedPreview) {
        ItemStack[] initialMatrix = inventory.getMatrix();
        Match match = match(initialMatrix);
        if (match == null || !displayedPreview.equals(match.recipe().id() + ":" + match.mob())) {
            return new DeliveryResult(DeliveryStatus.MATRIX_CHANGED, "Crafting grid no longer matches the previewed recipe.");
        }
        SlotFingerprint[] before = fingerprint(initialMatrix);
        Recipe recipe = match.recipe();
        if (!recipe.isCommand()) {
            PreparedOutput prepared = prepareTypedOutput(match);
            if (prepared.stack() == null) {
                return new DeliveryResult(DeliveryStatus.UNAVAILABLE, prepared.unavailableReason());
            }
            ItemStack[] liveMatrix = inventory.getMatrix();
            if (!player.isOnline() || !matchesSameRecipeAndSnapshot(liveMatrix, match, before)) {
                return new DeliveryResult(DeliveryStatus.MATRIX_CHANGED, "Crafting grid changed before ingredients could be consumed.");
            }
            consumeOneFromEachSlot(inventory, liveMatrix);
            giveOrDrop(player, prepared.stack());
            return new DeliveryResult(DeliveryStatus.DELIVERED, "Delivered typed recipe output.");
        }

        if (connection != null && connection.isFolia()) {
            ItemStack[] liveMatrix = inventory.getMatrix();
            if (!player.isOnline() || !matchesSameRecipeAndSnapshot(liveMatrix, match, before)) {
                return new DeliveryResult(DeliveryStatus.MATRIX_CHANGED, "Crafting grid changed before ingredients could be consumed.");
            }
            consumeOneFromEachSlot(inventory, liveMatrix);
            String command = formatCommand(recipe.command(), player, match.mob());
            connection.scheduler().runOnServer(() -> commandDispatcher.dispatch(command));
            return new DeliveryResult(DeliveryStatus.COMMAND_SCHEDULED, "Scheduled best-effort recipe command on global scheduler.");
        }

        DeliveryResult dispatchResult = dispatchCommand(player, match);
        if (!dispatchResult.succeeded()) {
            return dispatchResult;
        }
        ItemStack[] liveMatrix = inventory.getMatrix();
        if (!player.isOnline() || !matchesSameRecipeAndSnapshot(liveMatrix, match, before)) {
            return new DeliveryResult(DeliveryStatus.MATRIX_CHANGED, "Crafting grid changed during command execution; matrix left untouched.");
        }
        consumeOneFromEachSlot(inventory, liveMatrix);
        return dispatchResult;
    }

    DeliveryResult deliver(Player player, Match match) {
        Recipe recipe = match.recipe();
        if (recipe.isCommand()) {
            return dispatchCommand(player, match);
        }
        PreparedOutput prepared = prepareTypedOutput(match);
        if (prepared.stack() == null) {
            return new DeliveryResult(DeliveryStatus.UNAVAILABLE, prepared.unavailableReason());
        }
        giveOrDrop(player, prepared.stack());
        return new DeliveryResult(DeliveryStatus.DELIVERED, "Delivered typed recipe output.");
    }

    private DeliveryResult dispatchCommand(Player player, Match match) {
        String command = formatCommand(match.recipe().command(), player, match.mob());
        if (connection != null && connection.isFolia()) {
            connection.scheduler().runOnServer(() -> commandDispatcher.dispatch(command));
            return new DeliveryResult(DeliveryStatus.COMMAND_SCHEDULED, "Scheduled best-effort recipe command on global scheduler.");
        }
        try {
            boolean accepted = commandDispatcher.dispatch(command);
            if (!accepted) {
                return new DeliveryResult(DeliveryStatus.COMMAND_REFUSED, "Command was refused or unknown.");
            }
            return new DeliveryResult(DeliveryStatus.COMMAND_DISPATCHED, "Best-effort recipe command dispatched.");
        } catch (RuntimeException failure) {
            return new DeliveryResult(DeliveryStatus.COMMAND_REFUSED, "Command failed: " + failure.getMessage());
        }
    }

    private static String formatCommand(String template, Player player, String mob) {
        return template.replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{mob}", mob)
                .replace("{mob_upper}", mob.toUpperCase(Locale.ROOT));
    }

    private PreparedOutput prepareTypedOutput(Match match) {
        Recipe recipe = match.recipe();
        if (recipe.spawner()) {
            var entityType = heads.type(match.mob());
            if (entityType == null) {
                return new PreparedOutput(null, "Unknown mob entity type for spawner.");
            }
            ItemStack output = new ItemStack(Material.SPAWNER);
            if (!(output.getItemMeta() instanceof BlockStateMeta meta)
                    || !(meta.getBlockState() instanceof CreatureSpawner spawner)) {
                return new PreparedOutput(null, "Spawner block metadata is unavailable.");
            }
            spawner.setSpawnedType(entityType);
            spawner.getPersistentDataContainer().set(CRAFTED_SPAWNER_KEY, PersistentDataType.BYTE, (byte) 1);
            meta.setBlockState(spawner);
            meta.getPersistentDataContainer().set(CRAFTED_SPAWNER_KEY, PersistentDataType.BYTE, (byte) 1);
            output.setItemMeta(meta);
            return new PreparedOutput(output, "");
        }
        if (recipe.card()) {
            Cards service = cards.get();
            if (service == null) {
                return new PreparedOutput(null, "Curios card service is unavailable.");
            }
            ItemStack card = service.craft(match.mob(), recipe.weights()).orElse(null);
            if (card == null || card.getType().isAir()) {
                return new PreparedOutput(null, "No matching Curios card could be created for " + match.mob() + ".");
            }
            return new PreparedOutput(card, "");
        }
        ItemResult result = items.create(recipe.item().replace("{mob}", match.mob()), 1);
        ItemStack stack = result == null ? null : result.stack().orElse(null);
        if (stack == null || stack.getType().isAir()) {
            String reason = result == null ? "Item provider returned no result." : result.message();
            return new PreparedOutput(null, reason);
        }
        return new PreparedOutput(stack, "");
    }

    static boolean isCraftedSpawnerItem(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().getOrDefault(CRAFTED_SPAWNER_KEY, PersistentDataType.BYTE, (byte) 0) > 0) {
            return true;
        }
        return meta instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof CreatureSpawner spawner
                && spawner.getPersistentDataContainer().getOrDefault(CRAFTED_SPAWNER_KEY, PersistentDataType.BYTE, (byte) 0) > 0;
    }

    static EntityType craftedSpawnerType(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return null;
        if (item.getItemMeta() instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof CreatureSpawner spawner) {
            return spawner.getSpawnedType();
        }
        return null;
    }

    private static void giveOrDrop(Player player, ItemStack output) {
        player.getInventory().addItem(output).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }

    private static SlotFingerprint[] fingerprint(ItemStack[] matrix) {
        SlotFingerprint[] fp = new SlotFingerprint[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            ItemStack item = matrix[i];
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                fp[i] = new SlotFingerprint(Material.AIR, 0);
            } else {
                fp[i] = new SlotFingerprint(item.getType(), item.getAmount());
            }
        }
        return fp;
    }

    private boolean matchesSameRecipeAndSnapshot(ItemStack[] liveMatrix, Match expected, SlotFingerprint[] before) {
        if (liveMatrix == null || liveMatrix.length != before.length) return false;
        for (int i = 0; i < liveMatrix.length; i++) {
            ItemStack item = liveMatrix[i];
            Material mat = (item == null || item.getType().isAir() || item.getAmount() <= 0) ? Material.AIR : item.getType();
            int amt = mat == Material.AIR ? 0 : item.getAmount();
            if (before[i].material() != mat || before[i].amount() != amt) {
                return false;
            }
        }
        Match current = match(liveMatrix);
        return current != null
                && expected.recipe().id().equals(current.recipe().id())
                && expected.mob().equals(current.mob());
    }

    private static void consumeOneFromEachSlot(CraftingInventory inventory, ItemStack[] liveMatrix) {
        ItemStack[] updated = Arrays.copyOf(liveMatrix, liveMatrix.length);
        for (int i = 0; i < updated.length; i++) {
            ItemStack item = updated[i];
            if (item == null || item.getType().isAir()) continue;
            if (item.getAmount() <= 1) {
                updated[i] = null;
            } else {
                item.setAmount(item.getAmount() - 1);
            }
        }
        inventory.setMatrix(updated);
    }
}
