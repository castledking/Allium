package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.ItemDefinition;
import codes.castled.allium.api.ItemIdentity;
import codes.castled.allium.api.ItemResult;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HeadRecipesDeliveryTest {

    private Heads heads;
    private Player player;
    private PlayerInventory playerInv;
    private ItemStack headStack;
    private ItemStack paperStack;

    @BeforeEach
    void setUp() {
        heads = mock(Heads.class);
        headStack = mockItem(Material.PLAYER_HEAD, 2);
        paperStack = mockItem(Material.PAPER, 2);
        when(heads.mob(headStack)).thenReturn("zombie");
        when(heads.mob(paperStack)).thenReturn(null);
        when(heads.mob(null)).thenReturn(null);

        player = mock(Player.class);
        playerInv = mock(PlayerInventory.class);
        when(player.getName()).thenReturn("Alex");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(playerInv);
        when(playerInv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
    }

    private static ItemStack mockItem(Material material, int amount) {
        AtomicInteger amt = new AtomicInteger(amount);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(inv -> amt.get());
        doAnswer(inv -> {
            amt.set(inv.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        return stack;
    }

    private ItemStack[] cardMatrix() {
        return new ItemStack[]{
                headStack, headStack, headStack,
                headStack, paperStack, headStack,
                headStack, headStack, headStack
        };
    }

    private static AddonItems stubItems(ItemResult result) {
        return new AddonItems() {
            @Override public void define(ItemDefinition definition) {}
            @Override public ItemResult create(String reference, int amount) { return result; }
            @Override public Optional<ItemIdentity> identify(ItemStack stack) { return Optional.empty(); }
        };
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(content);
        return cfg;
    }

    @Test
    void typedCardDeliveryConsumesRevalidatedMatrixAndGrantsItem() throws Exception {
        ItemStack cardOut = mockItem(Material.PAPER, 1);
        HeadRecipes.Cards cards = new HeadRecipes.Cards() {
            @Override public boolean supports(String mob) { return true; }
            @Override public OptionalInt previewModel(String mob, Map<String, Double> weights) { return OptionalInt.empty(); }
            @Override public Optional<ItemStack> craft(String mob, Map<String, Double> weights) { return Optional.of(cardOut); }
        };
        HeadRecipes recipes = new HeadRecipes(heads, stubItems(null), null, () -> cards, cmd -> true);
        recipes.load(yaml("""
                trading_card:
                  name: Trading Card
                  pattern: [HHH, HPH, HHH]
                  ingredients:
                    P: PAPER
                  result:
                    card: true
                    preview: PAPER
                """), null);

        ItemStack[] slots = cardMatrix();
        CraftingInventory inv = mock(CraftingInventory.class);
        AtomicReference<ItemStack[]> live = new AtomicReference<>(slots);
        when(inv.getMatrix()).thenAnswer(i -> live.get());
        doAnswer(i -> {
            live.set(i.getArgument(0));
            return null;
        }).when(inv).setMatrix(any());

        HeadRecipes.DeliveryResult res = recipes.craft(player, inv, "trading_card:zombie");
        assertEquals(HeadRecipes.DeliveryStatus.DELIVERED, res.status());
        verify(playerInv).addItem(cardOut);
        assertEquals(1, paperStack.getAmount());
    }

    @Test
    void unavailableTypedOutputDoesNotConsumeMatrix() throws Exception {
        HeadRecipes.Cards emptyCards = new HeadRecipes.Cards() {
            @Override public boolean supports(String mob) { return true; }
            @Override public OptionalInt previewModel(String mob, Map<String, Double> weights) { return OptionalInt.empty(); }
            @Override public Optional<ItemStack> craft(String mob, Map<String, Double> weights) { return Optional.empty(); }
        };
        HeadRecipes recipes = new HeadRecipes(heads, stubItems(null), null, () -> emptyCards, cmd -> true);
        recipes.load(yaml("""
                trading_card:
                  name: Trading Card
                  pattern: [HHH, HPH, HHH]
                  ingredients:
                    P: PAPER
                  result:
                    card: true
                    preview: PAPER
                """), null);

        CraftingInventory inv = mock(CraftingInventory.class);
        when(inv.getMatrix()).thenReturn(cardMatrix());

        HeadRecipes.DeliveryResult res = recipes.craft(player, inv, "trading_card:zombie");
        assertEquals(HeadRecipes.DeliveryStatus.UNAVAILABLE, res.status());
        verify(inv, never()).setMatrix(any());
        verify(playerInv, never()).addItem(any(ItemStack.class));
        assertEquals(2, paperStack.getAmount());
    }

    @Test
    void matrixMutationDuringTypedCallbackAbortsConsumptionAndGrant() throws Exception {
        ItemStack cardOut = mockItem(Material.PAPER, 1);
        AtomicReference<ItemStack[]> live = new AtomicReference<>(cardMatrix());
        HeadRecipes.Cards mutatingCards = new HeadRecipes.Cards() {
            @Override public boolean supports(String mob) { return true; }
            @Override public OptionalInt previewModel(String mob, Map<String, Double> weights) { return OptionalInt.empty(); }
            @Override public Optional<ItemStack> craft(String mob, Map<String, Double> weights) {
                // Simulate an external listener clearing the crafting grid mid-callback
                live.set(new ItemStack[9]);
                return Optional.of(cardOut);
            }
        };
        HeadRecipes recipes = new HeadRecipes(heads, stubItems(null), null, () -> mutatingCards, cmd -> true);
        recipes.load(yaml("""
                trading_card:
                  name: Trading Card
                  pattern: [HHH, HPH, HHH]
                  ingredients:
                    P: PAPER
                  result:
                    card: true
                    preview: PAPER
                """), null);

        CraftingInventory inv = mock(CraftingInventory.class);
        when(inv.getMatrix()).thenAnswer(i -> live.get());

        HeadRecipes.DeliveryResult res = recipes.craft(player, inv, "trading_card:zombie");
        assertEquals(HeadRecipes.DeliveryStatus.MATRIX_CHANGED, res.status());
        verify(inv, never()).setMatrix(any());
        verify(playerInv, never()).addItem(any(ItemStack.class));
    }

    @Test
    void refusedCommandDoesNotConsumeMatrixAndMutatingCommandDoesNotRestoreStaleMatrix() throws Exception {
        AtomicBoolean acceptCommand = new AtomicBoolean(false);
        AtomicReference<ItemStack[]> live = new AtomicReference<>(cardMatrix());
        AtomicBoolean mutateDuringCommand = new AtomicBoolean(false);

        HeadRecipes recipes = new HeadRecipes(heads, stubItems(null), null, () -> null, cmd -> {
            if (mutateDuringCommand.get()) {
                live.set(new ItemStack[9]);
            }
            return acceptCommand.get();
        });
        recipes.load(yaml("""
                custom_cmd:
                  name: Custom Command
                  pattern: [HHH, HPH, HHH]
                  ingredients:
                    P: PAPER
                  result:
                    command: 'give {player} diamond 1'
                    preview: DIAMOND
                """), null);

        CraftingInventory inv = mock(CraftingInventory.class);
        when(inv.getMatrix()).thenAnswer(i -> live.get());

        // 1. Refused command -> COMMAND_REFUSED, matrix untouched
        HeadRecipes.DeliveryResult refused = recipes.craft(player, inv, "custom_cmd:zombie");
        assertEquals(HeadRecipes.DeliveryStatus.COMMAND_REFUSED, refused.status());
        verify(inv, never()).setMatrix(any());
        assertEquals(2, paperStack.getAmount());

        // 2. Command succeeds, but callback mutated/emptied the crafting grid -> MATRIX_CHANGED, stale matrix NOT written back
        acceptCommand.set(true);
        mutateDuringCommand.set(true);
        HeadRecipes.DeliveryResult mutated = recipes.craft(player, inv, "custom_cmd:zombie");
        assertEquals(HeadRecipes.DeliveryStatus.MATRIX_CHANGED, mutated.status());
        verify(inv, never()).setMatrix(any());

        // 3. Command succeeds with unchanged grid -> COMMAND_DISPATCHED, live matrix consumed
        mutateDuringCommand.set(false);
        live.set(cardMatrix());
        HeadRecipes.DeliveryResult ok = recipes.craft(player, inv, "custom_cmd:zombie");
        assertEquals(HeadRecipes.DeliveryStatus.COMMAND_DISPATCHED, ok.status());
        verify(inv).setMatrix(any());
        assertEquals(1, paperStack.getAmount());
    }
}
