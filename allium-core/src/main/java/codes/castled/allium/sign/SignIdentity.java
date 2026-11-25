package codes.castled.allium.sign;

import codes.castled.allium.economy.Money;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-written face identity. Display text is never an authority for execution. */
public record SignIdentity(UUID id,Type type,String argument,boolean publicWarp, Money price, boolean priceOverride) {
    public enum Type {
        COLOR, WARP, KIT, BALANCE, HEAL, REPAIR, DISPOSAL, WORKBENCH, ANVIL,
        CARTOGRAPHY, GRINDSTONE, LOOM, SMITHING, MAIL, INFO,
        GAMEMODE, TIME, WEATHER, RANDOMTELEPORT, FREE, ENCHANT, SPAWNMOB, BUY, SELL, TRADE;

        public static Type parse(String text) {
            String trimmed = text.trim();
            if (trimmed.equalsIgnoreCase("colour")) return COLOR;
            if (trimmed.equalsIgnoreCase("rtp")) return RANDOMTELEPORT;
            return valueOf(trimmed.toUpperCase(Locale.ROOT));
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
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
        if(type==Type.GAMEMODE){
            argument=normalizeGameMode(argument);
        }
        if(type==Type.TIME){
            argument=normalizeTime(argument);
        }
        if(type==Type.WEATHER){
            argument=normalizeWeather(argument);
        }
        if(type==Type.FREE){
            argument=argument.trim();
            if(argument.isEmpty())throw new IllegalArgumentException("item required");
        }
        if(type==Type.ENCHANT){
            int sep=argument.indexOf('|');
            if(sep<=0||sep>=argument.length()-1)throw new IllegalArgumentException("enchant sign requires item and enchantment");
        }
        if(type==Type.SPAWNMOB){
            int sep=argument.indexOf('|');
            if(sep<=0||sep>=argument.length()-1)throw new IllegalArgumentException("spawnmob sign requires amount and mob");
            int amount=Integer.parseInt(argument.substring(0,sep));
            if(amount<1||amount>256)throw new IllegalArgumentException("invalid spawnmob amount");
        }
        if(type==Type.BUY||type==Type.SELL){
            int sep=argument.indexOf('|');
            if(sep<=0||sep>=argument.length()-1)throw new IllegalArgumentException(type.key()+" sign requires amount and item");
            int amount=Integer.parseInt(argument.substring(0,sep));
            if(amount<1||amount>2304)throw new IllegalArgumentException("invalid "+type.key()+" amount");
            if(argument.substring(sep+1).isBlank())throw new IllegalArgumentException(type.key()+" sign requires item");
            if(price.minor()<=0)throw new IllegalArgumentException(type.key()+" sign requires positive price");
        }
        if(type==Type.TRADE){
            TradeSignSpec.decodeArgument(argument);
            if(price.minor()!=0||priceOverride)throw new IllegalArgumentException("trade sign carries price inside its trade spec");
        }
        if(type==Type.RANDOMTELEPORT&&!argument.isEmpty()&&!argument.matches("[a-zA-Z0-9_-]{1,32}")){
            throw new IllegalArgumentException("invalid randomteleport zone");
        }
        if(argument.length()>256||publicWarp&&type!=Type.WARP)throw new IllegalArgumentException("invalid sign argument");
        if((type==Type.WARP||type==Type.KIT)&&!argument.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("destination required");
    }

    public static String normalizeGameMode(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("0") || lower.contains("survi")) return "survival";
        if (lower.equals("1") || lower.contains("creat")) return "creative";
        if (lower.equals("2") || lower.contains("advent")) return "adventure";
        if (lower.equals("3") || lower.contains("spec")) return "spectator";
        throw new IllegalArgumentException("invalid gamemode: " + raw);
    }

    public static String normalizeTime(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return "";
        if (lower.equals("day") || lower.equals("night")) return lower;
        throw new IllegalArgumentException("invalid time: " + raw);
    }

    public static String normalizeWeather(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return "";
        if (lower.equals("sun") || lower.equals("clear")) return "sun";
        if (lower.equals("storm") || lower.equals("rain")) return "storm";
        throw new IllegalArgumentException("invalid weather: " + raw);
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
