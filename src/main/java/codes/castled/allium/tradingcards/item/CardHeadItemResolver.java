package codes.castled.allium.tradingcards.item;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolver;
import codes.castled.allium.spawnercraft.MobHeadRegistry;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Mob-specific references keep deferred payouts textured and identifiable after closing the GUI. */
public final class CardHeadItemResolver implements ItemResolver {
    public static final String NAMESPACE = "cardhead";
    private final Plugin plugin;

    public CardHeadItemResolver(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public boolean exists(ItemRef ref) {
        return type(ref.id()).filter(HeadResolver::hasDefaultHead).isPresent();
    }

    @Override public Optional<ItemStack> create(ItemRef ref, int amount) {
        return type(ref.id()).flatMap(mob -> HeadResolver.resolveDefault(plugin, mob)).map(head -> {
            head.setAmount(Math.max(1, amount));
            return head;
        });
    }

    @Override public Optional<String> idOf(ItemStack stack) {
        String mob = MobHeadRegistry.getMobKey(stack, true);
        return type(mob).filter(HeadResolver::hasDefaultHead)
            .map(type -> type.name().toLowerCase(Locale.ROOT));
    }

    private static Optional<EntityType> type(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            return Optional.of(EntityType.valueOf(id.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
