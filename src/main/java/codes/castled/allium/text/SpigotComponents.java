package codes.castled.allium.text;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link FormattedText} as BungeeCord chat components for Spigot, built run by run: plain text with
 * colour and decorations only. Unlike a legacy string, these are never auto-linked: Spigot turns
 * http(s) URLs inside legacy chat strings into clickable links.
 */
public final class SpigotComponents {

    private SpigotComponents() {
    }

    public static BaseComponent[] of(FormattedText text) {
        List<BaseComponent> out = new ArrayList<>();
        List<FormattedText.StyledGlyph> glyphs = text.glyphs();
        int i = 0;
        while (i < glyphs.size()) {
            FormattedText.StyledGlyph style = glyphs.get(i);
            StringBuilder run = new StringBuilder();
            while (i < glyphs.size() && glyphs.get(i).hasSameStyle(style)) {
                run.appendCodePoint(glyphs.get(i).codePoint());
                i++;
            }
            TextComponent component = new TextComponent(run.toString());
            if (style.legacyColorChar() != '\0') {
                component.setColor(ChatColor.getByChar(style.legacyColorChar()));
            } else if (style.rgb() != -1) {
                component.setColor(ChatColor.of(new java.awt.Color(style.rgb())));
            } else {
                component.setColor(ChatColor.WHITE);
            }
            component.setBold(style.hasDecoration(FormattedText.DEC_BOLD));
            component.setItalic(style.hasDecoration(FormattedText.DEC_ITALIC));
            component.setUnderlined(style.hasDecoration(FormattedText.DEC_UNDERLINED));
            component.setStrikethrough(style.hasDecoration(FormattedText.DEC_STRIKETHROUGH));
            component.setObfuscated(style.hasDecoration(FormattedText.DEC_OBFUSCATED));
            out.add(component);
        }
        return out.toArray(new BaseComponent[0]);
    }
}
