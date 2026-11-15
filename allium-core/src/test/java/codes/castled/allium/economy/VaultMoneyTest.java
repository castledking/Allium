package codes.castled.allium.economy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultMoneyTest {
    @Test void onlyNarrowBinaryResidueIsSnapped(){
        assertEquals(30,VaultMoney.amount(0.1+0.2).minor());
        assertEquals(123,VaultMoney.amount(1.23000001).minor());
        assertEquals(123,VaultMoney.amount(1.22999999).minor());
        for(double bad:new double[]{1.230000011,1.229999989,1.001,.001,.005,Double.NaN,Double.POSITIVE_INFINITY,-.000000001})
            assertThrows(RuntimeException.class,()->VaultMoney.amount(bad),Double.toString(bad));
        assertEquals(0,VaultMoney.amount(-0.0).minor());
    }
    @Test void adjacentCentsAcrossFullHardRangeRoundTripExactly(){
        for(long edge:new long[]{0,1,29,30,123,1L<<40,1L<<45,Money.HARD-100})
            for(long offset=0;offset<100;offset++)if(edge+offset<=Money.HARD){
                var value=new Money(edge+offset);assertEquals(value,VaultMoney.amount(VaultMoney.egress(value)));
                double negative=VaultMoney.egress(new Money(-value.minor()));assertEquals(-value.minor(),-VaultMoney.amount(-negative).minor());
            }
    }
}
