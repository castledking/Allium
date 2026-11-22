package codes.castled.spawnercraft;

import codes.castled.curios.CardCrafting;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

record CuriosCards(CardCrafting service) implements HeadRecipes.Cards {
    static HeadRecipes.Cards find(Server server) {
        CardCrafting service = server.getServicesManager().load(CardCrafting.class);
        return service == null ? null : new CuriosCards(service);
    }

    @Override public boolean supports(String mob) { return service.supports(mob); }

    @Override public OptionalInt previewModel(String mob, Map<String, Double> weights) {
        return service.previewModel(mob, weights);
    }

    @Override public Optional<ItemStack> craft(String mob, Map<String, Double> weights) {
        return service.craft(mob, weights);
    }
}
