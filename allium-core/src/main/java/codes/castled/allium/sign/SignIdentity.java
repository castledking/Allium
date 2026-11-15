package codes.castled.allium.sign;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-written face identity. Display text is never an authority for execution. */
public record SignIdentity(UUID id,Type type,String argument,boolean publicWarp) {
    public enum Type { COLOR,WARP,KIT,BALANCE,HEAL,REPAIR,DISPOSAL,WORKBENCH,ANVIL,MAIL,INFO;
        public static Type parse(String text){return valueOf(text.trim().equalsIgnoreCase("colour")?"COLOR":text.trim().toUpperCase(Locale.ROOT));}
        public String key(){return name().toLowerCase(Locale.ROOT);}
    }
    public SignIdentity {
        Objects.requireNonNull(id);Objects.requireNonNull(type);Objects.requireNonNull(argument);
        if(argument.length()>256||publicWarp&&type!=Type.WARP)throw new IllegalArgumentException("invalid sign argument");
        if((type==Type.WARP||type==Type.KIT)&&!argument.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("destination required");
    }
    public String encode(){return "1:"+id+":"+type.key()+":"+(publicWarp?"1":"0")+":"+Base64.getUrlEncoder().withoutPadding().encodeToString(argument.getBytes(StandardCharsets.UTF_8));}
    public static Optional<SignIdentity> decode(String raw){
        if(raw==null||raw.length()>1024)return Optional.empty();
        try{String[] parts=raw.split(":",-1);if(parts.length!=5||!parts[0].equals("1")||!Set.of("0","1").contains(parts[3]))return Optional.empty();
            return Optional.of(new SignIdentity(UUID.fromString(parts[1]),Type.parse(parts[2]),new String(Base64.getUrlDecoder().decode(parts[4]),StandardCharsets.UTF_8),parts[3].equals("1")));
        }catch(IllegalArgumentException error){return Optional.empty();}
    }
}
