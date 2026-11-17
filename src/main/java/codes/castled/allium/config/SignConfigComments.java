package codes.castled.allium.config;
import codes.castled.allium.sign.SignIdentity;
import java.util.*;
/** Keep an opt-in, commented checklist even though YAML treats an all-comment list as null. */
public final class SignConfigComments {
 private SignConfigComments(){}
 public static String format(String yaml,List<String> enabled){
  var lines=new ArrayList<>(Arrays.asList(yaml.split("\n",-1)));int start=-1,end=-1;
  for(int i=0;i<lines.size();i++)if(lines.get(i).startsWith("enabledSigns:")){start=i;end=i+1;while(end<lines.size()&&!lines.get(end).matches("[A-Za-z0-9_-]+:.*"))end++;break;}
  if(start<0){for(int i=0;i<lines.size();i++)if(lines.get(i).startsWith("sign-use-per-second:")){start=i;end=i;while(start>0&&(lines.get(start-1).isBlank()||lines.get(start-1).strip().matches("#\\s*-.*")))start--;break;}}
  if(start<0)return yaml;
  var extras=new ArrayList<String>();for(int i=start;i<end;i++){String l=lines.get(i).strip();if(!l.isEmpty()&&!l.startsWith("enabledSigns:")&&!l.startsWith("-")&&!l.matches("#\\s*-.*"))extras.add(lines.get(i));}
  var normalized=new LinkedHashSet<String>();for(String s:enabled){try{normalized.add(SignIdentity.Type.parse(s).key());}catch(IllegalArgumentException unknown){normalized.add(s);}}
  var block=new ArrayList<String>();block.add("enabledSigns:");for(var type:SignIdentity.Type.values()){boolean on=normalized.remove(type.key());block.add("  "+(on?"- ":"#- ")+type.key());}for(String unknown:normalized)block.add("  - '"+unknown.replace("'","''")+"'");block.addAll(extras);block.add("");lines.subList(start,end).clear();lines.addAll(start,block);return String.join("\n",lines);
 }
}
