package codes.castled.allium.sign;

import codes.castled.allium.economy.Money;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-written face identity. Display text is never an authority for execution. */
public record SignIdentity(UUID id,Type type,String argument,boolean publicWarp, Money price, boolean priceOverride) {
    public enum Type { COLOR,WARP,KIT,BALANCE,HEAL,REPAIR,DISPOSAL,WORKBENCH,ANVIL,MAIL,INFO;
        public static Type parse(String text){return valueOf(text.trim().equalsIgnoreCase("colour")?"COLOR":text.trim().toUpperCase(Locale.ROOT));}
        public String key(){return name().toLowerCase(Locale.ROOT);}
    }
    public SignIdentity(UUID id, Type type, String argument, boolean publicWarp) {
        this(id,type,argument,publicWarp,new Money(0),false);
    }
    public SignIdentity(UUID id, Type type, String argument, boolean publicWarp, Money price) {
        this(id,type,argument,publicWarp,price,price!=null&&price.minor()>0);
    }
    public SignIdentity {
        Objects.requireNonNull(id);Objects.requireNonNull(type);Objects.requireNonNull(argument);
        Objects.requireNonNull(price);
        if(price.minor()<0 || (price.minor()>0 || priceOverride) && type==Type.COLOR)throw new IllegalArgumentException("invalid price");
        if(price.minor()>0 && !priceOverride)throw new IllegalArgumentException("positive price requires priceOverride");
        if(type==Type.REPAIR){
            argument=argument.isBlank()?"hand":argument.toLowerCase(Locale.ROOT);
            if(!Set.of("hand","all").contains(argument))throw new IllegalArgumentException("invalid repair mode");
        }
        if(argument.length()>256||publicWarp&&type!=Type.WARP)throw new IllegalArgumentException("invalid sign argument");
        if((type==Type.WARP||type==Type.KIT)&&!argument.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("destination required");
    }
    public String encode(){
        return "3:"+id+":"+type.key()+":"+(publicWarp?"1":"0")+":"
                +Base64.getUrlEncoder().withoutPadding().encodeToString(argument.getBytes(StandardCharsets.UTF_8))
                +":"+price.minor()+":"+(priceOverride?"1":"0");
    }
    public static Optional<SignIdentity> decode(String raw){
        if(raw==null||raw.length()>1024)return Optional.empty();
        try{String[] parts=raw.split(":",-1);
            boolean v1=parts.length==5&&parts[0].equals("1");
            boolean v2=parts.length==6&&parts[0].equals("2");
            boolean v3=parts.length==7&&parts[0].equals("3");
            if(!(v1||v2||v3)||!Set.of("0","1").contains(parts[3]))return Optional.empty();
            if(v3&&!Set.of("0","1").contains(parts[6]))return Optional.empty();
            Type type=Type.parse(parts[2]);
            String argument=new String(Base64.getUrlDecoder().decode(parts[4]),StandardCharsets.UTF_8);
            // The old repair route always repaired the held item, regardless of its display text.
            if(v1&&type==Type.REPAIR)argument="hand";
            Money price=new Money(v1?0:Long.parseLong(parts[5]));
            boolean override=v3?parts[6].equals("1"):price.minor()>0;
            return Optional.of(new SignIdentity(UUID.fromString(parts[1]),type,argument,parts[3].equals("1"),price,override));
        }catch(IllegalArgumentException error){return Optional.empty();}
    }
}
