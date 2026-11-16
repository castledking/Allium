package codes.castled.allium.chat;
import com.google.gson.*;
import java.util.*;
/** Empty translation metadata survives component compaction without a visible click target. */
public final class ChatIdentity {
    public static final String ID="allium:chat:id:",REPLAY="allium:chat:replay";
    private ChatIdentity(){}
    public record Metadata(String id,boolean replay){}
    public static String tag(String json,String identity){
        var marker=new JsonObject();marker.addProperty("translate","");marker.addProperty("fallback","");marker.addProperty("insertion",identity);
        var root=new JsonObject();root.addProperty("text","");var extra=new JsonArray();extra.add(JsonParser.parseString(json));extra.add(marker);root.add("extra",extra);return root.toString();
    }
    public static Metadata metadata(String json){
        if(json==null||json.length()>262144)return new Metadata(null,false);
        var nodes=new ArrayDeque<JsonElement>();try{nodes.add(JsonParser.parseString(json));}catch(JsonParseException malformed){return new Metadata(null,false);}
        String id=null;boolean replay=false;int count=0;
        while(!nodes.isEmpty()&&count++<16384){var next=nodes.remove();if(next.isJsonArray()){for(var child:next.getAsJsonArray())nodes.add(child);}
            else if(next.isJsonObject()){
                var obj=next.getAsJsonObject();var insertion=obj.get("insertion");if(insertion!=null&&insertion.isJsonPrimitive()){
                    String value=insertion.getAsString();if(value.startsWith(ID))id=value.substring(ID.length());if(value.equals(REPLAY))replay=true;
                }
                // Traverse only component children, never user text or hover payloads as authority.
                for(String key:List.of("extra","with")){var children=obj.get(key);if(children!=null)nodes.add(children);}
            }
        }return new Metadata(id,replay);
    }
}
