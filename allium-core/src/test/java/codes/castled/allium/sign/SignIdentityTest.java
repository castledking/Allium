package codes.castled.allium.sign;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SignIdentityTest {
    @Test void identitiesRoundTripAndColourImportsToColor(){
        assertEquals(SignIdentity.Type.COLOR,SignIdentity.Type.parse("colour"));
        assertEquals(SignIdentity.Type.RANDOMTELEPORT,SignIdentity.Type.parse("rtp"));
        for(var type:SignIdentity.Type.values()){
            String arg=switch(type){
                case REPAIR->"hand";
                case GAMEMODE->"creative";
                case TIME->"day";
                case WEATHER->"sun";
                case FREE->"stone";
                case ENCHANT->"*|sharpness:1";
                case SPAWNMOB->"1|wolf:tamed";
                case BUY,SELL->"4|stone";
                case TRADE->TradeSignSpec.encodeArgument(UUID.randomUUID(),
                        TradeSignSpec.Offer.money(new codes.castled.allium.economy.Money(500)),
                        TradeSignSpec.Offer.item("stone",4));
                case RANDOMTELEPORT->"wild";
                default->"spawn";
            };
            var price=(type==SignIdentity.Type.BUY||type==SignIdentity.Type.SELL)
                    ?new codes.castled.allium.economy.Money(500):new codes.castled.allium.economy.Money(0);
            var identity=new SignIdentity(UUID.randomUUID(),type,arg,type==SignIdentity.Type.WARP,price,price.minor()>0);
            assertEquals(identity,SignIdentity.decode(identity.encode()).orElseThrow());
        }
        assertThrows(IllegalArgumentException.class,()->new SignIdentity(UUID.randomUUID(),SignIdentity.Type.BUY,"4|stone",false));
        assertThrows(IllegalArgumentException.class,()->new SignIdentity(UUID.randomUUID(),SignIdentity.Type.SELL,"0|stone",false,new codes.castled.allium.economy.Money(100),true));
        assertThrows(IllegalArgumentException.class,()->new SignIdentity(UUID.randomUUID(),SignIdentity.Type.BUY,"stone",false,new codes.castled.allium.economy.Money(100),true));
    }
    @Test void displayHeadersAndMalformedIdentitiesNeverDecode(){
        for(var raw:List.of("[kit]","§1[warp]","1:bad:kit:0:cGFpZA","2:"+UUID.randomUUID()+":kit:0:cGFpZA","1:"+UUID.randomUUID()+":balance:1:"))assertTrue(SignIdentity.decode(raw).isEmpty());
        assertThrows(IllegalArgumentException.class,()->new SignIdentity(UUID.randomUUID(),SignIdentity.Type.KIT,"paid other",false));
    }
    @Test void budgetIsSharedAndRejectsSameTickReplayAndResets(){
        var budget=new SignClickBudget();UUID p=UUID.randomUUID(),other=UUID.randomUUID();
        assertTrue(budget.admit(p,0,2));assertFalse(budget.admit(p,1,2));assertTrue(budget.admit(p,100_000_000,2));
        assertFalse(budget.admit(p,200_000_000,2));assertTrue(budget.admit(other,200_000_000,2));
        assertTrue(budget.admit(p,1_000_000_000,2));budget.forget(p);assertTrue(budget.admit(p,1_000_000_001,2));
    }
    @Test void v3EncodesPriceOverridesAndDecodesLegacyV1AndV2(){
        UUID id=UUID.randomUUID();
        var kitPaid=new SignIdentity(id,SignIdentity.Type.KIT,"starter",false,new codes.castled.allium.economy.Money(40_000),true);
        assertEquals(kitPaid,SignIdentity.decode(kitPaid.encode()).orElseThrow());
        var kitFreeOverride=new SignIdentity(id,SignIdentity.Type.KIT,"vip",false,new codes.castled.allium.economy.Money(0),true);
        var decodedFree=SignIdentity.decode(kitFreeOverride.encode()).orElseThrow();
        assertTrue(decodedFree.priceOverride());assertEquals(0,decodedFree.price().minor());
        var warpPaid=new SignIdentity(id,SignIdentity.Type.WARP,"spawn",true,new codes.castled.allium.economy.Money(500),true);
        assertEquals(warpPaid,SignIdentity.decode(warpPaid.encode()).orElseThrow());
        // Legacy v1 and v2 still decode cleanly.
        String arg=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("all".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var v2=SignIdentity.decode("2:"+id+":repair:0:"+arg+":2500").orElseThrow();
        assertEquals(2500,v2.price().minor());assertTrue(v2.priceOverride());
        assertThrows(IllegalArgumentException.class,()->new SignIdentity(id,SignIdentity.Type.COLOR,"",false,new codes.castled.allium.economy.Money(100),true));
        assertThrows(IllegalArgumentException.class,()->new SignIdentity(id,SignIdentity.Type.COLOR,"",false,new codes.castled.allium.economy.Money(0),true));
    }
}
