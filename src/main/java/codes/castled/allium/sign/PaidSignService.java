package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.PaidActionOutcome;
import codes.castled.allium.economy.PaidActionResult;
import codes.castled.allium.economy.PaidActionService;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Paid sign adapter backed by {@link PaidActionService}: delegates reservation, revalidation,
 * typed delivery outcomes, disconnect/retirement handling, and capture/release settlement to the
 * shared paid-action lifecycle.
 */
public final class PaidSignService {

    @FunctionalInterface
    interface Action {
        /** Reports actual delivery, once. Deferred actions check validity again before delivering. */
        void deliver(BooleanSupplier valid, Consumer<Boolean> complete);
    }

    @FunctionalInterface
    interface TypedAction {
        /** Reports a typed delivery outcome, once. */
        void deliver(BooleanSupplier valid, Consumer<PaidActionOutcome> complete);
    }

    private final PluginStart plugin;
    private final PaidActionService actions;

    public PaidSignService(PluginStart plugin) {
        this.plugin = plugin;
        this.actions = new PaidActionService(plugin, plugin.getScheduler().boundedPool("Allium-PaidSign", 1, 128));
    }

    public void close() {
        actions.close();
    }

    public boolean busy(Player player) {
        return actions.busy(player);
    }

    void start(Player player, SignIdentity identity, BooleanSupplier allowed, Action action) {
        startTyped(player, identity, allowed, (valid, complete) ->
                action.deliver(valid, delivered -> complete.accept(PaidActionOutcome.of(delivered))));
    }

    void startTyped(Player player, SignIdentity identity, BooleanSupplier allowed, TypedAction action) {
        UUID workflow = UUID.randomUUID();
        String price = EconomyDisplay.read(plugin.getConfig()).format(identity.price());
        String fingerprint = EconomyReservations.fingerprint(
                "SIGN", identity.id().toString(), identity.type().key(),
                identity.argument(), Long.toString(identity.price().minor()));

        actions.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN,
                workflow,
                player,
                identity.price(),
                fingerprint,
                "Paid sign",
                allowed,
                action::deliver,
                result -> handleResult(player, identity, price, result),
                true
        ));
    }

    private void handleResult(Player player, SignIdentity sign, String price, PaidActionResult result) {
        switch (result.status()) {
            case UNAVAILABLE -> plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
            case BUSY -> plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            case INSUFFICIENT_FUNDS -> {
                if (actions.isCurrentSession(player)) {
                    plugin.getMessageManager().send(player, Messages.SIGN_INSUFFICIENT_FUNDS, "{price}", price);
                }
            }
            case RESERVATION_REFUSED -> {
                if (actions.isCurrentSession(player)) {
                    EconomyRuntime.sendRefusal(plugin, player, result.detail());
                }
            }
            case UNKNOWN_RESERVE -> {
                if (actions.isCurrentSession(player) && result.unknownOperationId() != null) {
                    plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN,
                            "{operation}", result.unknownOperationId().toString());
                }
            }
            case STORAGE_ERROR, CAPTURE_DEFERRED, RELEASE_DEFERRED -> {
                if (actions.isCurrentSession(player)) {
                    plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
                }
            }
            case CAPTURED -> {
                if (actions.isCurrentSession(player)) {
                    String key = sign.type().key();
                    String type = Character.toUpperCase(key.charAt(0)) + key.substring(1);
                    plugin.getMessageManager().send(player, Messages.SIGN_PAID, "{price}", price, "{type}", type);
                    UtilitySupport.audit(plugin, player, "paid_sign",
                            "type=" + key + " arg=" + sign.argument() + " price=" + sign.price().minor()
                                    + " op=" + result.captureOperationId() + " workflow=" + result.workflowId());
                }
            }
            case RELEASED -> {
                // Refused or interrupted actions already messaged the player (if applicable) and released the hold.
            }
        }
    }
}
