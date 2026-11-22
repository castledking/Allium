package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.AddonEconomy;
import codes.castled.allium.economy.EconomyResult.*;
import codes.castled.allium.module.EconomyModule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Allium's economy as an addon sees it: synchronous checks and moves, made the way
 * {@link VaultEconomyProvider} makes them, without needing Vault. Each move is journalled under
 * the origin {@code ADDON:<name>}.
 */
public final class AddonMoney implements AddonEconomy {

    /** What money moves through while the economy is up. */
    record Ledger(EconomyEngine engine, Function<UUID, Balance> balances,
                  Function<EconomyRequest, Mutation> mutate, Function<BigDecimal, String> format) {}

    private final String origin;
    private final Supplier<Ledger> ledger;

    /** The economy for {@code addonName}, following Allium's economy module as it enables and disables. */
    public static AddonMoney of(PluginStart plugin, String addonName) {
        return new AddonMoney(addonName, () -> {
            if (!PaidWorkflowSupport.ready(plugin)) return null;
            EconomyRuntime runtime = ((EconomyModule) plugin.getModuleManager().getModule("economy")).runtime();
            return runtime == null || !runtime.ready() ? null : new Ledger(runtime.engine(),
                    runtime::synchronousBalance, runtime::vaultMutation, amount -> runtime.display().format(amount));
        });
    }

    AddonMoney(String addonName, Supplier<Ledger> ledger) {
        this.origin = "ADDON:" + addonName;
        this.ledger = ledger;
    }

    @Override
    public boolean available() {
        return ledger.get() != null;
    }

    @Override
    public boolean has(UUID player, double amount) {
        Ledger current = ledger.get();
        if (current == null || player == null) return false;
        if (amount <= 0) return true;
        Money requested;
        try {
            requested = VaultMoney.amount(amount);
        } catch (RuntimeException invalid) {
            return false;
        }
        return current.balances().apply(player) instanceof Found found && found.available().compareTo(requested) >= 0;
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        return change(player, amount, EconomyRequest.Kind.WITHDRAW);
    }

    @Override
    public boolean deposit(UUID player, double amount) {
        return change(player, amount, EconomyRequest.Kind.DEPOSIT);
    }

    private boolean change(UUID player, double raw, EconomyRequest.Kind kind) {
        Ledger current = ledger.get();
        if (current == null || player == null) return false;
        Money amount;
        try {
            amount = VaultMoney.amount(raw).positive();
            VaultMoney.egress(amount);
        } catch (RuntimeException invalid) {
            return false;
        }
        UUID operation = UUID.randomUUID();
        return current.mutate().apply(current.engine().request(operation, origin, operation.toString(), kind,
                origin, player, null, amount)) instanceof Committed;
    }

    @Override
    public String format(double amount) {
        Ledger current = ledger.get();
        BigDecimal value = BigDecimal.valueOf(Double.isFinite(amount) ? amount : 0).setScale(2, RoundingMode.HALF_EVEN);
        return current == null ? AddonEconomy.UNAVAILABLE.format(value.doubleValue()) : current.format().apply(value);
    }
}
