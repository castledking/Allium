package codes.castled.allium.items.impl;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.item.ItemResolvers;
import codes.castled.allium.items.CustomItem;

/**
 * The bong state machine.
 *
 * <p>A bong is fully described by its water state, its remaining hits and
 * whether a bowl piece is on it — and all three live in the Nexo item id rather
 * than in a tag, so every state is a real item the pack has a texture for and
 * the player sees the water change in their hand:
 *
 * <pre>
 * clean_bong_3-3  --hit--&gt; slightly_dirty_bong_2-3  --hit--&gt; dirty_bong_1-3  --hit--&gt; filthy_bong_0-3
 * </pre>
 *
 * <p>The last hit empties the bowl piece rather than destroying it: the bong
 * keeps an empty {@code bowl_piece} that can be taken off, packed with a weed
 * nugget and loaded again. Water never depletes either, so there is no way to
 * empty a bong yet. A sponge brings the water back to clean without touching the
 * hits or the bowl piece, so a loaded bong stays loaded across a scrub.
 *
 * <p>Nothing here is charge-based or time-based: a bong only changes state when
 * a player drinks it, scrubs it, or takes the bowl piece off.
 */
public class BongItem extends CustomItem {

    public static final String ITEM_ID = "clean_bong";
    public static final String EMPTY_ID = "clean_empty_bong";
    /** A packed bowl piece, the one that loads a bong. */
    public static final String BOWL_PIECE_ID = "full_bowl_piece";
    /** An unpacked one, what a spent bowl piece turns back into. */
    public static final String EMPTY_PIECE_ID = "bowl_piece";
    public static final int MAX_HITS = 3;

    /** Water states, clean first; the last entry is as dirty as it gets. */
    private static final String[] DIRT_TIERS = {"clean", "slightly_dirty", "dirty", "filthy"};

    /**
     * Both the bare bongs ({@code dirty_bong}) and the ones carrying a bowl piece
     * ({@code dirty_bong_1-3}, plus {@code dirty_bong_0-3} for the spent piece).
     * The hit group is optional on purpose: absent means no bowl piece at all,
     * {@code 0-3} means an empty one still screwed on.
     */
    private static final Pattern BONG_ID = Pattern.compile(
            "^(clean|slightly_dirty|dirty|filthy)_bong(?:_([0-3])-3)?$");

    /** Nexo writes the item's own id, which is the only thing that survives a pack reload. */
    private static final NamespacedKey NEXO_KEY = new NamespacedKey("nexo", "id");

    private ItemResolverChain items;

    public BongItem(final Plugin plugin) {
        super(plugin, ITEM_ID);
    }

    /**
     * A bong's water state, how many hits are left, and whether a bowl piece is
     * screwed onto it.
     *
     * <p>The bowl piece outlives the weed: the last hit empties it rather than
     * burning it away, so a spent bong still carries a {@code bowl_piece} that
     * can be taken off, packed with a nugget and loaded again. A bare bong — one
     * with no piece at all — is a different state, and only that is what a full
     * piece gets loaded onto.
     */
    public record State(int dirt, int hits, boolean bowl) {

        /** Whether there is weed left to smoke off this bong. */
        public boolean loaded() {
            return bowl && hits > 0;
        }

        /** Whether a bowl piece is screwed on, full or spent. */
        public boolean hasBowl() {
            return bowl;
        }

        /** Whether the water still needs scrubbing. */
        public boolean dirty() {
            return dirt > 0;
        }

        /** The Nexo id of this exact state, e.g. {@code slightly_dirty_bong_2-3}. */
        public String id() {
            final String bare = DIRT_TIERS[Math.clamp(dirt, 0, DIRT_TIERS.length - 1)] + "_bong";
            return bowl ? bare + "_" + Math.clamp(hits, 0, MAX_HITS) + "-" + MAX_HITS : bare;
        }

        /**
         * One hit: the water gets dirtier and one hit of weed is gone. The last
         * hit empties the bowl piece instead of removing it.
         */
        public State afterHit() {
            return new State(Math.min(dirt + 1, DIRT_TIERS.length - 1), Math.max(0, hits - 1), true);
        }

        /** A sponge: spotless water again, same hits and same bowl piece. */
        public State scrubbed() {
            return new State(0, hits, bowl);
        }

        /** A full bowl piece going on: this bong's water with {@value #MAX_HITS} hits. */
        public State filled() {
            return new State(dirt, MAX_HITS, true);
        }

        /** The same bong with its bowl piece taken off. */
        public State emptied() {
            return new State(dirt, 0, false);
        }

        /**
         * Whether a bowl piece can be taken off: at {@code 3/3} there is a full
         * piece with nothing smoked out of it, and at {@code 0/3} the piece has
         * already spent its weed. In between it is burnt in and rides the bong
         * out to the last hit.
         */
        public boolean removable() {
            return bowl && (hits == MAX_HITS || hits == 0);
        }

        /**
         * Whether a full bowl piece can be loaded onto this state — a bare bong,
         * or one whose piece has already been smoked empty.
         */
        public boolean loadable() {
            return !bowl || hits == 0;
        }

        /** True when {@code id} is a bong in any state. */
        public static boolean isBong(final String id) {
            return parse(id) != null;
        }

        /**
         * @return the state of the given Nexo id, or null when it is not a bong.
         */
        public static State parse(final String id) {
            if (id == null) {
                return null;
            }
            final Matcher matcher = BONG_ID.matcher(id);
            if (!matcher.matches()) {
                return null;
            }
            int dirt = 0;
            for (int i = 0; i < DIRT_TIERS.length; i++) {
                if (DIRT_TIERS[i].equals(matcher.group(1))) {
                    dirt = i;
                    break;
                }
            }
            final String hits = matcher.group(2);
            return hits == null
                    ? new State(dirt, 0, false)   // a bare bong
                    : new State(dirt, Integer.parseInt(hits), true);
        }
    }

    @Override public Material getMaterial() { return Material.GLASS_BOTTLE; }
    @Override public String getDisplayName() { return "§fBong"; }
    @Override public List<String> getLore() {
        return List.of(
                "§7Clean water, spotless glass.",
                "",
                "§8Craft it with a full bowl piece to load it.",
                "§8[Drop sponge to clean]");
    }
    @Override public String getTextureType() { return "item_model"; }
    @Override public Object getItemModel() { return "nexo:" + ITEM_ID; }

    @Override
    public ItemStack createItemStack(final int amount) {
        final ItemStack item = create(ITEM_ID).orElse(null);
        if (item == null) {
            return super.createItemStack(amount);
        }
        item.setAmount(Math.max(1, amount));
        return item;
    }

    /** Accepts every bong state, matched on the Nexo id. */
    @Override
    public boolean isThisItem(final ItemStack item) {
        if (State.isBong(nexoIdOf(item))) {
            return true;
        }
        return super.isThisItem(item); // a stack Allium created itself
    }

    /** The Nexo id of a stack, or null when the stack is not a Nexo item. */
    public static String nexoIdOf(final ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return null;
        }
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(NEXO_KEY, PersistentDataType.STRING);
    }

    /**
     * Builds a bong stack straight from Nexo, so the replacement keeps the
     * texture, the drinking components and the effect commands the pack defines.
     *
     * <p>The resolver chain is built on first use rather than at startup: it only
     * ever runs while a player is holding a bong, which is long after Nexo has
     * registered its items (see {@code NexoItemsGate}).
     *
     * @return empty when Nexo is absent or does not know the id
     */
    public Optional<ItemStack> create(final String nexoId) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            return Optional.empty();
        }
        if (items == null) {
            items = ItemResolvers.build(plugin, "Allium Items", plugin.getLogger());
        }
        return items.create(ItemRef.parse("nexo:" + nexoId), 1).map(stack -> {
            final ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setMaxStackSize(1);
                stack.setItemMeta(meta);
            }
            return stack;
        });
    }

    /** Whether a stack is a bong with weed left in its bowl piece. */
    public static boolean isLoaded(final ItemStack item) {
        final State state = State.parse(nexoIdOf(item));
        return state != null && state.loaded();
    }

    /** Gives an item to a player, dropping whatever does not fit. */
    public static void give(final Player player, final ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        for (final ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}