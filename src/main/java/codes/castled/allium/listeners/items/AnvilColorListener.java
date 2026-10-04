package codes.castled.allium.listeners.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import codes.castled.allium.managers.chat.ChatColorParser;
import codes.castled.allium.managers.core.Text;

import java.util.List;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

/**
 * Colours anvil renames with the same rules chat and signs get: legacy {@code &a} codes and
 * MiniMessage tags can be mixed in one name, and each is gated on the matching
 * {@code allium.anvil} permission. The parsing lives in {@link ChatColorParser} under the
 * {@code allium.anvil} scope, so a wildcard grant like {@code allium.anvil.color.*} reaches
 * every legacy colour and a chat grant never leaks over.
 *
 * <p>Paper exposes the rename text as a getter only - there is no setter on
 * {@code AnvilInventory} or {@code AnvilView} - so the parsed name is applied the way both
 * public anvil-colour plugins do it: take the name the player asked for and hand the result slot
 * a copy carrying it. The text field keeps showing the codes as typed, which is also what lets
 * the player keep editing them.
 *
 * <p>Names come out of vanilla slanted, so a name we colour is explicitly un-slanted unless the
 * player asked for italics with {@code &o} or {@code <italic>}.
 */
public class AnvilColorListener implements Listener {

    /** Permission root for anvil renaming; chat and signs use their own roots for the same parser. */
    private static final String SCOPE = "allium.anvil";

    /**
     * MiniMessage's negated decoration tag. Applied in the source rather than only on the parsed
     * component, because {@code NOT_ITALIC} does not survive a legacy round trip.
     */
    private static final String NO_ITALIC_TAG = "<!italic>";

    /**
     * A name spelled back out as {@code &} codes. Vanilla hands us the result slot as a
     * component, and this is what turns an already-parsed name back into parseable source.
     */
    private static final LegacyComponentSerializer TYPED = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        HumanEntity viewer = event.getView().getPlayer();
        if (viewer == null) {
            return;
        }

        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }

        String source = sourceText(event.getInventory().getRenameText(), displayNameOf(result),
                displayNameOf(event.getInventory().getItem(0)));
        if (!ChatColorParser.containsFormatting(source)) {
            // No rename we can improve on, so leave vanilla's own name alone.
            return;
        }

        ChatColorParser.PermissionCheck permissions = viewer::hasPermission;
        String filtered = ChatColorParser.toMiniMessage(permissions, source, SCOPE);
        if (!filtered.equals(source)) {
            Text.sendDebugLog(INFO, viewer.getName() + " - Anvil rename: '" + source + "' -> '" + filtered + "'");
        }

        // Whether a colour actually lands decides the slant, so that is what the test looks at:
        // a name whose codes were all filtered out keeps vanilla's italic like any plain name.
        boolean coloured = ChatColorParser.containsColor(filtered);
        String nameSource = coloured ? unitalicSource(filtered) : filtered;
        Component name = forItemName(ChatColorParser.deserialize(nameSource), filtered);

        ItemStack renamed = result.clone();
        renamed.editMeta(meta -> meta.displayName(name));
        event.setResult(renamed);
    }

    /**
     * The text to colour.
     *
     * <p>{@code AnvilInventory#getRenameText()} pulls its value out of the open inventory view
     * and quietly hands back {@code null} when that view is not an {@code AnvilView} - no error,
     * no log - which is what a modded or wrapped anvil gives us. The name vanilla already applied
     * to the result slot is the source that can be counted on, and it is only trustworthy once it
     * differs from the input item's name, since that difference is what a rename looks like.
     *
     * @param typed What the player typed, or null/empty when the server could not tell us
     * @param resultName Display name of the result slot, spelled as {@code &} codes
     * @param inputName Display name of the input slot, spelled as {@code &} codes
     */
    static String sourceText(String typed, String resultName, String inputName) {
        if (typed != null && !typed.isEmpty() && ChatColorParser.containsFormatting(typed)) {
            return typed;
        }
        if (resultName == null || resultName.isEmpty() || resultName.equals(inputName)) {
            return "";
        }
        return resultName;
    }

/**
 * Drops the italic vanilla gives custom item names, keeping any italics the name asked for.
 *
 * <p>Done twice on purpose. The negated italic tag goes into the source, right after the
 * leading reset tags our own legacy conversion emits - a reset placed later would wipe a
 * tag sitting in front of it - so the un-slanting survives being re-parsed from source. The
 * component pass then catches what the tag cannot: a reset further along the name, and the fact
 * that {@code NOT_ITALIC} is the one decoration a legacy round trip silently drops, which is how
 * a slanted name sneaks back in on the next keystroke.
 */
static Component forItemName(Component parsed, String source) {
    if (!ChatColorParser.containsColor(source)) {
        return parsed;
    }
    return withoutInheritedItalics(parsed);
}

/**
 * Prepends the negated italic tag to MiniMessage source, after any leading reset tags, so a
 * later legacy colour code cannot clear it again.
 */
static String unitalicSource(String miniMessage) {
    int insertAt = 0;
    while (miniMessage.startsWith("<reset>", insertAt)) {
        insertAt += "<reset>".length();
    }
    return miniMessage.substring(0, insertAt) + NO_ITALIC_TAG + miniMessage.substring(insertAt);
}

    private static Component withoutInheritedItalics(Component component) {
        if (component.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
            component = component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
        List<Component> children = component.children();
        if (children.isEmpty()) {
            return component;
        }
        return component.children(children.stream().map(AnvilColorListener::withoutInheritedItalics).toList());
    }

    /** Display name of {@code item} spelled as {@code &} codes, or null when it carries no name. */
    private static String displayNameOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? TYPED.serialize(meta.displayName()) : null;
    }
}