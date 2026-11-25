package codes.castled.curios;

import codes.castled.allium.api.ChatHoverFrame;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class HoverFrames implements ChatHoverFrame {
    private final CuriosPlugin plugin;

    HoverFrames(CuriosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public BaseComponent[] render(Player subject, String frame, List<BaseComponent[]> lines) {
        if (!plugin.texturedMenu()) return null;
        FramePanels panels = plugin.framePanels();
        int width = lines.stream().mapToInt(line -> Arrays.stream(line).mapToInt(c -> TextWidth.of(c.toPlainText())).sum())
                .max().orElse(0);
        var panel = panels.panel(frame, plugin.automaticFrame(subject), lines.size(), width, 0);
        if (panel == null) return null;
        var output = new ArrayList<BaseComponent>();
        output.add(glyph(panel.header(), panel.font()));
        for (int i = 0; i < lines.size(); i++) {
            output.add(new TextComponent("\n"));
            output.add(glyph(panel.prefixes().get(i), panel.font()));
            output.addAll(Arrays.asList(lines.get(i)));
        }
        output.add(new TextComponent("\n"));
        output.add(glyph(panel.last(), panel.font()));
        if (panel.blankAfter()) output.add(new TextComponent("\n"));
        return output.toArray(BaseComponent[]::new);
    }

    private static BaseComponent glyph(String value, String font) {
        var glyph = new TextComponent(value);
        glyph.setFont(font);
        glyph.setColor(ChatColor.WHITE);
        return glyph;
    }
}
