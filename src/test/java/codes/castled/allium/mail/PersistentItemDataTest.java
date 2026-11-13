package codes.castled.allium.mail;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PersistentItemDataTest {
    private static final NamespacedKey UUID_KEY = new NamespacedKey("allium","instance_uuid");
    private static final NamespacedKey STATE_KEY = new NamespacedKey("allium","state_charges");
    private record Tag(PersistentDataType<?,?> type,Object value) {}
    private static PersistentDataContainer container() {
        Map<NamespacedKey,Tag> tags=new HashMap<>();
        PersistentDataContainer data=mock(PersistentDataContainer.class);
        PersistentDataAdapterContext context=mock(PersistentDataAdapterContext.class);
        when(data.getAdapterContext()).thenReturn(context);
        when(context.newPersistentDataContainer()).thenAnswer(c->container());
        when(data.getKeys()).thenAnswer(c->Set.copyOf(tags.keySet()));
        when(data.has(any(NamespacedKey.class),any())).thenAnswer(c->{Tag tag=tags.get(c.getArgument(0));PersistentDataType<?,?> type=c.getArgument(1);return tag!=null && tag.type().getPrimitiveType().equals(type.getPrimitiveType());});
        when(data.get(any(NamespacedKey.class),any())).thenAnswer(c->{Tag tag=tags.get(c.getArgument(0));PersistentDataType<?,?> type=c.getArgument(1);return tag!=null && tag.type().getPrimitiveType().equals(type.getPrimitiveType()) ? tag.value():null;});
        doAnswer(c->{tags.put(c.getArgument(0),new Tag(c.getArgument(1),c.getArgument(2)));return null;}).when(data).set(any(),any(),any());
        doAnswer(c->{tags.remove(c.getArgument(0));return null;}).when(data).remove(any());
        return data;
    }
    @Test void numericLookingUuidAndStateStringsAreRestoredWithoutParsingOrResigning() {
        var original=container();
        String uuid="5505d6a7-a21f-4b2e-bb4b-7bd3858dd687";
        original.set(UUID_KEY,PersistentDataType.STRING,uuid);
        original.set(STATE_KEY,PersistentDataType.STRING,"03");
        var signature=new NamespacedKey("allium","signature");
        original.set(signature,PersistentDataType.STRING,"v2:k1:exact-original-mac");
        var target=container();
        target.set(UUID_KEY,PersistentDataType.STRING,"5505.0");
        target.set(STATE_KEY,PersistentDataType.INTEGER,3);
        target.set(new NamespacedKey("wrong","extra"),PersistentDataType.STRING,"remove");
        PersistentItemData.restore(target,PersistentItemData.capture(original));
        assertEquals(uuid,target.get(UUID_KEY,PersistentDataType.STRING));
        assertEquals("03",target.get(STATE_KEY,PersistentDataType.STRING));
        assertFalse(target.has(STATE_KEY,PersistentDataType.INTEGER));
        assertEquals("v2:k1:exact-original-mac",target.get(signature,PersistentDataType.STRING));
        assertEquals(original.getKeys(),target.getKeys());
    }
    @Test void allBaselinePrimitiveTypesAndNestedContainersRoundTripExactly() {
        var original=container();
        var child=container(); child.set(UUID_KEY,PersistentDataType.STRING,"value:with\\0delimiters");
        List<PersistentDataType<?,?>> types=List.of(PersistentDataType.BYTE,PersistentDataType.SHORT,PersistentDataType.INTEGER,PersistentDataType.LONG,
                PersistentDataType.FLOAT,PersistentDataType.DOUBLE,PersistentDataType.STRING,PersistentDataType.BYTE_ARRAY,PersistentDataType.INTEGER_ARRAY,
                PersistentDataType.LONG_ARRAY,PersistentDataType.TAG_CONTAINER,PersistentDataType.TAG_CONTAINER_ARRAY);
        List<Object> values=List.of((byte)-128,(short)32000,Integer.MIN_VALUE,Long.MAX_VALUE,Float.intBitsToFloat(0x80000000),Double.longBitsToDouble(0x7ff8000000000001L),
                "yes",new byte[]{-128,0,127},new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE},new long[]{Long.MIN_VALUE,Long.MAX_VALUE},child,new PersistentDataContainer[]{child});
        for(int i=0;i<types.size();i++)set(original,new NamespacedKey("test","type_"+i),types.get(i),values.get(i));
        var snapshot=PersistentItemData.capture(original);
        var target=container(); PersistentItemData.restore(target,snapshot);
        assertEquals(snapshot,PersistentItemData.capture(target),"type tags and IEEE bits survive the sidecar");
        assertArrayEquals(new long[]{Long.MIN_VALUE,Long.MAX_VALUE},target.get(new NamespacedKey("test","type_9"),PersistentDataType.LONG_ARRAY));
    }
    @Test void unknownTypesOrMalformedSidecarsAreRefused() {
        var original=container();
        var unknown=mock(PersistentDataContainer.class);
        when(unknown.getKeys()).thenReturn(Set.of(UUID_KEY));
        assertThrows(IllegalArgumentException.class,()->PersistentItemData.capture(unknown));
        assertThrows(IllegalArgumentException.class,()->PersistentItemData.restore(original,Map.of("allium:id",Map.of("type","mystery","value","x"))));
        assertThrows(RuntimeException.class,()->PersistentItemData.restore(original,Map.of("allium:id",Map.of("type","byte","value","256"))));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void set(PersistentDataContainer data,NamespacedKey key,PersistentDataType type,Object value) { data.set(key,type,value); }
}
