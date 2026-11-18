package codes.castled.allium.sign;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SignIdentityTest {
    @Test void identitiesRoundTripAndColourImportsToColor(){
        assertEquals(SignIdentity.Type.COLOR,SignIdentity.Type.parse("colour"));
        for(var type:SignIdentity.Type.values()){
            var identity=new SignIdentity(UUID.randomUUID(),type,type==SignIdentity.Type.REPAIR?"hand":"spawn",type==SignIdentity.Type.WARP);
            assertEquals(identity,SignIdentity.decode(identity.encode()).orElseThrow());
        }
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
}
