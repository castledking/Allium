package codes.castled.spawnercraft;

import codes.castled.curios.CardTradeHeads;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Map;
import java.util.Optional;

/** Pays Curios card trade-ins in this mob's SpawnerCraft head, as legacy's trading cards paid spawner heads. */
record CuriosTradeHeads(Heads heads) implements CardTradeHeads {
    /**
     * A card has no variant, so it pays the mob's vanilla default look. Without an entry here a mob
     * pays its {@code <mob>_head}, else its first catalogue head, which for these is a named easter
     * egg (jeb_, Toast) or a rarer variant.
     */
    private static final Map<String, String> DEFAULT_LOOK = Map.of(
            "sheep", "white_sheep_head", "wolf", "pale_wolf_head", "rabbit", "brown_rabbit_head");

    static void register(Plugin plugin, Heads heads) {
        plugin.getServer().getServicesManager().register(CardTradeHeads.class, new CuriosTradeHeads(heads), plugin, ServicePriority.Normal);
    }

    @Override
    public Optional<ItemStack> head(String mob, int amount) {
        Heads.Head head = heads.find(DEFAULT_LOOK.getOrDefault(mob, mob));
        if (head == null || !head.mob().equals(mob)) return Optional.empty();
        ItemStack stack = heads.create(head);
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        return Optional.of(stack);
    }
}
