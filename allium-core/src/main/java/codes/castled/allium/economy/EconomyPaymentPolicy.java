package codes.castled.allium.economy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Authoritative receiver-owned acceptance and ignore policy, using the money transaction. */
public final class EconomyPaymentPolicy {
    private EconomyPaymentPolicy() {}
    public static boolean permits(Connection c, UUID sender, UUID recipient, EconomySettings settings)throws SQLException {
        boolean accept=settings.defaultAcceptance();
        try(var s=c.prepareStatement("SELECT accept_payments FROM allium_preferences WHERE uuid=?")){
            s.setString(1,recipient.toString());try(var r=s.executeQuery()){if(r.next()){boolean stored=r.getBoolean(1);if(!r.wasNull())accept=stored;}}
        }
        if(!accept)return false;
        if(settings.excludesIgnore())try(var s=c.prepareStatement("SELECT 1 FROM allium_ignores WHERE owner=? AND ignored=?")){
            s.setString(1,recipient.toString());s.setString(2,sender.toString());try(var r=s.executeQuery()){if(r.next())return false;}
        }
        return true;
    }
}
