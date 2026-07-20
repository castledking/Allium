package codes.castled.allium.harvest.command;

import codes.castled.allium.harvest.HarvestBranding;
import codes.castled.allium.harvest.HarvestModule;
import codes.castled.allium.harvest.crop.CropInstance;
import codes.castled.allium.harvest.crop.CropPlacementService;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.ValidationIssue;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.Durations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /harvest} admin/debug command tree:
 * reload | give | crop … | spawner … | debug
 */
public final class HarvestCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final HarvestModule module;

    public HarvestCommand(HarvestModule module) {
        this.module = module;
    }

    private static boolean can(CommandSender sender, String node) {
        return sender.hasPermission(HarvestBranding.PERMISSION_ROOT + "." + node)
            || sender.hasPermission(HarvestBranding.PERMISSION_ROOT + ".admin");
    }

    private static void msg(CommandSender sender, String miniMessage) {
        sender.sendMessage(MM.deserialize("<gradient:#7bd88f:#4fa86a>[Harvest]</gradient> " + miniMessage));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!module.isEnabled()) {
            msg(sender, "<red>The harvest module is disabled (see harvest/config.yml).</red>");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "give" -> give(sender, args);
            case "crop" -> crop(sender, args);
            case "spawner" -> spawner(sender, args);
            case "soil" -> soil(sender, args);
            case "debug" -> debug(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        msg(sender, "<gray>/harvest reload | give <player> <item> [amount] | debug</gray>");
        msg(sender, "<gray>/harvest crop inspect|plant <crop> [path]|grow [stages]|remove|nearby [radius]</gray>");
        msg(sender, "<gray>/harvest spawner inspect|refresh|rescan <radius>|removevisual</gray>");
        msg(sender, "<gray>/harvest soil inspect|restore <duration>|forget</gray>");
    }

    // ==================== subcommands ====================

    private void reload(CommandSender sender) {
        if (!can(sender, "reload")) { noPerm(sender); return; }
        // module.reload() already logged every issue to the console in full;
        // chat only gets a summary so a broken file cannot flood the player.
        List<ValidationIssue> issues = module.reload();
        long errors = issues.stream().filter(ValidationIssue::isError).count();
        long warnings = issues.size() - errors;

        String loaded = "<green>Reload successful</green> <gray>—</gray> "
            + module.registry().size() + " crop(s), "
            + module.spawnerModels().size() + " spawner model(s)";
        if (errors > 0 || warnings > 0) {
            StringBuilder problems = new StringBuilder();
            if (errors > 0) {
                problems.append("<red>").append(errors).append(" error(s)</red>");
            }
            if (warnings > 0) {
                if (errors > 0) problems.append("<gray>, </gray>");
                problems.append("<yellow>").append(warnings).append(" warning(s)</yellow>");
            }
            msg(sender, loaded + " <gray>—</gray> " + problems + "<gray>; see console.</gray>");
            if (errors > 0) {
                msg(sender, "<gray>Entries with errors were skipped; everything else applied.</gray>");
            }
        } else {
            msg(sender, loaded + "<gray>.</gray>");
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!can(sender, "give")) { noPerm(sender); return; }
        if (args.length < 3) {
            msg(sender, "<red>Usage: /harvest give <player> <item> [amount]</red>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "<red>Player '" + args[1] + "' is not online.</red>");
            return;
        }
        int amount = args.length > 3 ? parseInt(args[3], 1) : 1;
        ItemRef ref;
        try {
            ref = ItemRef.parse(args[2]);
        } catch (IllegalArgumentException e) {
            msg(sender, "<red>" + e.getMessage() + "</red>");
            return;
        }
        Optional<ItemStack> stack = module.items().create(ref, amount);
        if (stack.isEmpty()) {
            msg(sender, "<red>Item '" + ref + "' does not exist.</red>");
            return;
        }
        target.getInventory().addItem(stack.get())
            .values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        msg(sender, "<green>Gave " + amount + "× " + ref + " to " + target.getName() + ".</green>");
    }

    private void crop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Crop commands are player-only.</red>");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "inspect";
        switch (sub) {
            case "inspect" -> {
                if (!can(sender, "crop.inspect")) { noPerm(sender); return; }
                targetCrop(player).ifPresentOrElse(crop -> {
                    msg(player, "<green>Crop:</green> <white>" + crop.cropId() + "</white>"
                        + " <gray>path=</gray><gold>" + crop.pathId() + "</gold>"
                        + " <gray>stage=</gray>" + crop.stage()
                        + " <gray>state=</gray>" + crop.state()
                        + (crop.fertilizerId() != null ? " <gray>fertilizer=</gray>" + crop.fertilizerId() : ""));
                    if (crop.nextGrowthAt() > 0) {
                        msg(player, "<gray>Next stage in "
                            + Durations.format(Math.max(0, crop.nextGrowthAt() - System.currentTimeMillis()))
                            + "</gray>");
                    }
                    msg(player, "<gray>Instance " + crop.instanceId() + ", cells=" + crop.cells().size() + "</gray>");
                }, () -> msg(player, "<red>No crop in sight (look at the crop or its soil).</red>"));
            }
            case "plant" -> {
                if (!can(sender, "crop.plant.command")) { noPerm(sender); return; }
                if (args.length < 3) {
                    msg(player, "<red>Usage: /harvest crop plant <crop> [path]</red>");
                    return;
                }
                Optional<CropDefinition> definition = module.registry().crop(args[2].toLowerCase(Locale.ROOT));
                if (definition.isEmpty()) {
                    msg(player, "<red>Unknown crop '" + args[2] + "'.</red>");
                    return;
                }
                Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    msg(player, "<red>Look at a soil block.</red>");
                    return;
                }
                String path = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : null;
                CropPlacementService.PlantResult result =
                    module.placement().plant(player, definition.get(), target, null, path);
                msg(player, result.success()
                    ? "<green>Planted '" + definition.get().id() + "' on path <gold>" + result.pathId() + "</gold>.</green>"
                    : "<red>" + (result.denyReason() == null ? "Cancelled." : result.denyReason()) + "</red>");
            }
            case "grow" -> {
                if (!can(sender, "crop.debug")) { noPerm(sender); return; }
                int stages = args.length > 2 ? parseInt(args[2], 1) : 1;
                targetCrop(player).ifPresentOrElse(crop -> {
                    module.growthEngine().forceGrow(player.getWorld(), crop, stages);
                    msg(player, "<green>Advanced to stage " + crop.stage()
                        + " (" + crop.state() + ").</green>");
                }, () -> msg(player, "<red>No crop in sight.</red>"));
            }
            case "remove" -> {
                if (!can(sender, "crop.debug")) { noPerm(sender); return; }
                targetCrop(player).ifPresentOrElse(crop -> {
                    module.harvests().removeCrop(crop,
                        codes.castled.allium.harvest.event.CropRemoveEvent.Reason.ADMIN);
                    msg(player, "<green>Crop removed.</green>");
                }, () -> msg(player, "<red>No crop in sight.</red>"));
            }
            case "nearby" -> {
                if (!can(sender, "crop.inspect")) { noPerm(sender); return; }
                int radius = args.length > 2 ? parseInt(args[2], 16) : 16;
                List<CropInstance> nearby = new ArrayList<>();
                module.instances().forEachLoaded(crop -> {
                    if (!crop.position().worldId().equals(player.getWorld().getUID())) return;
                    int dx = crop.position().x() - player.getLocation().getBlockX();
                    int dz = crop.position().z() - player.getLocation().getBlockZ();
                    if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) {
                        nearby.add(crop);
                    }
                });
                msg(player, "<green>" + nearby.size() + " crop(s) within " + radius + " blocks:</green>");
                nearby.stream().limit(10).forEach(crop -> msg(player,
                    "<gray>- " + crop.cropId() + "/" + crop.pathId() + " stage " + crop.stage()
                        + " @ " + crop.position().x() + "," + crop.position().y() + "," + crop.position().z() + "</gray>"));
            }
            default -> sendHelp(sender);
        }
    }

    private void spawner(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Spawner commands are player-only.</red>");
            return;
        }
        if (module.spawnerTracking() == null || !module.config().spawners().enabled()) {
            msg(player, "<red>Spawner models are disabled.</red>");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "inspect";
        Block target = player.getTargetBlockExact(6);
        switch (sub) {
            case "inspect" -> {
                if (!can(sender, "spawner.inspect")) { noPerm(sender); return; }
                if (target == null) { msg(player, "<red>Look at a spawner.</red>"); return; }
                module.spawnerTracking().trackedAt(keyOf(target)).ifPresentOrElse(spawner ->
                    msg(player, "<green>Tracked spawner:</green> <white>" + spawner.entityType() + "</white>"
                        + " <gray>visual=</gray>" + (spawner.visualEntityId() != null ? "yes" : "no")),
                    () -> msg(player, "<yellow>Not tracked. Use /harvest spawner refresh.</yellow>"));
            }
            case "refresh" -> {
                if (!can(sender, "spawner.refresh")) { noPerm(sender); return; }
                if (target == null) { msg(player, "<red>Look at a spawner.</red>"); return; }
                module.spawnerTracking().refresh(target);
                msg(player, "<green>Spawner visual re-converged.</green>");
            }
            case "rescan" -> {
                if (!can(sender, "spawner.rescan")) { noPerm(sender); return; }
                int radius = args.length > 2 ? Math.min(parseInt(args[2], 2), 8) : 2;
                int found = module.spawnerTracking().rescan(player.getLocation(), radius);
                msg(player, "<green>Rescanned " + ((radius * 2 + 1) * (radius * 2 + 1))
                    + " chunks, refreshed " + found + " spawner(s).</green>");
            }
            case "removevisual" -> {
                if (!can(sender, "spawner.refresh")) { noPerm(sender); return; }
                if (target == null) { msg(player, "<red>Look at a spawner.</red>"); return; }
                module.spawnerTracking().untrack(player.getWorld(), keyOf(target));
                msg(player, "<green>Visual removed and spawner untracked.</green>");
            }
            default -> sendHelp(sender);
        }
    }

    private void soil(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Only players can use this.</red>");
            return;
        }
        if (!module.config().soil().enabled()) {
            msg(player, "<red>Soil tracking is disabled (see harvest/config.yml).</red>");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            msg(player, "<red>Look at a soil block.</red>");
            return;
        }
        var key = keyOf(target);
        long now = System.currentTimeMillis();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "inspect";
        switch (sub) {
            case "inspect" -> {
                if (!can(sender, "soil.inspect")) { noPerm(sender); return; }
                module.soils().get(key).ifPresentOrElse(soil -> {
                    long remaining = soil.remainingMillis(now);
                    msg(player, soil.isExhausted(now)
                        ? "<red>Exhausted soil.</red> <gray>Restore it with a soil retainer.</gray>"
                        : "<green>Healthy soil</green> <gray>—</gray> <white>"
                            + (remaining == Long.MAX_VALUE ? "never wears out"
                                : codes.castled.allium.harvest.util.Durations.format(remaining) + " left")
                            + "</white>");
                    if (soil.fertilizerId() != null) {
                        msg(player, "<gray>Fertilizer worked in: </gray><white>"
                            + soil.fertilizerId() + "</white> <gray>(spent on the next planting)</gray>");
                    }
                }, () -> msg(player, "<yellow>Untracked — it starts its clock on first planting.</yellow>"));
            }
            case "restore" -> {
                if (!can(sender, "soil.modify")) { noPerm(sender); return; }
                String raw = args.length > 2 ? args[2] : "7d";
                long millis;
                try {
                    millis = codes.castled.allium.harvest.util.Durations.parseMillis(raw);
                } catch (IllegalArgumentException e) {
                    msg(player, "<red>" + e.getMessage() + "</red>");
                    return;
                }
                module.soils().retain(key, millis, now);
                msg(player, "<green>Soil restored — <white>"
                    + codes.castled.allium.harvest.util.Durations.format(
                        module.soils().remainingMillis(key, now))
                    + "</white> of life left.</green>");
            }
            case "forget" -> {
                if (!can(sender, "soil.modify")) { noPerm(sender); return; }
                module.soils().forget(key);
                msg(player, "<green>Soil forgotten — this spot now counts as new ground.</green>");
            }
            default -> sendHelp(sender);
        }
    }

    private void debug(CommandSender sender) {
        if (!can(sender, "crop.debug")) { noPerm(sender); return; }
        msg(sender, "<green>Harvest debug</green>");
        msg(sender, "<gray>Crop definitions: </gray>" + module.registry().size());
        msg(sender, "<gray>Loaded crop instances: </gray>" + module.instances().loadedCount());
        module.instances().countsByWorld().forEach((world, count) -> {
            var w = Bukkit.getWorld(world);
            msg(sender, "<gray>  " + (w != null ? w.getName() : world) + ": </gray>" + count);
        });
        msg(sender, "<gray>Due picked last pass: </gray>" + module.growthEngine().duePickedLastPass());
        msg(sender, "<gray>Growth pass time: </gray>"
            + (module.growthEngine().lastPassNanos() / 1_000_000.0) + " ms");
        msg(sender, "<gray>Pending crop writes: </gray>" + module.cropStorage().pendingWrites());
        msg(sender, "<gray>Crop visuals — duplicates removed: </gray>" + module.visuals().duplicatesRemoved()
            + "<gray>, orphans removed: </gray>" + module.visuals().orphansRemoved());
        if (module.spawnerTracking() != null) {
            msg(sender, "<gray>Tracked spawners (loaded): </gray>" + module.spawnerTracking().trackedCount());
            msg(sender, "<gray>Spawner visuals — duplicates removed: </gray>"
                + module.spawnerTracking().duplicatesRemoved()
                + "<gray>, orphans removed: </gray>" + module.spawnerTracking().orphansRemoved());
        }
    }

    // ==================== helpers ====================

    private Optional<CropInstance> targetCrop(Player player) {
        Block target = player.getTargetBlockExact(6);
        if (target == null) return Optional.empty();
        BlockPositionKey key = keyOf(target);
        return module.instances().at(key)
            .or(() -> module.instances().at(key.offset(0, 1, 0)));
    }

    private static BlockPositionKey keyOf(Block block) {
        return new BlockPositionKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void noPerm(CommandSender sender) {
        msg(sender, "<red>You do not have permission for that.</red>");
    }

    // ==================== tab completion ====================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("reload", "give", "crop", "spawner", "debug"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "crop" -> options.addAll(List.of("inspect", "plant", "grow", "remove", "nearby"));
                case "spawner" -> options.addAll(List.of("inspect", "refresh", "rescan", "removevisual"));
                case "soil" -> options.addAll(List.of("inspect", "restore", "forget"));
                case "give" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("crop") && args[1].equalsIgnoreCase("plant")) {
            module.registry().crops().forEach(crop -> options.add(crop.id()));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("crop") && args[1].equalsIgnoreCase("plant")) {
            module.registry().crop(args[2].toLowerCase(Locale.ROOT))
                .ifPresent(crop -> options.addAll(crop.paths().keySet()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
