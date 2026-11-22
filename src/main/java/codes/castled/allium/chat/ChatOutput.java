package codes.castled.allium.chat;

import codes.castled.allium.text.*;
import net.md_5.bungee.api.chat.*;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import java.util.*;

/** Trusted template + separately parsed values. No user body is reparsed as a template. */
public final class ChatOutput {
    private ChatOutput(){}
    private static final Map<String,String> TOKENS = Map.ofEntries(
            Map.entry("<message>", "{message}"), Map.entry("<prefix>", "{prefix}"), Map.entry("<suffix>", "{suffix}"),
            Map.entry("<display_name>", "{name}"), Map.entry("<name>", "{name}"), Map.entry("<player>", "{player}"),
            Map.entry("%allium_nickname%", "{name}"), Map.entry("%allium_gradientdisplayname%", "{gradient_name}"),
            Map.entry("%gradient_displayname%", "{gradient_name}"),
            Map.entry("%luckperms_prefix%", "{prefix}"), Map.entry("%luckperms_suffix%", "{suffix}"));
    private static final Set<String> IDENTITY = Set.of("name", "gradient_name", "player", "discord_username", "prefix", "suffix");
    private static final java.util.regex.Pattern FRAME = java.util.regex.Pattern.compile("\\[frame(?::([^\\]]*))?\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern GLYPH = java.util.regex.Pattern.compile("<glyph:([a-zA-Z0-9_./-]+)>");

    record Hooks(java.util.function.UnaryOperator<String> expand,
                 java.util.function.Function<String, BaseComponent> glyph,
                 java.util.function.BiFunction<String, List<BaseComponent[]>, BaseComponent[]> frame) {
        static final Hooks PLAIN = new Hooks(java.util.function.UnaryOperator.identity(), id -> null, (frame, lines) -> null);
    }
    record Audience(String ordinary, String staff) {
        String forViewer(Player viewer) {
            return viewer.hasPermission("allium.delmsg") || viewer.hasPermission("allium.deletemsg")
                    || viewer.hasPermission("allium.staff") ? staff : ordinary;
        }
    }
    private record Decoration(HoverEvent hover, ClickEvent click) {
        void apply(BaseComponent component) {
            component.setHoverEvent(hover);
            component.setClickEvent(click);
        }
    }

    static String render(String template, Map<String, FormattedText> values, Player viewer, String id,
                         String realName, String group, ConfigurationSection config) {
        return prepare(template, values, id, realName, group, config, Hooks.PLAIN).forViewer(viewer);
    }

    static Audience prepare(String template, Map<String, FormattedText> values, String id, String realName,
                            String group, ConfigurationSection config, Hooks hooks) {
        template = aliases(template);
        var markers = new LinkedHashMap<Integer, String>();
        int index = 0;
        for (String key : values.keySet()) {
            int marker = 0xe100 + index++;
            String token = "{" + key + "}";
            if (!template.contains(token)) continue;
            template = template.replace(token, Character.toString(marker));
            markers.put(marker, key);
        }
        String mode = config.getString("chat-hover.delete-hover-mode", "prefix").toLowerCase(Locale.ROOT);
        if (mode.equals("player") || (mode.equals("prefix") || mode.equals("suffix"))
                && (!markers.containsValue(mode) || values.get(mode).toPlainText().isBlank())) mode = "name";

        var ordinary = new ArrayList<BaseComponent>();
        var staff = new ArrayList<BaseComponent>();
        var normalDecorations = new HashMap<String, Decoration>();
        var staffDecorations = new HashMap<String, Decoration>();
        for (var glyph : TextFormatter.parse(hooks.expand().apply(template)).glyphs()) {
            String key = markers.get(glyph.codePoint());
            var text = key == null ? new FormattedText(List.of(glyph)) : inherit(values.get(key), glyph);
            BaseComponent[] nodes = SpigotComponents.of(text);
            if (key != null && IDENTITY.contains(key)) {
                String role = key.equals("prefix") || key.equals("suffix") ? key : "name";
                boolean configured = !key.equals("discord_username");
                if (configured || role.equals(mode)) {
                    var section = configured ? section(config, group, role) : null;
                    var normal = normalDecorations.computeIfAbsent(role, unused ->
                            decoration(section, values, realName, group, id, false, hooks));
                    var augmented = role.equals(mode) ? staffDecorations.computeIfAbsent(role, unused ->
                            decoration(section, values, realName, group, id, true, hooks)) : normal;
                    for (var node : nodes) {
                        var copy = node.duplicate();
                        normal.apply(node);
                        augmented.apply(copy);
                        ordinary.add(node);
                        staff.add(copy);
                    }
                    continue;
                }
            }
            ordinary.addAll(Arrays.asList(nodes));
            staff.addAll(Arrays.asList(nodes));
        }
        return new Audience(tag(ordinary, id), tag(staff, id));
    }

    private static String tag(List<BaseComponent> nodes, String id) {
        return ChatIdentity.tag(ComponentSerializer.toString(nodes.toArray(BaseComponent[]::new)), ChatIdentity.ID + id);
    }

    private static ConfigurationSection section(ConfigurationSection config, String group, String role) {
        var specific = config.getConfigurationSection("chat-hover.groups." + group + "." + role);
        return specific == null ? config.getConfigurationSection("chat-hover.groups.default." + role) : specific;
    }

    private static Decoration decoration(ConfigurationSection section, Map<String, FormattedText> values,
                                         String realName, String group, String id, boolean canDelete, Hooks hooks) {
        List<String> configured = section == null ? List.of() : section.isString("hover")
                ? List.of(section.getString("hover")) : section.getStringList("hover");
        List<BaseComponent[]> lines = new ArrayList<>();
        String frame = null;
        for (String line : configured) {
            String expanded = hooks.expand().apply(substitute(line, values, realName, group, true));
            var marker = FRAME.matcher(expanded.strip());
            if (frame == null && marker.matches()) {
                String style = marker.group(1);
                frame = style != null && style.matches("[a-zA-Z0-9_]{1,48}") ? style : "auto";
                continue;
            }
            for (String part : expanded.split("\\n", -1)) lines.add(glyphs(part, hooks));
        }
        if (canDelete) {
            if (!lines.isEmpty() && lines.get(lines.size() - 1).length > 0) lines.add(new BaseComponent[0]);
            lines.add(SpigotComponents.of(TextFormatter.parse("&cClick to delete this message")));
            lines.add(SpigotComponents.of(TextFormatter.parse("&8[&f" + id + "&8]")));
        }
        BaseComponent[] tooltip = frame == null ? null : hooks.frame().apply(frame, List.copyOf(lines));
        if (tooltip == null) tooltip = join(lines);
        var hover = lines.isEmpty() ? null : new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip);
        ClickEvent click = canDelete ? new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/delmsg " + id)
                : configuredClick(section, values, realName, group, hooks);
        return new Decoration(hover, click);
    }

    private static ClickEvent configuredClick(ConfigurationSection section, Map<String, FormattedText> values,
                                              String realName, String group, Hooks hooks) {
        if (section == null) return null;
        var action = switch (section.getString("action", "suggest-command").toLowerCase(Locale.ROOT)) {
            case "run-command" -> ClickEvent.Action.RUN_COMMAND;
            case "open-url" -> ClickEvent.Action.OPEN_URL;
            case "copy-to-clipboard" -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            case "suggest-command" -> ClickEvent.Action.SUGGEST_COMMAND;
            default -> null;
        };
        if (action == null) return null;
        String click = section.getString("click", "");
        if (action == ClickEvent.Action.COPY_TO_CLIPBOARD) click = section.getString("content", click);
        click = hooks.expand().apply(substitute(click, values, realName, group, false));
        return click.isEmpty() ? null : new ClickEvent(action, click);
    }

    private static String aliases(String text) {
        for (var alias : TOKENS.entrySet()) text = text.replace(alias.getKey(), alias.getValue());
        return text;
    }

    private static String substitute(String text, Map<String, FormattedText> values, String player, String group, boolean styled) {
        text = aliases(text).replace("{player}", player).replace("{group}", group);
        for (String key : IDENTITY) {
            var value = values.get(key);
            if (value != null) text = text.replace("{" + key + "}", styled ? value.toLegacy() : value.toPlainText());
        }
        return text;
    }

    private static BaseComponent[] glyphs(String line, Hooks hooks) {
        var tags = GLYPH.matcher(line);
        if (!tags.find()) return SpigotComponents.of(TextFormatter.parse(line));
        var components = new ArrayList<BaseComponent>();
        var marked = new StringBuilder();
        var glyphs = new ArrayList<BaseComponent>();
        do {
            var glyph = hooks.glyph().apply(tags.group(1));
            String replacement = glyph == null ? "" : Character.toString(0xf0000 + glyphs.size());
            if (glyph != null) glyphs.add(glyph);
            tags.appendReplacement(marked, replacement);
        } while (tags.find());
        tags.appendTail(marked);
        var run = new ArrayList<FormattedText.StyledGlyph>();
        for (var glyph : TextFormatter.parse(marked.toString()).glyphs()) {
            int index = glyph.codePoint() - 0xf0000;
            if (index < 0 || index >= glyphs.size()) run.add(glyph);
            else {
                components.addAll(Arrays.asList(SpigotComponents.of(new FormattedText(List.copyOf(run)))));
                run.clear();
                components.add(glyphs.get(index));
            }
        }
        components.addAll(Arrays.asList(SpigotComponents.of(new FormattedText(run))));
        return components.toArray(BaseComponent[]::new);
    }

    private static BaseComponent[] join(List<BaseComponent[]> lines) {
        var joined = new ArrayList<BaseComponent>();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.add(new TextComponent("\n"));
            joined.addAll(Arrays.asList(lines.get(i)));
        }
        return joined.toArray(BaseComponent[]::new);
    }
    public static FormattedText inherit(FormattedText text,FormattedText.StyledGlyph surrounding){
        var out=new ArrayList<FormattedText.StyledGlyph>();for(var glyph:text.glyphs()){
            boolean own=glyph.rgb()!=-1||glyph.legacyColorChar()!='\0';out.add(new FormattedText.StyledGlyph(glyph.codePoint(),own?glyph.rgb():surrounding.rgb(),own?glyph.legacyColorChar():surrounding.legacyColorChar(),glyph.decorations()!=0?glyph.decorations():surrounding.decorations()));
        }return new FormattedText(out);
    }
    public static void send(Player player,String json){player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.SYSTEM,ComponentSerializer.parse(json));}
    public static void replay(Player player,String json){send(player,ChatIdentity.tag(json,ChatIdentity.REPLAY));}
    public static String blank(int index){char[] spaces={' ','\u2000','\u2001','\u2002','\u2003','\u2004','\u2005','\u2006','\u2007','\u2008','\u2009','\u200A'};StringBuilder text=new StringBuilder();for(int i=0;i<5;i++){text.append(spaces[index%spaces.length]);index/=spaces.length;}return ComponentSerializer.toString(new TextComponent(text.toString()));}
}
