package codes.castled.crops;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.command.spec.*;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * {@code /crops} (aliases {@code /crop}, {@code /hf}, {@code /harvest}) command handler and
 * dynamic tab completer for the Crops & Kitchen addon.
 */
final class CropsCommand {

    private static final int MAX_GIVE_AMOUNT = 2304;
    private static final List<String> PLAYER_ACTIONS = List.of("help", "list", "inspect");
    private static final List<String> ADMIN_ACTIONS = List.of("give", "plant", "grow", "remove", "nearby", "status", "migrate", "crop");
    private static final List<String> CROP_SUBACTIONS = List.of("inspect", "plant", "grow", "remove", "nearby");

    private final CropsPlugin plugin;
    private final ModelWorld world;
    private final Planting planting;
    private final Kitchen kitchen;
    private final LegacyHarvestMigrationService migration;
    private final List<String> itemIds;
    private final Set<String> itemIdSet;

    CropsCommand(CropsPlugin plugin, ModelWorld world, Planting planting, Kitchen kitchen,
                 LegacyHarvestMigrationService migration, List<String> itemIds) {
        this.plugin = plugin;
        this.world = world;
        this.planting = planting;
        this.kitchen = kitchen;
        this.migration = migration;
        this.itemIds = List.copyOf(itemIds);
        this.itemIdSet = new LinkedHashSet<>(itemIds);
    }

    CommandSpec spec() {
        List<String> all = new ArrayList<>(PLAYER_ACTIONS);
        all.addAll(ADMIN_ACTIONS);
        return new CommandSpec(
                "crops", "crops", "crops",
                List.of("crop", "hf", "harvest"),
                "Inspect, give, plant, grow, and manage custom crops and kitchen items",
                "/crops [list|inspect|give|plant|grow|remove|nearby|status]",
                "/crops [list|inspect|give|plant|grow|remove|nearby|status]",
                "crops.use", null,
                List.of(CommandArgSpec.optional("action", null, SuggestionSpec.literals(all, null))),
                List.of(),
                CommandMessageKeys.ofOutcomes(Map.of())
        );
    }

    void execute(AddonCommands.Context ctx) {
        CommandSender sender = ctx.sender();
        String[] args = ctx.args();
        if (args.length == 0) {
            sendHelp(sender, ctx.label());
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        // Legacy /harvest crop <inspect|plant|grow|remove|nearby> compatibility
        if ("crop".equals(action)) {
            if (args.length < 2) {
                sendHelp(sender, ctx.label());
                return;
            }
            args = Arrays.copyOfRange(args, 1, args.length);
            action = args[0].toLowerCase(Locale.ROOT);
        }
        switch (action) {
            case "help" -> sendHelp(sender, ctx.label());
            case "list" -> list(sender, args);
            case "inspect" -> inspect(sender);
            case "give" -> give(sender, args);
            case "plant" -> plant(sender, args);
            case "grow" -> grow(sender, args);
            case "remove" -> remove(sender);
            case "nearby" -> nearby(sender, args);
            case "status" -> status(sender);
            case "migrate" -> migrate(sender, args);
            default -> sendHelp(sender, ctx.label());
        }
    }

    List<String> complete(AddonCommands.Context ctx) {
        CommandSender sender = ctx.sender();
        String[] args = ctx.args();
        boolean admin = isAdmin(sender);
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> actions = new ArrayList<>(PLAYER_ACTIONS);
            if (admin) actions.addAll(ADMIN_ACTIONS);
            return actions.stream().filter(a -> a.startsWith(prefix)).toList();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if ("crop".equals(action)) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return CROP_SUBACTIONS.stream()
                        .filter(a -> "inspect".equals(a) || admin)
                        .filter(a -> a.startsWith(prefix))
                        .toList();
            }
            args = Arrays.copyOfRange(args, 1, args.length);
            action = args[0].toLowerCase(Locale.ROOT);
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> args.length == 2
                    ? List.of("crops", "pies", "items").stream().filter(s -> s.startsWith(prefix)).toList()
                    : List.of();
            case "give" -> completeGive(sender, args, admin, prefix);
            case "plant" -> completePlant(args, admin, prefix);
            case "grow" -> admin && args.length == 2
                    ? List.of("1", "2", "3", "max").stream().filter(s -> s.startsWith(prefix)).toList()
                    : List.of();
            case "nearby" -> admin && args.length == 2
                    ? List.of("8", "16", "32", "64").stream().filter(s -> s.startsWith(prefix)).toList()
                    : List.of();
            case "migrate" -> completeMigrate(args, admin, prefix);
            default -> List.of();
        };
    }

    private List<String> completeGive(CommandSender sender, String[] args, boolean admin, String prefix) {
        if (!admin) return List.of();
        if (args.length == 2) {
            List<String> out = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if ((!(sender instanceof Player p) || p.canSee(online))
                        && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
            out.addAll(completeGiveItems(prefix));
            return out;
        }
        boolean firstIsPlayer = !isKnownItem(args[1]) && Bukkit.getPlayerExact(args[1]) != null;
        if (args.length == 3) {
            if (firstIsPlayer) {
                return completeGiveItems(prefix);
            }
            return List.of("1", "16", "64").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 4 && firstIsPlayer) {
            return List.of("1", "16", "64").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private List<String> completeGiveItems(String prefix) {
        String namespace = prefix.contains(":") ? "allium:" : "";
        return itemIds.stream().map(id -> namespace + id).filter(id -> id.startsWith(prefix)).toList();
    }

    private List<String> completePlant(String[] args, boolean admin, String prefix) {
        if (!admin) return List.of();
        if (args.length == 2) {
            return planting.cropIds().stream().filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length == 3) {
            Catalog.Crop crop = planting.crop(args[1]);
            if (crop != null) {
                return crop.paths().keySet().stream().filter(p -> p.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
        }
        return List.of();
    }

    private void sendHelp(CommandSender sender, String label) {
        String cmd = "/" + (label == null || label.isBlank() ? "crops" : label);
        tell(sender, "&7" + cmd + " list [crops|pies|items] &8- &fList crops, pies, or items");
        tell(sender, "&7" + cmd + " inspect &8- &fInspect the crop or kitchen item you are looking at");
        if (isAdmin(sender)) {
            tell(sender, "&7" + cmd + " give [player] <item> [amount] &8- &fGive a Crops or Kitchen item");
            tell(sender, "&7" + cmd + " plant <crop> [path] &8- &fPlant a crop on the soil you are looking at");
            tell(sender, "&7" + cmd + " grow [stages|max] &8- &fAdvance the looked-at crop's growth");
            tell(sender, "&7" + cmd + " remove &8- &fRemove the looked-at crop or kitchen placement");
            tell(sender, "&7" + cmd + " nearby [radius] &8- &fList active placements nearby");
            tell(sender, "&7" + cmd + " status &8- &fShow loaded Crops & Kitchen counts");
            tell(sender, "&7" + cmd + " migrate [apply <runId>|status|<path>] &8- &fDry-run or apply legacy Harvest farm migration");
        }
    }

    private void list(CommandSender sender, String[] args) {
        String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "crops";
        switch (category) {
            case "crops" -> tell(sender, "&aCrops (" + planting.count() + "): &f" + String.join(", ", planting.cropIds()));
            case "pies" -> tell(sender, "&aKitchen Pies (" + kitchen.pieCount() + "): &f" + String.join(", ", kitchen.pieIds()));
            case "items" -> {
                int shown = Math.min(25, itemIds.size());
                String suffix = itemIds.size() > shown ? " &7(+" + (itemIds.size() - shown) + " more; use tab completion with /crops give)" : "";
                tell(sender, "&aItems (" + itemIds.size() + "): &f" + String.join(", ", itemIds.subList(0, shown)) + suffix);
            }
            default -> tell(sender, "&cUsage: /crops list [crops|pies|items]");
        }
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, "&cThis command requires a player.");
            return;
        }
        ModelWorld.Placed placed = targetPlacement(player);
        if (placed == null) {
            tell(player, "&cNo planted crop or kitchen item in sight (look at it or its soil).");
            return;
        }
        if ("crop".equals(placed.kind)) {
            Catalog.Crop crop = planting.crop(placed.type);
            Catalog.Path path = planting.pathOf(placed);
            int totalStages = path == null ? 0 : path.stages().size();
            boolean mature = totalStages > 0 && placed.stage + 1 >= totalStages;
            String growth = mature ? "&aMature" : "&eNext stage in " + formatDuration(Math.max(0L, placed.due - System.currentTimeMillis()));
            tell(player, "&aCrop: &f" + placed.type + " &7path=&6" + placed.path
                    + " &7stage=&f" + (placed.stage + 1) + "/" + totalStages + " &7(" + growth + "&7)");
            if (crop != null) {
                Block soil = placed.cell().getRelative(BlockFace.DOWN);
                boolean hydrated = !crop.water() || planting.isHydrated(soil);
                int light = placed.cell().getLightLevel();
                tell(player, "&7Soil: &f" + soil.getType().name().toLowerCase(Locale.ROOT)
                        + " &7water=" + (hydrated ? "&aok" : "&cneeded")
                        + " &7light=" + (light >= crop.light() ? "&a" : "&c") + light + "/" + crop.light());
            }
        } else {
            tell(player, "&aKitchen (" + placed.kind + "): &f" + placed.type
                    + " &7state=&6" + placed.path + " &7stage=&f" + placed.stage);
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        if (args.length < 2 || args.length > 4) {
            tell(sender, "&cUsage: /crops give [player] <item> [amount]");
            return;
        }
        Player target;
        int itemIndex;
        if (!isKnownItem(args[1]) && args.length >= 3) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null || (sender instanceof Player p && !p.canSee(target))) {
                tell(sender, "&cPlayer '" + args[1] + "' is not online.");
                return;
            }
            itemIndex = 2;
        } else {
            if (!(sender instanceof Player p)) {
                tell(sender, "&cConsole must specify a player: /crops give <player> <item> [amount]");
                return;
            }
            target = p;
            itemIndex = 1;
        }
        String rawItem = args[itemIndex].toLowerCase(Locale.ROOT);
        String itemId = rawItem.startsWith("allium:") ? rawItem.substring("allium:".length()) : rawItem;
        if (!itemIdSet.contains(itemId)) {
            tell(sender, "&cUnknown Crops item '" + args[itemIndex] + "'.");
            return;
        }
        int amount = 1;
        if (args.length > itemIndex + 1) {
            amount = parsePositiveInt(args[itemIndex + 1], -1);
            if (amount < 1 || amount > MAX_GIVE_AMOUNT) {
                tell(sender, "&cAmount must be 1.." + MAX_GIVE_AMOUNT + ".");
                return;
            }
        }
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(64, remaining);
            ItemStack stack = plugin.item("allium:" + itemId, batch);
            if (stack == null) {
                tell(sender, "&cCould not create allium:" + itemId + ".");
                return;
            }
            plugin.give(target, stack);
            remaining -= batch;
        }
        tell(sender, "&aGave &f" + amount + "x " + itemId + " &ato &f" + target.getName() + "&a.");
    }

    private void plant(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "&cThis command requires a player.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            tell(player, "&cUsage: /crops plant <crop> [path]");
            return;
        }
        Catalog.Crop crop = planting.crop(args[1]);
        if (crop == null) {
            tell(player, "&cUnknown crop '" + args[1] + "'.");
            return;
        }
        Block soil = player.getTargetBlockExact(6);
        if (soil == null) {
            tell(player, "&cLook at a soil block within 6 blocks.");
            return;
        }
        String requestedPath = args.length == 3 ? args[2] : null;
        String error = planting.plantDirect(player, crop, soil, requestedPath);
        if (error != null) {
            tell(player, "&c" + error);
        } else {
            tell(player, "&aPlanted &f" + crop.id() + "&a.");
        }
    }

    private void grow(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "&cThis command requires a player.");
            return;
        }
        ModelWorld.Placed placed = targetPlacement(player);
        if (placed == null || !"crop".equals(placed.kind)) {
            tell(player, "&cNo planted crop in sight.");
            return;
        }
        int stages = 1;
        if (args.length >= 2) {
            if ("max".equalsIgnoreCase(args[1])) {
                stages = 100;
            } else {
                stages = parsePositiveInt(args[1], -1);
                if (stages < 1) {
                    tell(player, "&cUsage: /crops grow [stages|max]");
                    return;
                }
            }
        }
        int advanced = planting.forceGrow(placed, stages);
        Catalog.Path path = planting.pathOf(placed);
        int total = path == null ? placed.stage + 1 : path.stages().size();
        if (advanced <= 0) {
            tell(player, "&e" + placed.type + " is already mature (stage " + (placed.stage + 1) + "/" + total + ").");
        } else {
            tell(player, "&aAdvanced &f" + placed.type + " &aby " + advanced + " stage(s) to &f" + (placed.stage + 1) + "/" + total + "&a.");
        }
    }

    private void remove(CommandSender sender) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "&cThis command requires a player.");
            return;
        }
        ModelWorld.Placed placed = targetPlacement(player);
        if (placed == null) {
            tell(player, "&cNo planted crop or kitchen placement in sight.");
            return;
        }
        world.remove(placed);
        tell(player, "&aRemoved &f" + placed.kind + " (" + placed.type + ")&a.");
    }

    private void nearby(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "&cThis command requires a player.");
            return;
        }
        int radius = args.length >= 2 ? Math.max(1, Math.min(128, parsePositiveInt(args[1], 16))) : 16;
        UUID worldId = player.getWorld().getUID();
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        List<ModelWorld.Placed> found = new ArrayList<>();
        for (ModelWorld.Placed p : world.active()) {
            if (!p.active || !p.cell.world().equals(worldId)) continue;
            if (Math.abs(p.cell.x() - px) <= radius && Math.abs(p.cell.z() - pz) <= radius) {
                found.add(p);
            }
        }
        tell(player, "&a" + found.size() + " placement(s) within " + radius + " blocks:");
        found.stream().limit(12).forEach(p -> tell(player, "&7- " + p.kind + ": &f" + p.type + "/" + p.path + " &7stage " + (p.stage + 1)
                + " @ " + p.cell.x() + "," + p.cell.y() + "," + p.cell.z()));
    }

    private void status(CommandSender sender) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        tell(sender, "&aCrops: &f" + planting.count() + " &7| &aKitchen pies: &f" + kitchen.pieCount()
                + " &7| &aItems: &f" + itemIds.size() + " &7| &aActive loaded placements: &f" + world.active().size()
                + " &7| &acrop-model-in-jade: &f" + plugin.cropModelInJade()
                + (migration != null && migration.pendingEntryCount() > 0
                ? " &7| &ePending legacy migration: &f" + migration.pendingEntryCount() + " (" + migration.pendingChunkCount() + " chunks)" : ""));
    }

    private void migrate(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            tell(sender, "&cYou do not have permission.");
            return;
        }
        if (migration == null) {
            tell(sender, "&cMigration service is unavailable.");
            return;
        }
        if (args.length == 1) {
            migration.dryRun(sender, null);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if ("status".equals(sub)) {
            migration.status(sender);
            return;
        }
        if ("apply".equals(sub)) {
            if (args.length < 3) {
                tell(sender, "&eUsage: /crops migrate apply <runId>");
                return;
            }
            migration.apply(sender, args[2]);
            return;
        }
        migration.dryRun(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    private List<String> completeMigrate(String[] args, boolean admin, String prefix) {
        if (!admin || migration == null) return List.of();
        if (args.length == 2) {
            return List.of("apply", "status").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 3 && "apply".equalsIgnoreCase(args[1])) {
            return migration.recentRuns().stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private ModelWorld.Placed targetPlacement(Player player) {
        var ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), 6.0,
                entity -> world.get(entity) != null);
        if (ray != null && ray.getHitEntity() != null) {
            ModelWorld.Placed hit = world.get(ray.getHitEntity());
            if (hit != null) return hit;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) return null;
        ModelWorld.Placed direct = world.at(block);
        if (direct != null) return direct;
        return world.at(block.getRelative(BlockFace.UP));
    }

    private boolean isKnownItem(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("allium:")) normalized = normalized.substring("allium:".length());
        return itemIdSet.contains(normalized);
    }

    private static boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("crops.admin") || sender.isOp();
    }

    private void tell(CommandSender sender, String message) {
        plugin.connection().text().send(sender, "&a[Crops] &f" + message);
    }

    static int parsePositiveInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static String formatDuration(long millis) {
        long seconds = (millis + 999L) / 1000L;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remSec = seconds % 60;
        if (minutes < 60) return remSec == 0 ? minutes + "m" : minutes + "m " + remSec + "s";
        long hours = minutes / 60;
        long remMin = minutes % 60;
        return remMin == 0 ? hours + "h" : hours + "h " + remMin + "m";
    }
}
