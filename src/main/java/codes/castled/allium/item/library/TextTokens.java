package codes.castled.allium.item.library;

import codes.castled.allium.item.spec.ItemSpecLexer;
import codes.castled.allium.text.TextFormatter;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code name:} and {@code lore:} tokens, formatted by Allium's text formatter ({@code &} codes,
 * hex, gradients). Unquoted values turn {@code _} into spaces (Essentials style); quoted values
 * are literal. Lore lines are separated by {@code |} outside quotes, and each line may be quoted.
 */
public final class TextTokens {

    public static final int MAX_LORE_LINES = 32;

    private TextTokens() {
    }

    public static void name(ItemMeta meta, String value, boolean quoted) {
        meta.setDisplayName(TextFormatter.toLegacy(spaces(value, quoted)));
    }

    public static void lore(ItemMeta meta, String value, boolean quoted) {
        List<String> lines = new ArrayList<>();
        for (String line : ItemSpecLexer.splitTopLevel(value, '|')) {
            boolean lineQuoted = quoted || ItemSpecLexer.isQuoted(line);
            lines.add(TextFormatter.toLegacy(spaces(ItemSpecLexer.unquote(line), lineQuoted)));
        }
        if (lines.size() > MAX_LORE_LINES) {
            throw new ItemBuildException("lore: at most " + MAX_LORE_LINES + " lines");
        }
        meta.setLore(lines);
    }

    private static String spaces(String value, boolean quoted) {
        return quoted ? value : value.replace('_', ' ');
    }
}
