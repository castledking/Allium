package codes.castled.allium.addon;

import codes.castled.allium.api.TradeItemCatalog;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import org.bukkit.inventory.ItemStack;

/** External shops may reconstruct stateless Crops products by verified ID. */
public record CoreTradeItemCatalog(ItemService items) implements TradeItemCatalog {
    @Override public boolean contains(String id) {
        if (id == null || !id.matches("[a-z0-9_/-]+")) return false;
        return items.getAlliumItem(id).filter(definition -> ("crops:" + id).equals(definition.behaviorId()))
                .filter(definition -> !items.hiddenFromGive(ProviderKey.of("allium", id)))
                .filter(definition -> items.isStackableSignedBehavior(definition.behaviorId()))
                .isPresent();
    }

    @Override public ItemStack create(String id) {
        return contains(id) ? items.create(items.parseRef("allium:" + id)).itemStack().orElse(null) : null;
    }

    @Override public String identify(ItemStack item) {
        return items.identify(item).filter(ref -> ref.appearance().isAllium() && ref.stateData().isEmpty()
                        && contains(ref.appearance().id()) && ref.behaviorId().filter(id -> id.equals("crops:" + ref.appearance().id())).isPresent())
                .map(ref -> ref.appearance().id()).orElse(null);
    }
}
