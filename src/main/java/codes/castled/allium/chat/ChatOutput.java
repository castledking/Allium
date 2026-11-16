package codes.castled.allium.chat;

import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.text.*;
import net.md_5.bungee.api.chat.*;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import java.util.*;

/** Trusted template + separately parsed values. No user body is reparsed as a template. */
public final class ChatOutput {
    private ChatOutput(){}
    private static final Map<String,String> TOKENS=Map.ofEntries(Map.entry("<message>","{message}"),Map.entry("<prefix>","{prefix}"),Map.entry("<suffix>","{suffix}"),Map.entry("<display_name>","{name}"),Map.entry("<name>","{name}"),Map.entry("<player>","{player}"),Map.entry("%allium_nickname%","{name}"),Map.entry("%allium_gradientdisplayname%","{gradient_name}"),Map.entry("%luckperms_prefix%","{prefix}"),Map.entry("%luckperms_suffix%","{suffix}"));
    public static String render(String template,Map<String,FormattedText> values,Player viewer,String id,String realName,String group,ConfigurationSection config){
        for(var entry:TOKENS.entrySet())template=template.replace(entry.getKey(),entry.getValue());
        var markers=new LinkedHashMap<Integer,String>();int index=0;for(String key:values.keySet()){int marker=0xe100+index++;template=template.replace("{"+key+"}",new String(Character.toChars(marker)));markers.put(marker,key);}
        List<BaseComponent> components=new ArrayList<>();
        boolean delete=viewer.hasPermission("allium.delmsg")||viewer.hasPermission("allium.deletemsg")||viewer.hasPermission("allium.staff");
        for(var glyph:TextFormatter.parse(template).glyphs()){
            String key=markers.get(glyph.codePoint());FormattedText text=key==null?new FormattedText(List.of(glyph)):inherit(values.get(key),glyph);
            BaseComponent[] nodes=SpigotComponents.of(text);
            if(key!=null&&Set.of("name","gradient_name","discord_username","prefix","suffix").contains(key)){
                String role=key.equals("gradient_name")||key.equals("discord_username")?"name":key;
                var section=config.getConfigurationSection("chat-hover.groups."+group+"."+role);if(section==null)section=config.getConfigurationSection("chat-hover.groups.default."+role);
                if(section!=null&&!key.equals("discord_username")){
                    String hover=String.join("\n",section.getStringList("hover")).replace("{player}",realName);
                    String click=section.getString("click","").replace("{player}",realName);
                    String action=section.getString("action","suggest-command");
                    for(var node:nodes){if(!hover.isEmpty())node.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,SpigotComponents.of(TextFormatter.parse(hover))));
                        if(!click.isEmpty()){var allowed=switch(action){case "run-command"->ClickEvent.Action.RUN_COMMAND;case "open-url"->ClickEvent.Action.OPEN_URL;case "copy-to-clipboard"->ClickEvent.Action.COPY_TO_CLIPBOARD;case "suggest-command"->ClickEvent.Action.SUGGEST_COMMAND;default->null;};if(allowed!=null)node.setClickEvent(new ClickEvent(allowed,click));}}
                }
                String mode=config.getString("chat-hover.delete-hover-mode","prefix");
                boolean deletionPart=role.equals(mode)||(role.equals("name")&&(!values.containsKey(mode)||values.get(mode).isEmpty()));
                if(delete&&!mode.equals("none")&&deletionPart)for(var node:nodes){node.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/delmsg "+id));node.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,new BaseComponent[]{new TextComponent("Delete message "+id)}));}
            }
            components.addAll(Arrays.asList(nodes));
        }
        return ChatIdentity.tag(ComponentSerializer.toString(components.toArray(new BaseComponent[0])),ChatIdentity.ID+id);
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
