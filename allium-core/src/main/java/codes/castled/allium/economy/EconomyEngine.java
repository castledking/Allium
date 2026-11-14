package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.security.MessageDigest;
import static codes.castled.allium.economy.EconomyResult.*;

/** One-process, one-commit SQLite engine. No Bukkit, network, refunds or hidden account creation. */
public final class EconomyEngine {
    @FunctionalInterface public interface PaymentPolicy { boolean permits(Connection transaction, UUID sender, UUID recipient) throws SQLException; }
    /** Fault boundary used by integration/live gates; real commit remains exactly once. */
    @FunctionalInterface public interface Committer { void commit(Connection connection, UUID operation) throws SQLException; }
    private final ConnectionSource connections;
    private final EconomyCoordinator coordinator=new EconomyCoordinator();
    private final PaymentPolicy policy;
    private final Committer committer;
    private volatile EconomySettings settings;
    public EconomyEngine(ConnectionSource connections, EconomySettings settings, PaymentPolicy policy) throws SQLException {
        this(connections,settings,policy,(c,id)->{try(var s=c.createStatement()){s.execute("COMMIT");}});
    }
    public EconomyEngine(ConnectionSource connections, EconomySettings settings, PaymentPolicy policy, Committer committer) throws SQLException {
        this.connections=Objects.requireNonNull(connections);this.settings=Objects.requireNonNull(settings);
        this.policy=Objects.requireNonNull(policy);this.committer=Objects.requireNonNull(committer);
        EconomySchema.migrate(connections);
    }
    public EconomySettings settings(){return settings;}
    public EconomyRequest request(UUID id,String origin,String key,EconomyRequest.Kind kind,String actor,UUID source,UUID recipient,Money amount){
        return new EconomyRequest(id,origin,key,kind,actor,source,recipient,amount,settings);
    }
    private Connection connection() throws SQLException {
        Connection c=connections.connection();
        try(var s=c.createStatement()){
            if(!c.getAutoCommit()) throw new SQLException("economy requires a clean autocommit connection");
            s.execute("PRAGMA busy_timeout=1000");s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            try(var r=s.executeQuery("PRAGMA synchronous")){if(!r.next()||r.getInt(1)!=2)throw new SQLException("SQLite FULL durability unavailable");}
            return c;
        }catch(SQLException error){c.close();throw error;}
    }
    public Balance balance(UUID id){
        try(var c=connection()){Found found=read(c,id);return found==null?new Missing():found;}
        catch(SQLException|RuntimeException error){return new Unavailable(error.getMessage());}
    }
    public EconomyCoordinator coordinator(){return coordinator;}
    public <T> T coordinated(java.util.concurrent.Callable<T> work)throws SQLException{return coordinator.call(work);}
    public Mutation execute(EconomyRequest request){
        try{return coordinator.call(()->transact(request));}
        catch(SQLException error){return new Unavailable(error.getMessage());}
    }
    /** Caller first closes admission and drains its async queue. Validation and install share writer ordering. */
    public void installSettings(EconomySettings next)throws SQLException {
        coordinator.call(()->{
            try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT balance_minor,reserved_minor FROM "+EconomySchema.ACCOUNTS)){
                while(r.next()){
                    long balance=r.getLong(1),held=r.getLong(2);
                    if(balance<next.minimum().minor()||balance>next.maximum().minor()||held>Math.max(0,Math.subtractExact(balance,next.minimum().minor())))
                        throw new SQLException("settings would strand an account or reservation");
                }
            }
            settings=next;return null;
        });
    }
    private Mutation transact(EconomyRequest q){
        boolean begun=false, committing=false;
        Connection c=null;
        try{
            c=connection();try(var s=c.createStatement()){s.execute("BEGIN IMMEDIATE");}begun=true;
            byte[] payload=q.payload(),hash=MessageDigest.getInstance("SHA-256").digest(payload);
            try { insertOperation(c,q,payload,hash); }
            catch(SQLException conflict) {
                String message=String.valueOf(conflict.getMessage());
                boolean operationKey=(conflict.getErrorCode()==19 || conflict.getErrorCode()==1555 || conflict.getErrorCode()==2067)
                        && (message.contains(EconomySchema.OPERATIONS+".operation_id") || message.contains(EconomySchema.OPERATIONS+".origin, "+EconomySchema.OPERATIONS+".request_key"));
                if(!operationKey)throw conflict;
                Mutation previous=replay(c,q,payload,hash);
                if(previous==null)throw conflict;
                rollback(c);begun=false;return previous;
            }
            SqlSavepoint savepoint=new SqlSavepoint(c);
            String rejection=null;Found a=null,b=null;
            if(!q.settings().equals(settings)) rejection="SETTINGS_CHANGED";
            else if(q.kind()==EconomyRequest.Kind.OPEN){
                a=read(c,q.source());
                if(a==null){
                    long now=System.currentTimeMillis();
                    try(var s=c.prepareStatement("INSERT INTO "+EconomySchema.ACCOUNTS+" VALUES(?,?,0,0,?,?)")){
                        s.setString(1,q.source().toString());s.setLong(2,q.amount().minor());s.setLong(3,now);s.setLong(4,now);requireOne(s.executeUpdate());
                    }
                    a=new Found(q.amount(),new Money(0),0);entry(c,q.id(),q.source(),0,0,a,q.amount().minor());
                }
            }else{
                // BEGIN IMMEDIATE owns the SQLite writer lock. Point reads still use UUID order.
                var ids=new ArrayList<UUID>();ids.add(q.source());if(q.recipient()!=null)ids.add(q.recipient());ids.sort(Comparator.comparing(UUID::toString));
                Map<UUID,Found> snapshots=new HashMap<>();for(UUID id:ids)snapshots.put(id,read(c,id));
                a=snapshots.get(q.source());b=q.recipient()==null?null:snapshots.get(q.recipient());
                if(a==null || q.recipient()!=null&&b==null)rejection="MISSING_ACCOUNT";
                else if(q.kind()==EconomyRequest.Kind.PAY && (q.amount().minor()<settings.minimumPay().minor() || !policy.permits(c,q.source(),q.recipient())))rejection="REFUSED";
                else{
                    long amount=q.amount().minor(),next=a.book().minor(),other=b==null?0:b.book().minor();
                    switch(q.kind()){
                        case PAY,WITHDRAW -> next=Math.subtractExact(next,amount);
                        case DEPOSIT -> next=Math.addExact(next,amount);
                        case ADMIN_SET -> next=amount;
                        default -> throw new IllegalStateException("unhandled mutation");
                    }
                    if(q.kind()==EconomyRequest.Kind.PAY)other=Math.addExact(other,amount);
                    long floor=(q.kind()==EconomyRequest.Kind.PAY||q.kind()==EconomyRequest.Kind.WITHDRAW)?Math.max(0,settings.minimum().minor()):settings.minimum().minor();
                    if(next<floor+a.reserved().minor())rejection="INSUFFICIENT_FUNDS";
                    else if(next>settings.maximum().minor() || b!=null&&other>settings.maximum().minor())rejection="CAP";
                    else{
                        Found old=a;a=update(c,q.source(),old,next);if(next!=old.book().minor())entry(c,q.id(),q.source(),old.book().minor(),old.reserved().minor(),a,Math.subtractExact(next,old.book().minor()));
                        if(b!=null){Found before=b;b=update(c,q.recipient(),before,other);entry(c,q.id(),q.recipient(),before.book().minor(),before.reserved().minor(),b,amount);}
                    }
                }
            }
            if(rejection!=null){savepoint.rollback();a=null;b=null;}
            savepoint.release();finish(c,q.id(),rejection,a,b);
            committing=true;committer.commit(c,q.id());begun=false;
            return rejection==null?new Committed(q.id(),a,b,false):new Rejected(q.id(),rejection);
        }catch(Exception error){
            if(begun && c!=null)rollback(c);
            return committing?new Unknown(q.id(),error.getMessage()):new Unavailable(error.getMessage());
        }finally{if(c!=null)try{c.close();}catch(SQLException ignored){/* commit outcome already fixed */}}
    }
    private static void rollback(Connection c){try(var s=c.createStatement()){s.execute("ROLLBACK");}catch(SQLException ignored){}}
    private static void requireOne(int count)throws SQLException{if(count!=1)throw new SQLException("economy conditional write affected "+count+" rows");}
    private static Found read(Connection c,UUID id)throws SQLException{
        try(var s=c.prepareStatement("SELECT balance_minor,reserved_minor,version FROM "+EconomySchema.ACCOUNTS+" WHERE account_uuid=?")){
            s.setString(1,id.toString());try(var r=s.executeQuery()){return r.next()?new Found(new Money(r.getLong(1)),new Money(r.getLong(2)),r.getLong(3)):null;}
        }
    }
    private static Found update(Connection c,UUID id,Found old,long next)throws SQLException{
        if(next==old.book().minor())return old;
        try(var s=c.prepareStatement("UPDATE "+EconomySchema.ACCOUNTS+" SET balance_minor=?,version=version+1,updated_at=? WHERE account_uuid=? AND version=? AND balance_minor=? AND reserved_minor=?")){
            s.setLong(1,next);s.setLong(2,System.currentTimeMillis());s.setString(3,id.toString());s.setLong(4,old.version());s.setLong(5,old.book().minor());s.setLong(6,old.reserved().minor());requireOne(s.executeUpdate());
        }return new Found(new Money(next),old.reserved(),old.version()+1);
    }
    private static void entry(Connection c,UUID op,UUID account,long before,long held,Found after,long delta)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO "+EconomySchema.ENTRIES+" VALUES(?,?,?,0,?,?,?,?,?)")){
            s.setString(1,op.toString());s.setString(2,account.toString());s.setLong(3,delta);s.setLong(4,before);s.setLong(5,after.book().minor());s.setLong(6,held);s.setLong(7,after.reserved().minor());s.setLong(8,after.version());requireOne(s.executeUpdate());
        }
    }
    private static void insertOperation(Connection c,EconomyRequest q,byte[] payload,byte[] hash)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO "+EconomySchema.OPERATIONS+" (operation_id,origin,request_key,kind,actor,source_uuid,recipient_uuid,amount_minor,payload,payload_hash,state,result_code,created_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?,'COMMITTED','PENDING',?,?)")){
            s.setString(1,q.id().toString());s.setString(2,q.origin());s.setString(3,q.key());s.setString(4,q.kind().name());s.setString(5,q.actor());s.setString(6,q.source().toString());s.setString(7,q.recipient()==null?null:q.recipient().toString());s.setLong(8,q.amount().minor());s.setBytes(9,payload);s.setBytes(10,hash);s.setLong(11,System.currentTimeMillis());s.setLong(12,System.currentTimeMillis());requireOne(s.executeUpdate());
        }
    }
    private static void finish(Connection c,UUID id,String rejection,Found a,Found b)throws SQLException{
        try(var s=c.prepareStatement("UPDATE "+EconomySchema.OPERATIONS+" SET state=?,result_code=?,source_balance=?,source_reserved=?,source_version=?,recipient_balance=?,recipient_reserved=?,recipient_version=?,finished_at=? WHERE operation_id=?")){
            s.setString(1,rejection==null?"COMMITTED":"REJECTED");s.setString(2,rejection==null?"OK":rejection);bindFound(s,3,a);bindFound(s,6,b);s.setLong(9,System.currentTimeMillis());s.setString(10,id.toString());requireOne(s.executeUpdate());
        }
    }
    private static void bindFound(PreparedStatement s,int offset,Found found)throws SQLException{
        if(found==null){for(int i=0;i<3;i++)s.setNull(offset+i,Types.BIGINT);}
        else{s.setLong(offset,found.book().minor());s.setLong(offset+1,found.reserved().minor());s.setLong(offset+2,found.version());}
    }
    private static Found resultFound(ResultSet r,int offset)throws SQLException{
        long value=r.getLong(offset);return r.wasNull()?null:new Found(new Money(value),new Money(r.getLong(offset+1)),r.getLong(offset+2));
    }
    private static Mutation replay(Connection c,EconomyRequest q,byte[] payload,byte[] hash)throws SQLException{
        try(var s=c.prepareStatement("SELECT operation_id,payload,payload_hash,state,result_code,source_balance,source_reserved,source_version,recipient_balance,recipient_reserved,recipient_version FROM "+EconomySchema.OPERATIONS+" WHERE operation_id=? OR (origin=? AND request_key=?)")){
            s.setString(1,q.id().toString());s.setString(2,q.origin());s.setString(3,q.key());try(var r=s.executeQuery()){
                if(!r.next())return null;
                UUID id=UUID.fromString(r.getString(1));
                if(!Arrays.equals(payload,r.getBytes(2))||!Arrays.equals(hash,r.getBytes(3)))return new Rejected(q.id(),"IDEMPOTENCY_CONFLICT");
                Mutation receipt=r.getString(4).equals("REJECTED")?new Rejected(id,r.getString(5)):new Committed(id,resultFound(r,6),resultFound(r,9),true);
                if(r.next())return new Rejected(q.id(),"IDEMPOTENCY_CONFLICT");
                return receipt;
            }
        }
    }
    // SQL savepoints stay inside the explicitly begun execution transaction.
    private record SqlSavepoint(Connection c){
        SqlSavepoint{try(var s=c.createStatement()){s.execute("SAVEPOINT effect");}catch(SQLException e){throw new IllegalStateException(e);}}
        void rollback()throws SQLException{try(var s=c.createStatement()){s.execute("ROLLBACK TO effect");}}
        void release()throws SQLException{try(var s=c.createStatement()){s.execute("RELEASE effect");}}
    }
}
