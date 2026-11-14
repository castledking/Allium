package gate;
import java.lang.reflect.*;
import java.util.*;

/** Actual runtime STREAM_CODEC output; no guessed enum ordinals or movement flags. */
final class NativePackets {
    static Map<String,Object> encode(Class<?> type,Object packet) throws Exception {
        Class<?> buf=Class.forName("io.netty.buffer.ByteBuf");
        Object raw=Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
        Object friendly=Class.forName("net.minecraft.network.FriendlyByteBuf").getConstructor(buf).newInstance(raw);
        try {
            Object codec=type.getField("STREAM_CODEC").get(null);
            Class.forName("net.minecraft.network.codec.StreamEncoder").getMethod("encode",Object.class,Object.class).invoke(codec,friendly,packet);
            int n=(Integer)buf.getMethod("readableBytes").invoke(raw);
            byte[] bytes=new byte[n];buf.getMethod("getBytes",int.class,byte[].class).invoke(raw,0,bytes);
            Object packetType=type.getMethod("type").invoke(packet);
            String name=Class.forName("net.minecraft.network.protocol.PacketType").getMethod("id").invoke(packetType).toString();
            return Map.of("packet",name,"hex",HexFormat.of().formatHex(bytes),"codec",type.getName()+".STREAM_CODEC");
        } finally {buf.getMethod("release").invoke(raw);}
    }
    static Map<String,Object> move(double x,double y,double z)throws Exception {
        Class<?> c=Class.forName("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos");
        return encode(c,c.getConstructor(double.class,double.class,double.class,boolean.class,boolean.class).newInstance(x,y,z,true,false));
    }
    @SuppressWarnings({"unchecked","rawtypes"}) static Map<String,Object> respawn()throws Exception {
        Class<?> a=Class.forName("net.minecraft.network.protocol.game.ServerboundClientCommandPacket$Action");
        Class<?> c=Class.forName("net.minecraft.network.protocol.game.ServerboundClientCommandPacket");
        return encode(c,c.getConstructor(a).newInstance(Enum.valueOf((Class)a,"PERFORM_RESPAWN")));
    }
    static Map<String,Object> proofs()throws Exception {
        Class<?> relative=Class.forName("net.minecraft.world.entity.Relative");
        Map<String,Object> masks=new LinkedHashMap<>();
        for(Object e:relative.getEnumConstants())masks.put(e.toString(),relative.getMethod("pack",Set.class).invoke(null,Set.of(e)));
        Class<?> accept=Class.forName("net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket");
        Object packet=accept.getConstructor(int.class,double.class,double.class,double.class,float.class,float.class).newInstance(123,1.25,80.5,-3.75,20f,-10f);
        Class<?> vec=Class.forName("net.minecraft.world.phys.Vec3");
        Object position=vec.getConstructor(double.class,double.class,double.class).newInstance(1.25,80.5,-3.75);
        Object velocity=vec.getConstructor(double.class,double.class,double.class).newInstance(.125,.25,.5);
        Class<?> change=Class.forName("net.minecraft.world.entity.PositionMoveRotation");
        Object delta=change.getConstructor(vec,vec,float.class,float.class).newInstance(position,velocity,20f,-10f);
        Class<?> clientbound=Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket");
        Object correction=clientbound.getConstructor(int.class,change,Set.class).newInstance(123,delta,Set.of(relative.getField("X").get(null),relative.getField("Y_ROT").get(null)));
        return Map.of("move",move(1.25,80.5,-3.75),"acceptTeleport",encode(accept,packet),"position",encode(clientbound,correction),"respawn",respawn(),"relativeMasks",masks);
    }
}
