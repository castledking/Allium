package codes.castled.allium.economy;
import codes.castled.allium.storage.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static codes.castled.allium.economy.EconomyResult.*;

/** Stable workflow keys; each step shares the money journal and one caller-supplied SQL transition. */
public final class EconomyReservations {
    public enum Kind { KIT, MAIL }
    public record Identity(Kind kind,UUID id){}
    public record Reservation(Kind kind,UUID id,UUID account,Money amount,String intentHash,String state,long version){}
    @FunctionalInterface public interface Executor {Mutation execute(EconomyRequest request,EconomyEngine.WorkflowTransition transition);}
    private final EconomyEngine engine;private final ConnectionSource connections;private final Executor executor;
    EconomyReservations(EconomyEngine engine,ConnectionSource connections,Executor executor){this.engine=engine;this.connections=connections;this.executor=executor;}
    static Identity identity(EconomyRequest q){
        Kind kind=Kind.valueOf(q.origin().substring("RESERVATION:".length()));UUID id=UUID.fromString(q.key().substring(0,36));
        if(!q.origin().equals("RESERVATION:"+kind)||!q.key().equals(id+":"+q.kind())||!q.id().equals(operation(kind,id,q.kind())))throw new IllegalArgumentException("noncanonical reservation intent");
        if(!q.actor().matches("WORKFLOW:[0-9a-f]{64}"))throw new IllegalArgumentException("canonical workflow fingerprint required");
        return new Identity(kind,id);
    }
    /** Length-framed UTF-8 avoids delimiter collisions; null is distinct from empty text. */
    public static String fingerprint(String... fields){
        try{var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(String field:fields){byte[] bytes=field==null?null:field.getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());if(bytes!=null)hash.update(bytes);}
            return java.util.HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static UUID operation(Kind kind,UUID id,EconomyRequest.Kind step){return UUID.nameUUIDFromBytes(("Allium-Reservation:"+kind+":"+id+":"+step).getBytes(StandardCharsets.US_ASCII));}
    private Mutation execute(Kind kind,UUID id,UUID account,Money amount,String intentHash,EconomyRequest.Kind step,EconomyEngine.WorkflowTransition transition){
        return executor.execute(engine.request(operation(kind,id,step),"RESERVATION:"+kind,id+":"+step,step,"WORKFLOW:"+intentHash,account,null,amount),transition);
    }
    public Mutation reserve(Kind kind,UUID id,UUID account,Money amount,EconomyEngine.WorkflowTransition pending){return reserve(kind,id,account,amount,fingerprint(kind.name(),id.toString(),account.toString(),Long.toString(amount.minor())),pending);}
    public Mutation reserve(Kind kind,UUID id,UUID account,Money amount,String intentHash,EconomyEngine.WorkflowTransition pending){return execute(kind,id,account,amount,intentHash,EconomyRequest.Kind.RESERVE,pending);}
    public Mutation spend(Kind kind,UUID id,UUID account,Money amount,String intentHash,EconomyEngine.WorkflowTransition ready){return execute(kind,id,account,amount,intentHash,EconomyRequest.Kind.SPEND,ready);}
    public Mutation capture(Kind kind,UUID id,EconomyEngine.WorkflowTransition provenReceipt)throws SQLException{return terminal(kind,id,EconomyRequest.Kind.CAPTURE,provenReceipt);}
    public Mutation release(Kind kind,UUID id,EconomyEngine.WorkflowTransition provenAbsence)throws SQLException{return terminal(kind,id,EconomyRequest.Kind.RELEASE,provenAbsence);}
    private Mutation terminal(Kind kind,UUID id,EconomyRequest.Kind step,EconomyEngine.WorkflowTransition transition)throws SQLException{
        var row=find(kind,id);if(row.isEmpty())return new Rejected(operation(kind,id,step),"MISSING_RESERVATION");
        return execute(kind,id,row.get().account(),row.get().amount(),row.get().intentHash(),step,transition);
    }
    public Optional<Reservation> find(Kind kind,UUID id)throws SQLException{try(var c=connections.connection()){return find(c,kind,id);}}
    static Optional<Reservation> find(Connection c,Kind kind,UUID id)throws SQLException{
        try(var s=c.prepareStatement("SELECT account_uuid,amount_minor,intent_hash,state,version FROM "+EconomySchema.RESERVATIONS+" WHERE workflow_kind=? AND workflow_id=?")){
            s.setString(1,kind.name());s.setString(2,id.toString());try(var r=s.executeQuery()){return r.next()?Optional.of(new Reservation(kind,id,UUID.fromString(r.getString(1)),new Money(r.getLong(2)),r.getString(3),r.getString(4),r.getLong(5))):Optional.empty();}
        }
    }
    /** Marker remains after terminal steps, so disabling economy cannot turn a paid recovery into a free activation. */
    public static boolean paid(ConnectionSource source,SqlDialect dialect,Kind kind,UUID id)throws SQLException{
        if(dialect!=SqlDialect.SQLITE)return false;
        try(var c=source.connection();var s=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){
            s.setString(1,EconomySchema.RESERVATIONS);try(var r=s.executeQuery()){if(!r.next())return false;}
            return find(c,kind,id).isPresent();
        }
    }
}
