package codes.castled.allium.economy;
import codes.castled.allium.PluginStart;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import java.nio.file.Path;
import java.sql.SQLException;
import java.io.IOException;
import static codes.castled.allium.economy.EconomyResult.*;

/** Async callers only: authoritative reservations, not a Vault withdraw/refund saga. */
public final class PaidWorkflowSupport {
    private PaidWorkflowSupport(){}
    public static final class UnknownCommit extends SQLException {
        private final java.util.UUID operationId;
        public UnknownCommit(java.util.UUID operationId){super("OUTCOME_UNKNOWN:"+operationId);this.operationId=operationId;}
        public java.util.UUID operationId(){return operationId;}
    }
    public static boolean ready(PluginStart plugin){return plugin.getModuleManager()!=null&&plugin.getModuleManager().getModule("economy") instanceof EconomyModule m&&m.isEnabled()&&m.runtime()!=null&&m.runtime().ready();}
    public static EconomyReservations reservations(PluginStart plugin)throws SQLException{
        if(!ready(plugin))throw new SQLException("paid workflow economy unavailable");
        return ((EconomyModule)plugin.getModuleManager().getModule("economy")).runtime().reservations();
    }
    public static boolean committed(Mutation result)throws SQLException{
        if(result instanceof Committed)return true;
        if(result instanceof Rejected refused&&refused.reason().equals("WORKFLOW_REFUSED"))return false;
        if(result instanceof Rejected refused)throw new SQLException("paid workflow refused: "+refused.reason());
        if(result instanceof Unknown unknown)throw new UnknownCommit(unknown.operationId());
        throw new SQLException("paid workflow storage unavailable");
    }
    /** Executed inside the capture/release transaction, while inventory admission remains locked. */
    public static void prove(Path file,String key,String expected)throws SQLException{
        try{if(expected==null||expected.isEmpty())PlayerDataReceiptProof.verifyAbsent(file,key);else PlayerDataReceiptProof.verify(file,key,expected);}
        catch(IOException error){throw new SQLException("saved native receipt evidence is unavailable",error);}
    }
}
