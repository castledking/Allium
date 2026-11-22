package codes.castled.allium.ride;

import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolActionHandler;
import codes.castled.allium.tool.powertool.PowerToolContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The powertool {@code ride}, {@code mount} and {@code release} actions, over {@link Rides}.
 *
 * <ul>
 *   <li>{@code ride}: the target rides the player, on top of whoever already does. Params:
 *       {@code restrain} (false; true restrains a player, as the Handcuffs do), {@code max-riders}
 *       (5, 1 to 16), {@code entity-types} (any living entity), {@code exempt-permission} (targets
 *       holding it cannot be picked up).</li>
 *   <li>{@code mount}: the player rides the target. Params: {@code entity-types},
 *       {@code exempt-permission}.</li>
 *   <li>{@code release}: lets go of the player's riders. Param {@code which}: {@code all} (default)
 *       or {@code last}. When it lets someone go on a {@code drop}, the item is kept.</li>
 * </ul>
 * Mobs are only picked up or ridden where protection plugins allow it; players are not claim
 * protected, and vanished players cannot be targeted by those who cannot see them.
 */
public final class RideActions {

    /** Sends a lang message to someone. */
    @FunctionalInterface
    public interface Notifier {
        void send(CommandSender recipient, Messages message, String... replacements);
    }

    /** Shows a title to a player. */
    @FunctionalInterface
    public interface Titles {
        void show(Player player, Messages title, Messages subtitle, String... replacements);
    }

    public static final int DEFAULT_MAX_RIDERS = 5;
    public static final int MAX_RIDERS = 16;

    private final Rides rides;
    private final ProtectionService protection;
    private final Notifier notifier;
    private final Titles titles;
    private final Function<Entity, String> names;

    public RideActions(Rides rides, ProtectionService protection, Notifier notifier, Titles titles, Function<Entity, String> names) {
        this.rides = rides;
        this.protection = protection;
        this.notifier = notifier;
        this.titles = titles;
        this.names = names;
    }

    public PowerToolActionHandler ride() {
        return this::ride;
    }

    public PowerToolActionHandler mount() {
        return this::mount;
    }

    public PowerToolActionHandler release() {
        return this::release;
    }

    boolean ride(PowerToolContext context, PowerToolAction action) {
        Player player = context.player();
        Entity target = context.targetEntity();
        if (!eligible(player, target, action, "ride")) {
            return false;
        }
        Map<String, Object> params = action.params();
        boolean restrain = bool(params.get("restrain"));
        String name = names.apply(target);
        if (restrain && !(target instanceof Player)) {
            notifier.send(player, Messages.RIDE_PLAYERS_ONLY, "{target}", name);
            return false;
        }
        int max = Math.max(1, Math.min(MAX_RIDERS, integer(params.get("max-riders"), DEFAULT_MAX_RIDERS)));
        Rides.Result result = rides.carry(player, target, restrain, max);
        switch (result) {
            case RESTRAINED -> {
                Player victim = (Player) target;
                notifier.send(player, Messages.RIDE_RESTRAINED, "{target}", name);
                notifier.send(victim, Messages.RIDE_RESTRAINED_TARGET, "{player}", player.getName());
                titles.show(victim, Messages.RIDE_RESTRAINED_TITLE, Messages.RIDE_RESTRAINED_SUBTITLE, "{player}", player.getName());
                return true;
            }
            case CARRIED -> {
                notifier.send(player, Messages.RIDE_CARRYING, "{target}", name);
                if (target instanceof Player carried) {
                    notifier.send(carried, Messages.RIDE_CARRIED_TARGET, "{player}", player.getName());
                }
                return true;
            }
            case FULL -> notifier.send(player, Messages.RIDE_FULL, "{max}", Integer.toString(max));
            case BUSY -> notifier.send(player, Messages.RIDE_BUSY, "{target}", name);
            default -> {
                // SELF, FAILED: nothing to say
            }
        }
        return false;
    }

    boolean mount(PowerToolContext context, PowerToolAction action) {
        Player player = context.player();
        Entity target = context.targetEntity();
        if (!eligible(player, target, action, "mount")) {
            return false;
        }
        if (rides.isRestrained(player.getUniqueId()) || rides.isRestrained(target.getUniqueId())
                || target.getUniqueId().equals(player.getVehicle() == null ? null : player.getVehicle().getUniqueId())) {
            notifier.send(player, Messages.RIDE_BUSY, "{target}", names.apply(target));
            return false;
        }
        if (rides.isCarried(player.getUniqueId())) {
            rides.let(player.getUniqueId());
        }
        player.leaveVehicle();
        return target.addPassenger(player);
    }

    boolean release(PowerToolContext context, PowerToolAction action) {
        Player player = context.player();
        Object which = action.params().get("which");
        boolean all = which == null || !"last".equalsIgnoreCase(String.valueOf(which).trim());
        List<Entity> released = rides.release(player, all);
        if (released.isEmpty()) {
            return false;
        }
        for (Entity entity : released) {
            notifier.send(player, Messages.RIDE_RELEASED, "{target}", names.apply(entity));
            if (entity instanceof Player rider && rider.isOnline()) {
                notifier.send(rider, Messages.RIDE_RELEASED_TARGET, "{player}", player.getName());
            }
        }
        // The handcuffs' drop lets go; the handcuffs stay in hand.
        if (context.causeEvent() instanceof PlayerDropItemEvent drop) {
            drop.setCancelled(true);
        }
        return true;
    }

    /** A living target, of an allowed type, not exempt, visible, and (for mobs) not protected. */
    private boolean eligible(Player player, @Nullable Entity target, PowerToolAction action, String verb) {
        if (target == null || !target.isValid() || !(target instanceof LivingEntity) || target.getUniqueId().equals(player.getUniqueId())) {
            return false;
        }
        List<String> types = strings(action.params().get("entity-types"));
        if (!types.isEmpty() && !types.contains(target.getType().name().toLowerCase(Locale.ROOT))
                && !types.contains(target.getType().getKey().toString())) {
            return false;
        }
        if (target instanceof Player other) {
            if (!PlayerNameVisibility.canSeeTarget(player, other)) {
                return false;
            }
            Object exempt = action.params().get("exempt-permission");
            if (exempt != null && !String.valueOf(exempt).isBlank() && other.hasPermission(String.valueOf(exempt).trim())) {
                notifier.send(player, Messages.RIDE_EXEMPT, "{target}", other.getName());
                return false;
            }
            return true;
        }
        if (!protection.checkEntityInteraction(player, target, verb, null).allowed()) {
            notifier.send(player, Messages.RIDE_PROTECTED, "{target}", names.apply(target));
            return false;
        }
        return true;
    }

    private static boolean bool(@Nullable Object value) {
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int integer(@Nullable Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** A list param, or a comma-separated text one, in lower case. */
    static List<String> strings(@Nullable Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim().toLowerCase(Locale.ROOT));
                }
            }
        } else if (value != null) {
            for (String part : String.valueOf(value).split("[,\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
