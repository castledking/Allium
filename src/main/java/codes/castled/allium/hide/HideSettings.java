package codes.castled.allium.hide;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/** A validated replacement is prepared before it becomes visible to listeners. */
public record HideSettings(boolean enabled,boolean useResolver,boolean blockNamespacedForBypass,boolean hideNamespacedForBypass,
        boolean removeUnsafe,Set<String> unsafe,boolean filterSuggestions,boolean cancelBareSlash,boolean cancelUnknown,
        Set<String> namespacedAllow,List<String> suggestionPrefixes,String hint,HidePolicy policy,
        Map<String,String> permissionOverrides) {
    public static HideSettings read(YamlConfiguration c,Map<String,Set<String>> implicitParents){
        for(String section:List.of("settings","groups"))if(c.contains(section)&&!c.isConfigurationSection(section))throw new IllegalArgumentException("hide "+section+" must be a section");
        for(String flag:List.of("enabled","use-allium-permission-resolver","block-namespaced-commands-for-ops","hide-namespaced-commands-for-ops","remove-unsafe-commands","filter-command-suggestions","cancel-bare-slash-request","cancel-unknown-suggestion-responses"))if(c.contains("settings."+flag)&&!(c.get("settings."+flag) instanceof Boolean))throw new IllegalArgumentException("hide flag must be boolean: "+flag);
        if(c.contains("permission-overrides")&&!(c.get("permission-overrides") instanceof List<?>))throw new IllegalArgumentException("permission-overrides must be a list");
        if(c.contains("settings.unknown-command-completion-help-message")&&(!(c.get("settings.unknown-command-completion-help-message") instanceof String text)||text.length()>4096))throw new IllegalArgumentException("invalid command hint");
        var definitions=new ArrayList<HidePolicy.Definition>();var groups=c.getConfigurationSection("groups");
        if(groups!=null)for(String name:groups.getKeys(false)){
            var g=groups.getConfigurationSection(name);if(g!=null&&g.contains("whitelist")&&!(g.get("whitelist") instanceof Boolean))throw new IllegalArgumentException("hide whitelist must be boolean");if(g==null)throw new IllegalArgumentException("hide group must be a section");
            var commandParents=new LinkedHashSet<>(strings(g,"inherits.commands"));var tabParents=new LinkedHashSet<>(strings(g,"inherits.tabcompletes"));
            for(String parent:implicitParents.getOrDefault(name.toLowerCase(Locale.ROOT),Set.of()))if(groups.getKeys(false).stream().anyMatch(k->k.equalsIgnoreCase(parent))){commandParents.add(parent);tabParents.add(parent);}
            definitions.add(new HidePolicy.Definition(name,g.getBoolean("whitelist",true),strings(g,"commands"),strings(g,"tabcompletes"),List.copyOf(commandParents),List.copyOf(tabParents)));
        }
        var overrides=new LinkedHashMap<String,String>();for(Map<?,?> row:c.getMapList("permission-overrides")){
            String command=Objects.toString(row.get("command"),"").toLowerCase(Locale.ROOT),permission=Objects.toString(row.get("permission"),"");
            if(!command.matches("[a-z0-9_:?.-]{1,128}")||!permission.matches("[A-Za-z0-9_.*:-]{1,256}"))throw new IllegalArgumentException("invalid hide permission override");
            if(overrides.put(command,permission)!=null)throw new IllegalArgumentException("duplicate hide permission override");
        }
        return new HideSettings(c.getBoolean("settings.enabled",false),c.getBoolean("settings.use-allium-permission-resolver",true),c.getBoolean("settings.block-namespaced-commands-for-ops",false),c.getBoolean("settings.hide-namespaced-commands-for-ops",false),
            c.getBoolean("settings.remove-unsafe-commands",true),normalized(c,"settings.unsafe-commands-list"),c.getBoolean("settings.filter-command-suggestions",true),c.getBoolean("settings.cancel-bare-slash-request",true),c.getBoolean("settings.cancel-unknown-suggestion-responses",true),
            normalized(c,"settings.allow-namespaced-suggestions"),List.copyOf(normalized(c,"settings.command-suggestion-filters")),c.getString("settings.unknown-command-completion-help-message",""),new HidePolicy(definitions),Map.copyOf(overrides));
    }
    public boolean namespacedVisible(String label,boolean bypass){
        if(!HidePolicy.root(label).contains(":"))return true;
        if(bypass)return !hideNamespacedForBypass;
        return namespacedAllow.stream().anyMatch(rule->rule.equalsIgnoreCase(HidePolicy.root(label))||rule.endsWith(":*")&&HidePolicy.root(label).startsWith(rule.substring(0,rule.length()-1)));
    }
    private static List<String> strings(ConfigurationSection c,String path){Object raw=c.get(path);if(raw==null)return List.of();if(!(raw instanceof List<?> values)||values.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("hide "+path+" must be a string list");return values.stream().map(String.class::cast).toList();}
    private static Set<String> normalized(ConfigurationSection c,String path){var out=new LinkedHashSet<String>();for(String value:strings(c,path)){String n=HidePolicy.normalize(value);if(n.length()>512)throw new IllegalArgumentException("hide rule too long");if(!n.isEmpty())out.add(n);}if(out.size()>4096)throw new IllegalArgumentException("too many hide filters");return Collections.unmodifiableSet(out);}
}
