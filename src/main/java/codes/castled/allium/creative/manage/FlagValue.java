package codes.castled.allium.creative.manage;

import org.jetbrains.annotations.Nullable;

/**
 * One flag as a holder sees it.
 *
 * @param set       what the holder sets itself, globally: true, false, or null when it sets nothing.
 *                  Meaningless when {@code setKnown} is false (Vault cannot tell set from inherited).
 * @param effective whether the flag applies after inheritance and defaults; null when unknown
 * @param setKnown  whether {@code set} is known
 * @param source    where {@code effective} comes from: {@code set here}, {@code inherited},
 *                  {@code bypass}, {@code op default}, {@code online}...
 */
public record FlagValue(@Nullable Boolean set, @Nullable Boolean effective, boolean setKnown, String source) {

    /** Whether the holder grants the flag itself (as far as the backend can tell). */
    public boolean givenHere() {
        return setKnown ? Boolean.TRUE.equals(set) : Boolean.TRUE.equals(effective);
    }

    /** A coloured chat/lore word for the effective state, e.g. {@code &aallowed &7(set here)}. */
    public String describeEffective() {
        String state = effective == null ? "&7unknown" : effective ? "&aallowed" : "&cnot allowed";
        return source.isEmpty() ? state : state + " &7(" + source + ")";
    }
}
