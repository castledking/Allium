package codes.castled.allium.ride;

import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.tool.BukkitParticles;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolActionHandler;
import codes.castled.allium.tool.powertool.PowerToolContext;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The powertool {@code claim_ban} action: pre-recode Allium's claim-ban countdown, for the claim
 * handcuffs. Once its player has restrained the target ({@code ride} with {@code restrain} and
 * {@code claim}), it counts down {@code countdown-seconds} (5; 1 to 60) with titles, a pulse and
 * blindness and slowness on both, then lets the target go and bans them from the claim through
 * {@link ClaimAccess}. Letting the target go first (the claim handcuffs' Q) cancels it, and so does
 * the target leaving the claim, either player leaving, or the claim changing. The effects either
 * had before are put back. Server thread.
 */
public final class ClaimBans implements Rides.RideListener {

    public static final int DEFAULT_SECONDS = 5;
    public static final int MAX_SECONDS = 60;

    private record Effects(@Nullable PotionEffect blindness, @Nullable PotionEffect slowness) {
    }

    private record Pending(UUID carrier, UUID target, String claimId, Effects carrierEffects, Effects targetEffects) {
    }

    private final Rides rides;
    private final ClaimAccess claims;
    private final TaskScheduler scheduler;
    private final Function<UUID, Player> players;
    private final RideActions.Notifier notifier;
    private final RideActions.Titles titles;
    private final Consumer<String> log;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> completing = new HashSet<>();

    public ClaimBans(Rides rides, ClaimAccess claims, TaskScheduler scheduler, Function<UUID, Player> players,
                     RideActions.Notifier notifier, RideActions.Titles titles, Consumer<String> log) {
        this.rides = rides;
        this.claims = claims;
        this.scheduler = scheduler;
        this.players = players;
        this.notifier = notifier;
        this.titles = titles;
        this.log = log;
        rides.addListener(this);
    }

    public PowerToolActionHandler action() {
        return this::start;
    }

    /** Whether a claim ban of {@code target} is counting down. */
    public boolean isPending(UUID target) {
        return pending.containsKey(target);
    }

    /** Cancels every countdown, putting the effects back (the module is stopping). */
    public void disable() {
        for (UUID target : List.copyOf(pending.keySet())) {
            cancel(target, null);
        }
    }

    boolean start(PowerToolContext context, PowerToolAction action) {
        Player carrier = context.player();
        Entity entity = context.targetEntity();
        if (!(entity instanceof Player target) || !rides.isRestrained(target.getUniqueId())
                || !carrier.getUniqueId().equals(rides.carrierOf(target.getUniqueId())) || pending.containsKey(target.getUniqueId())) {
            return false;
        }
        ClaimAccess.Check check = claims.check(carrier, target);
        if (!check.allowed()) {
            refuse(carrier, target, check, log);
            notifier.send(carrier, refusal(check.refusal()), "{target}", target.getName());
            rides.let(target.getUniqueId());
            notifier.send(target, Messages.RIDE_RELEASED_TARGET, "{player}", carrier.getName());
            return false;
        }
        int seconds = Math.max(1, Math.min(MAX_SECONDS, seconds(action.params().get("countdown-seconds"))));
        Pending entry = new Pending(carrier.getUniqueId(), target.getUniqueId(), check.claimId(), effects(carrier), effects(target));
        pending.put(target.getUniqueId(), entry);
        slowAndBlind(carrier, seconds);
        slowAndBlind(target, seconds);
        notifier.send(carrier, Messages.CLAIM_BAN_STARTED, "{seconds}", Integer.toString(seconds));
        notifier.send(target, Messages.CLAIM_BAN_STARTED_TARGET);
        tick(target.getUniqueId(), seconds, seconds);
        return true;
    }

    private void tick(UUID targetId, int remaining, int total) {
        Pending entry = pending.get(targetId);
        if (entry == null) {
            return;
        }
        Player target = players.apply(targetId);
        Player carrier = players.apply(entry.carrier());
        if (target == null || !target.isOnline() || carrier == null || !carrier.isOnline()
                || !entry.carrier().equals(rides.carrierOf(targetId))) {
            cancel(targetId, Messages.CLAIM_BAN_CANCELLED);
            return;
        }
        if (!claims.isInside(target, entry.claimId())) {
            cancel(targetId, Messages.CLAIM_BAN_LEFT);
            rides.let(targetId);
            notifier.send(target, Messages.RIDE_RELEASED_TARGET, "{player}", carrier.getName());
            return;
        }
        if (remaining <= 0) {
            complete(entry, target, carrier);
            return;
        }
        String seconds = Integer.toString(remaining);
        titles.show(carrier, Messages.CLAIM_BAN_TITLE, Messages.CLAIM_BAN_COUNTDOWN, 0, 25, 5, "{seconds}", seconds);
        titles.show(target, Messages.CLAIM_BAN_TARGET_TITLE, Messages.CLAIM_BAN_COUNTDOWN, 0, 25, 5, "{seconds}", seconds);
        pulse(carrier, target, total - remaining);
        scheduler.runOnServerLater(() -> tick(targetId, remaining - 1, total), 1000L);
    }

    private void complete(Pending entry, Player target, Player carrier) {
        UUID targetId = target.getUniqueId();
        if (pending.remove(targetId) == null) {
            return;
        }
        ClaimAccess.Check last = claims.check(carrier, target);
        restore(carrier, entry.carrierEffects());
        restore(target, entry.targetEffects());
        // Let them go first; the ban is only written once they are off.
        completing.add(targetId);
        try {
            rides.let(targetId);
        } finally {
            completing.remove(targetId);
        }
        if (!last.allowed() || !entry.claimId().equals(last.claimId())) {
            notifier.send(carrier, Messages.CLAIM_BAN_CHANGED, "{target}", target.getName());
            notifier.send(target, Messages.RIDE_RELEASED_TARGET, "{player}", carrier.getName());
            return;
        }
        if (claims.ban(entry.claimId(), target, carrier)) {
            log.accept(carrier.getName() + " claim-banned " + target.getName() + " from claim " + entry.claimId() + " with the claim handcuffs.");
            notifier.send(carrier, Messages.CLAIM_BAN_DONE, "{target}", target.getName());
            titles.show(carrier, Messages.CLAIM_BAN_DONE_TITLE, Messages.CLAIM_BAN_DONE_SUBTITLE, 5, 45, 10, "{target}", target.getName());
            titles.show(target, Messages.CLAIM_BAN_BANNED_TITLE, Messages.CLAIM_BAN_BANNED_SUBTITLE, 5, 45, 10);
            sound(carrier, "BLOCK_BEACON_ACTIVATE", 1.0F, 0.75F);
        } else {
            notifier.send(carrier, Messages.CLAIM_BAN_FAILED, "{target}", target.getName());
            notifier.send(target, Messages.CLAIM_BAN_FAILED_TARGET);
        }
    }

    @Override
    public void released(UUID rider, UUID carrier) {
        if (!completing.contains(rider) && pending.containsKey(rider)) {
            cancel(rider, Messages.CLAIM_BAN_CANCELLED);
        }
    }

    private void cancel(UUID targetId, @Nullable Messages reason) {
        Pending entry = pending.remove(targetId);
        if (entry == null) {
            return;
        }
        Player target = players.apply(targetId);
        Player carrier = players.apply(entry.carrier());
        if (target != null) {
            restore(target, entry.targetEffects());
            target.resetTitle();
        }
        if (carrier != null) {
            restore(carrier, entry.carrierEffects());
            carrier.resetTitle();
            if (reason != null) {
                notifier.send(carrier, reason, "{target}", target == null ? "" : target.getName());
            }
        }
    }

    // -- Effects ----------------------------------------------------------------------------------

    @Nullable
    private static PotionEffectType effect(String key) {
        try {
            @SuppressWarnings("deprecation")
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(key));
            return type;
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private static Effects effects(Player player) {
        PotionEffectType blindness = effect("blindness");
        PotionEffectType slowness = effect("slowness");
        return new Effects(blindness == null ? null : player.getPotionEffect(blindness),
                slowness == null ? null : player.getPotionEffect(slowness));
    }

    private static void slowAndBlind(Player player, int seconds) {
        int ticks = (seconds + 1) * 20;
        PotionEffectType blindness = effect("blindness");
        PotionEffectType slowness = effect("slowness");
        if (blindness != null) {
            player.removePotionEffect(blindness);
            player.addPotionEffect(new PotionEffect(blindness, ticks, 0, false, false, true));
        }
        if (slowness != null) {
            player.removePotionEffect(slowness);
            player.addPotionEffect(new PotionEffect(slowness, ticks, 6, false, false, true));
        }
    }

    private static void restore(Player player, Effects before) {
        PotionEffectType blindness = effect("blindness");
        PotionEffectType slowness = effect("slowness");
        if (blindness != null) {
            player.removePotionEffect(blindness);
            if (before.blindness() != null) player.addPotionEffect(before.blindness());
        }
        if (slowness != null) {
            player.removePotionEffect(slowness);
            if (before.slowness() != null) player.addPotionEffect(before.slowness());
        }
    }

    private static void pulse(Player carrier, Player target, int elapsed) {
        for (Player player : List.of(carrier, target)) {
            Location at = player.getLocation().add(0.0, 1.0, 0.0);
            if (at.getWorld() != null) {
                BukkitParticles.resolve("enchant").ifPresent(p -> at.getWorld().spawnParticle(p, at, 12 + elapsed * 4, 0.5, 0.7, 0.5, 0.06));
                BukkitParticles.resolve("smoke").ifPresent(p -> at.getWorld().spawnParticle(p, at, 6, 0.3, 0.45, 0.3, 0.01));
            }
            sound(player, "BLOCK_NOTE_BLOCK_HAT", 0.8F, 0.7F + elapsed * 0.12F);
        }
    }

    private static void sound(Player player, String name, float volume, float pitch) {
        try {
            Sound sound = (Sound) Sound.class.getField(name).get(null);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // a sound this version does not have
        }
    }

    // -- Helpers ----------------------------------------------------------------------------------

    private static int seconds(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? DEFAULT_SECONDS : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            return DEFAULT_SECONDS;
        }
    }

    /** The lang message for a failed claim check. */
    static Messages refusal(@Nullable ClaimAccess.Refusal refusal) {
        if (refusal == null) {
            return Messages.CLAIM_CUFFS_UNAVAILABLE;
        }
        return switch (refusal) {
            case UNAVAILABLE -> Messages.CLAIM_CUFFS_UNAVAILABLE;
            case NOT_IN_CLAIM -> Messages.CLAIM_CUFFS_NOT_IN_CLAIM;
            case NO_CLAIM_ID -> Messages.CLAIM_CUFFS_NO_CLAIM_ID;
            case NOT_MANAGER -> Messages.CLAIM_CUFFS_NOT_MANAGER;
            case TRUSTED -> Messages.CLAIM_CUFFS_TRUSTED;
            case ALREADY_BANNED -> Messages.CLAIM_CUFFS_ALREADY_BANNED;
        };
    }

    /** Logs why GPExpansion could not answer, once per refusal, for the console. */
    static void refuse(Player carrier, Player target, ClaimAccess.Check check, Consumer<String> log) {
        if (check.refusal() == ClaimAccess.Refusal.UNAVAILABLE && check.detail() != null) {
            log.accept("Claim handcuffs: GPExpansion could not check " + target.getName() + " for " + carrier.getName() + ": " + check.detail());
        }
    }
}
