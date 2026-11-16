package codes.castled.allium.hide;

import java.util.*;

/** Immutable command-path policy; execution and suggestions are independent. */
public final class HidePolicy {
    public record Definition(String name,boolean whitelist,List<String> commands,List<String> completions,List<String> commandParents,List<String> completionParents) {
        public Definition {name=group(name);commands=rules(commands);completions=rules(completions);commandParents=List.copyOf(commandParents);completionParents=List.copyOf(completionParents);}
    }
    public record Group(String name,boolean whitelist,List<String> commands,List<String> inheritedAllowed,List<String> completions) {
        public Group {commands=List.copyOf(commands);inheritedAllowed=List.copyOf(inheritedAllowed);completions=List.copyOf(completions);}
        boolean allows(String path,boolean completion){
            if(completion)return whitelist==matches(completions,path,whitelist);
            if(matches(commands,path,false))return whitelist;
            return !whitelist&&(inheritedAllowed.isEmpty()||matches(inheritedAllowed,path,false));
        }
    }
    private final Map<String,Group> groups;
    public HidePolicy(Collection<Definition> definitions){
        if(definitions.size()>128)throw new IllegalArgumentException("too many hide groups");
        Map<String,Definition> raw=new LinkedHashMap<>();for(var d:definitions)if(raw.put(d.name(),d)!=null)throw new IllegalArgumentException("duplicate hide group");
        Map<String,Group> compiled=new LinkedHashMap<>();for(String name:raw.keySet())compile(name,raw,compiled,new HashSet<>());groups=Map.copyOf(compiled);
    }
    private static Group compile(String name,Map<String,Definition> raw,Map<String,Group> result,Set<String> visiting){
        name=group(name);if(result.containsKey(name))return result.get(name);
        Definition d=raw.get(name);if(d==null)throw new IllegalArgumentException("unknown inherited hide group: "+name);
        if(!visiting.add(name))throw new IllegalArgumentException("cyclic hide group inheritance: "+name);
        var commands=new LinkedHashSet<>(d.commands());var allowed=new LinkedHashSet<String>();var tabs=new LinkedHashSet<>(d.completions());
        for(String parent:d.commandParents()){
            Group g=compile(parent,raw,result,visiting);
            if(d.whitelist())commands.addAll(g.commands());else if(g.whitelist()){allowed.addAll(g.commands());allowed.addAll(g.inheritedAllowed());}
        }
        for(String parent:d.completionParents())tabs.addAll(compile(parent,raw,result,visiting).completions());
        if(tabs.remove("^"))tabs.addAll(commands);
        if(commands.contains("^"))throw new IllegalArgumentException("^ is only valid in tabcompletes");
        var g=new Group(name,d.whitelist(),List.copyOf(commands),List.copyOf(allowed),List.copyOf(tabs));visiting.remove(name);result.put(name,g);return g;
    }
    public Set<String> groupNames(){return groups.keySet();}
    public List<Group> select(Collection<String> memberships){
        var selected=new ArrayList<Group>();for(String name:memberships){Group g=groups.get(name.toLowerCase(Locale.ROOT));if(g!=null&&!selected.contains(g))selected.add(g);}
        if(selected.isEmpty()&&groups.containsKey("default"))selected.add(groups.get("default"));return List.copyOf(selected);
    }
    public static boolean allows(List<Group> selected,String path,boolean completion){
        if(normalize(path).isEmpty())return false;
        for(Group g:selected)if(!g.whitelist()&&!g.allows(path,completion))return false;
        if(selected.stream().noneMatch(Group::whitelist))return true;
        return selected.stream().anyMatch(g->g.whitelist()&&g.allows(path,completion));
    }
    public static String normalize(String value){if(value==null)return "";value=value.trim();if(value.startsWith("/"))value=value.substring(1);return value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    public static String root(String value){String p=normalize(value);int end=p.indexOf(' ');return end<0?p:p.substring(0,end);}
    public static String plain(String value){String p=normalize(value);int colon=root(p).indexOf(':');return colon<0?p:p.substring(colon+1);}
    public static boolean matches(Collection<String> rules,String value,boolean ancestors){
        String p=normalize(value);if(p.isEmpty())return false;String plain=plain(p);
        for(String raw:rules){String r=normalize(raw);if(r.equals("*"))return true;
            if(r.endsWith(":*")&&root(p).startsWith(r.substring(0,r.length()-1)))return true;
            if(branch(p,r)||branch(plain,r)||ancestors&&(branch(r,p)||branch(r,plain)))return true;
        }return false;
    }
    public static String suggestionPath(String buffer,String candidate,int offset){
        buffer=buffer==null?"":buffer;if(offset<0||offset>buffer.length()){offset=buffer.lastIndexOf(' ')+1;if(offset==0&&buffer.startsWith("/"))offset=1;}
        return normalize(buffer.substring(0,offset)+(offset<=1&&candidate.startsWith("/")?candidate.substring(1):candidate));
    }
    private static boolean branch(String value,String parent){return value.equals(parent)||value.startsWith(parent+" ");}
    private static String group(String name){if(name==null||!name.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid hide group");return name.toLowerCase(Locale.ROOT);}
    private static List<String> rules(List<String> values){if(values.size()>2048)throw new IllegalArgumentException("too many hide rules");var out=new LinkedHashSet<String>();for(String v:values){String p=normalize(v);if(p.isEmpty()||p.length()>512)throw new IllegalArgumentException("invalid hide rule");out.add(p);}return List.copyOf(out);}
}
