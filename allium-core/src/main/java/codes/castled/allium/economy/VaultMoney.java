package codes.castled.allium.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Binary residue tolerance is one millionth of a cent, only at the Vault boundary. */
public final class VaultMoney {
    private static final BigDecimal EPSILON=new BigDecimal("0.00000001");
    private VaultMoney(){}
    public static Money amount(double value){
        if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("finite nonnegative amount required");
        return snap(value);
    }
    private static Money snap(double value){
        if(!Double.isFinite(value))throw new IllegalArgumentException("finite money required");
        BigDecimal decimal=BigDecimal.valueOf(value),cent=decimal.setScale(2,RoundingMode.HALF_EVEN);
        if(decimal.subtract(cent).abs().compareTo(EPSILON)>0)throw new IllegalArgumentException("genuine sub-cent amount");
        return Money.exact(cent);
    }
    public static double egress(Money money){
        double output=money.decimal().doubleValue();
        if(!snap(output).equals(money))throw new IllegalArgumentException("money cannot round-trip through Vault double");
        return output;
    }
    public static void validate(EconomyResult.Mutation result){
        EconomyResult.Found source=null,target=null;
        if(result instanceof EconomyResult.Committed c){source=c.source();target=c.recipient();}
        if(result instanceof EconomyResult.Rejected r){source=r.source();target=r.recipient();}
        for(var found:new EconomyResult.Found[]{source,target})if(found!=null){egress(found.book());egress(found.reserved());egress(found.available());}
    }
}
