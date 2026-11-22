package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.ChatHoverFrame;
import codes.castled.allium.text.PlayerMeta;
import codes.castled.allium.text.TextFormatter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.regex.Pattern;

/** Optional APIs live only in guarded nested classes; cache is refreshed on the server thread. */
public final class ChatDecorations {
    public record Value(String prefix,String suffix,String group,List<Integer> colors){public static final Value EMPTY=new Value("","","default",List.of());}
    private final PluginStart plugin;
    private static final Pattern UNRESOLVED = Pattern.compile("%[a-zA-Z][a-zA-Z0-9]*_[^%\\r\\n]+%");
    private boolean resolving;
    public ChatDecorations(PluginStart plugin){this.plugin=plugin;}
    public Value query(Player player){
        var metadata=PlayerMeta.of(plugin,player);
        String prefix=metadata.prefix(),suffix=metadata.suffix(),group=metadata.group().isEmpty()?"default":metadata.group();
        List<Integer> colors=List.of();
        if(resolving)return new Value(prefix,suffix,group,colors);
        try{
            resolving=true;
            if(plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")){
                if(prefix.isEmpty())prefix=Papi.value(player,"%luckperms_prefix%");
                if(suffix.isEmpty())suffix=Papi.value(player,"%luckperms_suffix%");
                if(plugin.getConfig().getBoolean("nickname.gradientplus-enabled",true))colors=palette(Papi.value(player,"%gradient_displayname%"));
            }
        }catch(LinkageError|RuntimeException ignored){}finally{resolving=false;}
        return new Value(prefix,suffix,group,colors);
    }
    public static List<Integer> palette(String rendered){
        if(rendered==null||rendered.length()>4096)return List.of();
        List<Integer> out=new ArrayList<>();
        for(var glyph:TextFormatter.parse(rendered).glyphs()){
            int rgb=glyph.rgb()!=-1?glyph.rgb():glyph.legacyColorChar()!='\0'?TextFormatter.legacyColorRgb(glyph.legacyColorChar()):-1;
            if(rgb!=-1&&(out.isEmpty()||out.get(out.size()-1)!=rgb))out.add(rgb);
        }
        if(out.isEmpty()||out.stream().allMatch(v->v==0xffffff))return List.of();
        return List.of(out.get(0),out.get(out.size()-1));
    }
    ChatOutput.Hooks hooks(Player subject) {
        var manager = plugin.getServer().getPluginManager();
        boolean placeholders = manager.isPluginEnabled("PlaceholderAPI");
        var nexo = manager.getPlugin("Nexo");
        var services = plugin.getServer().getServicesManager();
        var frames = services == null ? null : services.load(ChatHoverFrame.class);
        var glyphs = new HashMap<String, Optional<BaseComponent>>();
        return new ChatOutput.Hooks(text -> expand(subject, text, placeholders),
                id -> glyphs.computeIfAbsent(id, key -> Optional.ofNullable(glyph(nexo, key))).orElse(null),
                (frame, lines) -> frames == null ? null : frames.render(subject, frame, lines));
    }

    String expand(Player player, String text) {
        return expand(player, text, plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"));
    }

    private static String expand(Player player, String text, boolean placeholders) {
        if (text.indexOf('%') < 0) return text;
        if (placeholders) {
            try {
                text = Papi.expand(player, text);
            } catch (LinkageError | RuntimeException ignored) {
                // An optional expansion must not interrupt chat.
            }
        }
        return UNRESOLVED.matcher(text).replaceAll("");
    }

    private static BaseComponent glyph(Plugin nexo, String id) {
        if (nexo == null || !nexo.isEnabled()) return null;
        try {
            var fonts = nexo.getClass().getMethod("fontManager").invoke(nexo);
            var glyph = fonts.getClass().getMethod("glyphFromID", String.class).invoke(fonts, id);
            if (glyph == null) return null;
            var type = glyph.getClass();
            char[] chars = (char[]) type.getMethod("getChars").invoke(glyph);
            if (chars.length == 0) return null;
            var component = new TextComponent(String.valueOf(chars[0]));
            component.setFont(type.getMethod("getFont").invoke(glyph).toString());
            component.setColor(ChatColor.WHITE);
            return component;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return null;
        }
    }
    private static final class Papi {
        static String value(Player player,String key){String value=me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,key);return value==null||value.equals(key)?"":value;}
        static String expand(Player player, String template) {
            if (template.contains("{") && template.contains("}")) {
                template = me.clip.placeholderapi.PlaceholderAPI.setBracketPlaceholders(player, template);
            }
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, template);
        }
    }
}
