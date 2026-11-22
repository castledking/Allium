package codes.castled.allium.chat;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.text.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatOutputTest {
    private static void collect(net.md_5.bungee.api.chat.BaseComponent node,List<net.md_5.bungee.api.chat.BaseComponent> out){out.add(node);if(node.getExtra()!=null)for(var child:node.getExtra())collect(child,out);}
    @Test void bodyCannotInjectTemplatePlaceholdersOrInteractiveTags(){var viewer=mock(Player.class);var body=UntrustedText.parse("{name} <click:run_command:'/op me'>https://x.test</click>",UntrustedText.Allow.NONE);String json=ChatOutput.render("&a{name}&f: {message}",Map.of("name",UntrustedText.parse("Alice",UntrustedText.Allow.NONE),"message",body),viewer,"id","Alice","default",new YamlConfiguration());assertTrue(json.contains("{name}"));assertFalse(json.contains("clickEvent"));assertFalse(json.contains("hoverEvent"));assertTrue(json.contains("run_command"));}
    @Test void onlyStaffNameGetsDeletionControls(){var viewer=mock(Player.class);when(viewer.hasPermission("allium.delmsg")).thenReturn(true);var c=new YamlConfiguration();c.set("chat-hover.delete-hover-mode","name");String json=ChatOutput.render("{name}: {message}",Map.of("name",UntrustedText.parse("Alice",UntrustedText.Allow.NONE),"message",UntrustedText.parse("BODY",UntrustedText.Allow.NONE)),viewer,"session:4","Alice","default",c);assertTrue(json.contains("/delmsg session:4"));var nodes=net.md_5.bungee.chat.ComponentSerializer.parse(json);var all=new ArrayList<net.md_5.bungee.api.chat.BaseComponent>();for(var node:nodes)collect(node,all);var body=all.stream().filter(n->n.toPlainText().equals("BODY")).findFirst().orElseThrow();assertNull(body.getClickEvent());assertNull(body.getHoverEvent());}
    @Test void blankEntriesAreDistinctPacketsNotNewlineBatches(){var out=new HashSet<String>();for(int i=0;i<500;i++){String json=ChatOutput.blank(i);assertFalse(json.contains("\\n"));assertTrue(out.add(json));}assertEquals(500,out.size());}
    @Test void staffPrefixRetainsConfiguredHoverAboveDeletionFooter() {
        var staff = mock(Player.class);
        when(staff.hasPermission("allium.delmsg")).thenReturn(true);
        var regular = mock(Player.class);
        var config = new YamlConfiguration();
        config.set("chat-hover.groups.default.prefix.hover", List.of("&7{player}", "&6First Joined"));
        config.set("chat-hover.groups.default.prefix.action", "run-command");
        config.set("chat-hover.groups.default.prefix.click", "/help");
        var values = Map.of("prefix", TextFormatter.parse("&c[Owner]"), "name", TextFormatter.parse("Alice"));
        String staffJson = ChatOutput.render("{prefix}{name}", values, staff, "session:4", "Alice", "owner", config);
        String regularJson = ChatOutput.render("{prefix}{name}", values, regular, "session:4", "Alice", "owner", config);
        assertTrue(staffJson.contains("First Joined"), staffJson);
        assertTrue(staffJson.indexOf("First Joined") < staffJson.indexOf("Click to delete this message"), staffJson);
        assertTrue(staffJson.contains("/delmsg session:4"));
        assertTrue(regularJson.contains("First Joined"));
        assertTrue(regularJson.contains("/help"));
        assertFalse(regularJson.contains("/delmsg"));
    }
    @Test void gradientPlusWhiteMeansUnsetAndRealEndpointsAreRetained(){assertTrue(ChatDecorations.palette("&fAlice").isEmpty());assertTrue(ChatDecorations.palette("%gradient_displayname%").isEmpty());assertEquals(List.of(0xff0000,0x00ff00),ChatDecorations.palette("&#ff0000A&#00ff00B"));}
    @Test void channelSettingsRejectAmbiguousAliasesAndBadRadius(){var c=new YamlConfiguration();c.set("chat.channels.list.alpha.aliases",List.of("same"));c.set("chat.channels.list.beta.aliases",List.of("same"));final var ambiguous=c;assertThrows(IllegalArgumentException.class,()->ChatSettings.read(ambiguous));c=new YamlConfiguration();c.set("chat.channels.local-channel.radius",Double.NaN);final var bad=c;assertThrows(IllegalArgumentException.class,()->ChatSettings.read(bad));}
}
