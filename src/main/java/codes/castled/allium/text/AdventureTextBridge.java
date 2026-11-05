package codes.castled.allium.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Isolated Adventure adapter for Paper runtimes.
 *
 * <p>This class is only loaded after verifying that {@code net.kyori.adventure.text.Component}
 * and {@code net.kyori.adventure.audience.Audience} exist at runtime. No Paper or Adventure
 * types appear in shared signatures outside this adapter.
 */
final class AdventureTextBridge {

    private AdventureTextBridge() {
    }

    static boolean trySend(CommandSender sender, FormattedText formatted) {
        if (!(sender instanceof Audience audience)) {
            return false;
        }
        audience.sendMessage(toComponent(formatted));
        return true;
    }

    static Component toComponent(FormattedText formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return Component.empty();
        }

        List<FormattedText.StyledGlyph> glyphs = formatted.glyphs();
        TextComponent.Builder root = Component.text();

        StringBuilder runText = new StringBuilder();
        FormattedText.StyledGlyph runStyleGlyph = glyphs.get(0);

        for (FormattedText.StyledGlyph glyph : glyphs) {
            if (!glyph.hasSameStyle(runStyleGlyph)) {
                root.append(Component.text(runText.toString(), toAdventureStyle(runStyleGlyph)));
                runText.setLength(0);
                runStyleGlyph = glyph;
            }
            runText.appendCodePoint(glyph.codePoint());
        }

        if (runText.length() > 0) {
            root.append(Component.text(runText.toString(), toAdventureStyle(runStyleGlyph)));
        }

        return root.build();
    }

    /**
     * Converts an Adventure {@link Component} produced by {@link #toComponent(FormattedText)}
     * back into its visible legacy {@code §x} / {@code §0-f} representation for parity verification.
     */
    static String componentToVisibleLegacy(Component component) {
        if (component == null || Component.empty().equals(component)) {
            return "";
        }
        List<FormattedText.StyledGlyph> reconstructed = new java.util.ArrayList<>();
        collectGlyphs(component, null, (byte) 0, reconstructed);
        return new FormattedText(reconstructed).toLegacy();
    }

    private static void collectGlyphs(
            Component node,
            TextColor inheritedColor,
            byte inheritedDec,
            List<FormattedText.StyledGlyph> out
    ) {
        Style style = node.style();
        TextColor color = style.color() != null ? style.color() : inheritedColor;
        byte dec = resolveDecorations(style, inheritedDec);

        if (node instanceof TextComponent textNode) {
            String content = textNode.content();
            int i = 0;
            while (i < content.length()) {
                int cp = content.codePointAt(i);
                char legacyChar = (color instanceof NamedTextColor named) ? namedToLegacyChar(named) : '\0';
                int rgb = color != null ? color.value() : -1;
                out.add(new FormattedText.StyledGlyph(cp, rgb, legacyChar, dec));
                i += Character.charCount(cp);
            }
        }

        for (Component child : node.children()) {
            collectGlyphs(child, color, dec, out);
        }
    }

    private static Style toAdventureStyle(FormattedText.StyledGlyph glyph) {
        Style.Builder builder = Style.style();
        if (glyph.legacyColorChar() != '\0') {
            NamedTextColor named = legacyCharToNamed(glyph.legacyColorChar());
            if (named != null) {
                builder.color(named);
            }
        } else if (glyph.rgb() != -1) {
            builder.color(TextColor.color(glyph.rgb()));
        }

        byte dec = glyph.decorations();
        if ((dec & FormattedText.DEC_OBFUSCATED) != 0) builder.decoration(TextDecoration.OBFUSCATED, TextDecoration.State.TRUE);
        if ((dec & FormattedText.DEC_BOLD) != 0) builder.decoration(TextDecoration.BOLD, TextDecoration.State.TRUE);
        if ((dec & FormattedText.DEC_STRIKETHROUGH) != 0) builder.decoration(TextDecoration.STRIKETHROUGH, TextDecoration.State.TRUE);
        if ((dec & FormattedText.DEC_UNDERLINED) != 0) builder.decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE);
        if ((dec & FormattedText.DEC_ITALIC) != 0) builder.decoration(TextDecoration.ITALIC, TextDecoration.State.TRUE);
        return builder.build();
    }

    private static byte resolveDecorations(Style style, byte inherited) {
        byte dec = inherited;
        dec = applyDec(dec, style.decoration(TextDecoration.OBFUSCATED), FormattedText.DEC_OBFUSCATED);
        dec = applyDec(dec, style.decoration(TextDecoration.BOLD), FormattedText.DEC_BOLD);
        dec = applyDec(dec, style.decoration(TextDecoration.STRIKETHROUGH), FormattedText.DEC_STRIKETHROUGH);
        dec = applyDec(dec, style.decoration(TextDecoration.UNDERLINED), FormattedText.DEC_UNDERLINED);
        dec = applyDec(dec, style.decoration(TextDecoration.ITALIC), FormattedText.DEC_ITALIC);
        return dec;
    }

    private static byte applyDec(byte current, TextDecoration.State state, byte flag) {
        if (state == TextDecoration.State.TRUE) return (byte) (current | flag);
        if (state == TextDecoration.State.FALSE) return (byte) (current & ~flag);
        return current;
    }

    private static NamedTextColor legacyCharToNamed(char c) {
        return switch (c) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private static char namedToLegacyChar(NamedTextColor named) {
        if (named == NamedTextColor.BLACK) return '0';
        if (named == NamedTextColor.DARK_BLUE) return '1';
        if (named == NamedTextColor.DARK_GREEN) return '2';
        if (named == NamedTextColor.DARK_AQUA) return '3';
        if (named == NamedTextColor.DARK_RED) return '4';
        if (named == NamedTextColor.DARK_PURPLE) return '5';
        if (named == NamedTextColor.GOLD) return '6';
        if (named == NamedTextColor.GRAY) return '7';
        if (named == NamedTextColor.DARK_GRAY) return '8';
        if (named == NamedTextColor.BLUE) return '9';
        if (named == NamedTextColor.GREEN) return 'a';
        if (named == NamedTextColor.AQUA) return 'b';
        if (named == NamedTextColor.RED) return 'c';
        if (named == NamedTextColor.LIGHT_PURPLE) return 'd';
        if (named == NamedTextColor.YELLOW) return 'e';
        if (named == NamedTextColor.WHITE) return 'f';
        return '\0';
    }
}
