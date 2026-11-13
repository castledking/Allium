package codes.castled.allium.mail;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Typed PDC sidecar: native cross-version item fixers must not reinterpret signed string values. */
final class PersistentItemData {
    private PersistentItemData() {}
    private static final List<PersistentDataType<?, ?>> TYPES = List.of(PersistentDataType.BYTE, PersistentDataType.SHORT,
            PersistentDataType.INTEGER, PersistentDataType.LONG, PersistentDataType.FLOAT, PersistentDataType.DOUBLE,
            PersistentDataType.STRING, PersistentDataType.BYTE_ARRAY, PersistentDataType.INTEGER_ARRAY,
            PersistentDataType.LONG_ARRAY, PersistentDataType.TAG_CONTAINER, PersistentDataType.TAG_CONTAINER_ARRAY);
    private static final List<String> NAMES = List.of("byte","short","int","long","float","double","string",
            "bytes","ints","longs","container","containers");

    static Map<String,Object> capture(PersistentDataContainer data) { return capture(data,0,new int[1]); }
    private static Map<String,Object> capture(PersistentDataContainer data,int depth,int[] budget) {
        if(depth>16) throw new IllegalArgumentException("gift persistent data nesting too deep");
        Map<String,Object> out = new LinkedHashMap<>();
        for(NamespacedKey key : data.getKeys().stream().sorted(Comparator.comparing(NamespacedKey::toString)).toList()) {
            if(++budget[0]>4096) throw new IllegalArgumentException("gift has too many persistent data entries");
            int index=-1;
            for(int i=0;i<TYPES.size();i++) if(data.has(key,TYPES.get(i))) { index=i; break; }
            if(index<0) throw new IllegalArgumentException("unsupported persistent data type at "+key);
            Object value=data.get(key,TYPES.get(index));
            Object encoded=switch(index) {
                case 4 -> Integer.toUnsignedString(Float.floatToRawIntBits((Float)value));
                case 5 -> Long.toUnsignedString(Double.doubleToRawLongBits((Double)value));
                case 7 -> { List<String> a=new ArrayList<>(); for(byte n:(byte[])value)a.add(Byte.toString(n)); yield a; }
                case 8 -> Arrays.stream((int[])value).mapToObj(Integer::toString).toList();
                case 9 -> Arrays.stream((long[])value).mapToObj(Long::toString).toList();
                case 10 -> capture((PersistentDataContainer)value,depth+1,budget);
                case 11 -> Arrays.stream((PersistentDataContainer[])value).map(c->capture(c,depth+1,budget)).toList();
                default -> value.toString();
            };
            out.put(key.toString(),Map.of("type",NAMES.get(index),"value",encoded));
        }
        return out;
    }

    static void restore(PersistentDataContainer target,Map<?,?> snapshot) { restore(target,snapshot,0,new int[1]); }
    private static void restore(PersistentDataContainer target,Map<?,?> snapshot,int depth,int[] budget) {
        if(depth>16) throw new IllegalArgumentException("gift persistent data nesting too deep");
        for(NamespacedKey key : Set.copyOf(target.getKeys())) target.remove(key);
        for(var entry:snapshot.entrySet()) {
            if(++budget[0]>4096 || !(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?,?> tag))
                throw new IllegalArgumentException("invalid gift persistent data entry");
            NamespacedKey key=NamespacedKey.fromString(name);
            int type=NAMES.indexOf(tag.get("type"));
            if(key==null || type<0) throw new IllegalArgumentException("invalid gift persistent data key/type");
            Object raw=tag.get("value");
            Object value=switch(type) {
                case 0 -> Byte.valueOf((String)raw);
                case 1 -> Short.valueOf((String)raw);
                case 2 -> Integer.valueOf((String)raw);
                case 3 -> Long.valueOf((String)raw);
                case 4 -> Float.intBitsToFloat(Integer.parseUnsignedInt((String)raw));
                case 5 -> Double.longBitsToDouble(Long.parseUnsignedLong((String)raw));
                case 6 -> (String)raw;
                case 7 -> { List<?> a=(List<?>)raw; byte[] b=new byte[a.size()]; for(int i=0;i<b.length;i++)b[i]=Byte.parseByte((String)a.get(i)); yield b; }
                case 8 -> ((List<?>)raw).stream().mapToInt(n->Integer.parseInt((String)n)).toArray();
                case 9 -> ((List<?>)raw).stream().mapToLong(n->Long.parseLong((String)n)).toArray();
                case 10 -> { var child=target.getAdapterContext().newPersistentDataContainer(); restore(child,(Map<?,?>)raw,depth+1,budget); yield child; }
                case 11 -> { List<?> a=(List<?>)raw; PersistentDataContainer[] children=new PersistentDataContainer[a.size()];
                    for(int i=0;i<children.length;i++){children[i]=target.getAdapterContext().newPersistentDataContainer();restore(children[i],(Map<?,?>)a.get(i),depth+1,budget);} yield children; }
                default -> throw new IllegalArgumentException("unknown persistent data type");
            };
            set(target,key,TYPES.get(type),value);
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void set(PersistentDataContainer data,NamespacedKey key,PersistentDataType type,Object value) { data.set(key,type,value); }
}
