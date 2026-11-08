package codes.castled.allium.api;

import java.util.Map;
import java.util.Optional;

/**
 * What Allium identified a stack as.
 *
 * @param appearance the item reference, for example {@code allium:gate_wand} or {@code minecraft:stick}
 * @param behavior   the behavior id when the stack verified as one (a forged or edited signed stack has none)
 * @param state      the stack's state values
 */
public record ItemIdentity(String appearance, Optional<String> behavior, Map<String, String> state) {
}
