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
    @FunctionalInterface public interface WorkflowTransition { boolean apply(Connection transaction)throws SQLException; }
    public EconomyReservations reservations(){return reservations(this::execute);}
    public EconomyReservations reservations(EconomyReservations.Executor executor){return new EconomyReservations(this,connections,executor);}
    private final ConnectionSource connections;
    private final EconomyCoordinator coordinator=new EconomyCoordinator();
    private final PaymentPolicy policy;
    private final Committer committer;
    private final java.util.function.Consumer<Mutation> validator;
    private volatile java.util.function.Consumer<EconomyTiming.Sample> timing;
    /** Null disables diagnostics (the default); observers must be nonblocking. */
    public void observeTiming(java.util.function.Consumer<EconomyTiming.Sample> observer){timing=observer;}
    private volatile boolean ready;
    public boolean ready(){return ready;}
    private volatile EconomySettings settings;
    public EconomyEngine(ConnectionSource connections, EconomySettings settings, PaymentPolicy policy) throws SQLException {
        this(connections,settings,policy,(c,id)->{try(var s=c.createStatement()){s.execute("COMMIT");}});
    }
    public EconomyEngine(ConnectionSource connections, EconomySettings settings, PaymentPolicy policy, Committer committer) throws SQLException {
        this(connections,settings,policy,committer,ignored->{});
    }
    public EconomyEngine(ConnectionSource connections,EconomySettings settings,PaymentPolicy policy,Committer committer,java.util.function.Consumer<Mutation> validator)throws SQLException {
        this.connections=Objects.requireNonNull(connections);this.settings=Objects.requireNonNull(settings);
        this.policy=Objects.requireNonNull(policy);this.committer=Objects.requireNonNull(committer);
        this.validator=Objects.requireNonNull(validator);
        EconomySchema.migrate(connections);
        installSettings(settings);
        try(var c=connection();var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT COUNT(*) FROM "+EconomySchema.OPERATIONS+" WHERE (state='COMMITTED' AND (result_code<>'OK' OR source_balance IS NULL OR (recipient_uuid IS NOT NULL AND recipient_balance IS NULL))) OR (state='REJECTED' AND result_code IN ('OK','PENDING'))")){
                if(!r.next()||r.getLong(1)!=0)throw new SQLException("economy recovery found incomplete journal receipts");
            }
            for(String table:List.of(EconomySchema.ACCOUNTS,EconomySchema.OPERATIONS,EconomySchema.ENTRIES,EconomySchema.RESERVATIONS))
                try(var r=s.executeQuery("PRAGMA foreign_key_check("+table+")")){if(r.next())throw new SQLException("economy recovery found foreign-key violation");}
        }
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT a.account_uuid FROM "+EconomySchema.ACCOUNTS+" a WHERE a.reserved_minor<>(SELECT COALESCE(SUM(amount_minor),0) FROM "+EconomySchema.RESERVATIONS+" r WHERE r.account_uuid=a.account_uuid AND r.state='HELD') LIMIT 1")){
            if(r.next())throw new SQLException("economy held-reservation invariant failed");
        }
        ready=true;
    }
    public EconomySettings settings(){return settings;}
    public EconomyRequest request(UUID id,String origin,String key,EconomyRequest.Kind kind,String actor,UUID source,UUID recipient,Money amount){
        return new EconomyRequest(id,origin,key,kind,actor,source,recipient,amount,settings);
    }
    private Connection connection() throws SQLException {
        return connection(connections,false);
    }
    private Connection connection(ConnectionSource source,boolean readOnly)throws SQLException {
        var timing=EconomyTiming.CURRENT.get();long start=timing==null?0:System.nanoTime();Connection acquired;
        try{acquired=source.connection();}finally{if(timing!=null)timing.connection+=System.nanoTime()-start;}
        Connection c=EconomyTiming.wrap(acquired);
        try(var s=c.createStatement()){
            if(!c.getAutoCommit()) throw new SQLException("economy requires a clean autocommit connection");
            s.execute("PRAGMA busy_timeout=1000");s.execute("PRAGMA foreign_keys=ON");if(!readOnly)s.execute("PRAGMA journal_mode=WAL");
            else try(var r=s.executeQuery("PRAGMA journal_mode")){if(!r.next()||!r.getString(1).equalsIgnoreCase("wal"))throw new SQLException("read-only experiment requires WAL");}s.execute("PRAGMA synchronous=FULL");
            try(var r=s.executeQuery("PRAGMA synchronous")){if(!r.next()||r.getInt(1)!=2)throw new SQLException("SQLite FULL durability unavailable");}
            return c;
        }catch(SQLException error){c.close();throw error;}
    }
    public Balance balance(UUID id){return balance(id,null);}
    /** Experimental caller-owned read-only source to the same committed WAL database; no cache/transaction retained. */
    public Balance balance(UUID id,ConnectionSource readOnlySource){
        try(var timing=EconomyTiming.begin("READ",this.timing)){
            try(var c=readOnlySource==null?connection():connection(readOnlySource,true)){Found found=read(c,id);return found==null?new Missing():found;}
            catch(SQLException|RuntimeException error){return new Unavailable(error.getMessage());}
        }
    }
    public EconomyCoordinator coordinator(){return coordinator;}
    public <T> T coordinated(java.util.concurrent.Callable<T> work)throws SQLException{return coordinator.call(work);}
    public Mutation execute(EconomyRequest request){
        if(request.reservation())return new Rejected(request.id(),"WORKFLOW_REQUIRED");
        return execute(request,null);
    }
    /** Transition runs inside the effect savepoint/commit; false rolls back both money and workflow. */
    public Mutation execute(EconomyRequest request,WorkflowTransition transition){
        if(request.reservation()&&transition==null)return new Rejected(request.id(),"WORKFLOW_REQUIRED");
        try(var timing=EconomyTiming.begin(request.kind().name(),this.timing)){
            long waiting=timing==null?0:System.nanoTime();
            try{return coordinator.call(()->{if(timing!=null)timing.coordinator=System.nanoTime()-waiting;return transact(request,transition);});}
            catch(SQLException error){if(timing!=null)timing.coordinator=System.nanoTime()-waiting;return new Unavailable(error.getMessage());}
        }
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
    private Mutation transact(EconomyRequest q,WorkflowTransition transition){
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
            if(!q.settings().equals(settings)&&q.kind()!=EconomyRequest.Kind.CAPTURE&&q.kind()!=EconomyRequest.Kind.RELEASE) rejection="SETTINGS_CHANGED";
            else if(q.kind()==EconomyRequest.Kind.OPEN){
                a=read(c,q.source());
                if(a==null){
                    long now=System.currentTimeMillis();
                    try(var s=c.prepareStatement("INSERT INTO "+EconomySchema.ACCOUNTS+" VALUES(?,?,0,0,?,?)")){
                        s.setString(1,q.source().toString());s.setLong(2,q.amount().minor());s.setLong(3,now);s.setLong(4,now);requireOne(s.executeUpdate());
                    }
                    a=new Found(q.amount(),new Money(0),0);entry(c,q.id(),q.source(),0,0,a,q.amount().minor());
                }
            }else if(q.reservation()){
                a=read(c,q.source());
                if(a==null)rejection="MISSING_ACCOUNT";
                else{String refusal=reservation(c,q,a);if(refusal!=null)rejection=refusal;else a=read(c,q.source());}
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
            if(rejection==null&&transition!=null&&!transition.apply(c)){
                if(q.kind()==EconomyRequest.Kind.CAPTURE||q.kind()==EconomyRequest.Kind.RELEASE)throw new SQLException("terminal workflow evidence is not available");
                rejection="WORKFLOW_REFUSED";
            }
            if(rejection==null)verifyHeld(c,q.source());
            if(rejection!=null){savepoint.rollback();a=read(c,q.source());b=q.recipient()==null?null:read(c,q.recipient());}
            savepoint.release();finish(c,q.id(),rejection,a,b);
            Mutation result=rejection==null?new Committed(q.id(),a,b,false):new Rejected(q.id(),rejection,a,b);
            validator.accept(result);
            committing=true;
            var timing=EconomyTiming.CURRENT.get();long start=timing==null?0:System.nanoTime();
            if(timing!=null)timing.committing=true;
            try{committer.commit(c,q.id());}finally{if(timing!=null){timing.commit+=System.nanoTime()-start;timing.committing=false;}}
            begun=false;
            return result;
        }catch(Exception error){
            if(begun && c!=null)rollback(c);
            return committing?new Unknown(q.id(),error.getMessage()):new Unavailable(error.getMessage());
        }finally{if(c!=null)try{c.close();}catch(SQLException ignored){/* commit outcome already fixed */}}
    }
    private String reservation(Connection c,EconomyRequest q,Found before)throws SQLException {
        var identity=EconomyReservations.identity(q);
        verifyHeld(c,q.source());
        var held=EconomyReservations.find(c,identity.kind(),identity.id());
        long book=before.book().minor(),reserved=before.reserved().minor(),cost=q.amount().minor();
        boolean initial=q.kind()==EconomyRequest.Kind.RESERVE||q.kind()==EconomyRequest.Kind.SPEND;
        if(initial){
            if(held.isPresent())return "WORKFLOW_EXISTS";
            if(Math.subtractExact(Math.subtractExact(book,reserved),cost)<Math.max(0,settings.minimum().minor()))return "INSUFFICIENT_FUNDS";
            if(q.kind()==EconomyRequest.Kind.RESERVE)reserved=Math.addExact(reserved,cost);else book=Math.subtractExact(book,cost);
        }else{
            if(held.isEmpty())return "MISSING_RESERVATION";
            var row=held.get();if(!row.account().equals(q.source())||row.amount().minor()!=cost)return "WORKFLOW_CONFLICT";
            if(!row.state().equals("HELD"))return "WORKFLOW_TERMINAL";
            reserved=Math.subtractExact(reserved,cost);if(q.kind()==EconomyRequest.Kind.CAPTURE)book=Math.subtractExact(book,cost);
        }
        String state=q.kind()==EconomyRequest.Kind.RESERVE?"HELD":q.kind()==EconomyRequest.Kind.RELEASE?"RELEASED":"CAPTURED";
        long now=System.currentTimeMillis();
        if(initial){
            try(var s=c.prepareStatement("INSERT INTO "+EconomySchema.RESERVATIONS+" VALUES(?,?,?,?,?,?,0,?,?,?,?)")){
                s.setString(1,identity.kind().name());s.setString(2,identity.id().toString());s.setString(3,q.source().toString());s.setLong(4,cost);s.setString(5,q.actor().substring("WORKFLOW:".length()));s.setString(6,state);s.setString(7,q.id().toString());
                s.setString(8,state.equals("HELD")?null:q.id().toString());s.setLong(9,now);if(state.equals("HELD"))s.setNull(10,Types.BIGINT);else s.setLong(10,now);requireOne(s.executeUpdate());
            }
        }else{
            try(var s=c.prepareStatement("UPDATE "+EconomySchema.RESERVATIONS+" SET state=?,version=version+1,final_operation_id=?,finished_at=? WHERE workflow_kind=? AND workflow_id=? AND state='HELD' AND version=?")){
                s.setString(1,state);s.setString(2,q.id().toString());s.setLong(3,now);s.setString(4,identity.kind().name());s.setString(5,identity.id().toString());s.setLong(6,held.orElseThrow().version());requireOne(s.executeUpdate());
            }
        }
        try(var s=c.prepareStatement("UPDATE "+EconomySchema.ACCOUNTS+" SET balance_minor=?,reserved_minor=?,version=version+1,updated_at=? WHERE account_uuid=? AND balance_minor=? AND reserved_minor=? AND version=?")){
            s.setLong(1,book);s.setLong(2,reserved);s.setLong(3,now);s.setString(4,q.source().toString());s.setLong(5,before.book().minor());s.setLong(6,before.reserved().minor());s.setLong(7,before.version());requireOne(s.executeUpdate());
        }
        entry(c,q.id(),q.source(),before.book().minor(),before.reserved().minor(),new Found(new Money(book),new Money(reserved),Math.addExact(before.version(),1)),Math.subtractExact(book,before.book().minor()));
        return null;
    }
    private static void verifyHeld(Connection c,UUID account)throws SQLException{
        try(var s=c.prepareStatement("SELECT reserved_minor,(SELECT COALESCE(SUM(amount_minor),0) FROM "+EconomySchema.RESERVATIONS+" WHERE account_uuid=? AND state='HELD') FROM "+EconomySchema.ACCOUNTS+" WHERE account_uuid=?")){
            s.setString(1,account.toString());s.setString(2,account.toString());try(var r=s.executeQuery()){if(r.next()&&r.getLong(1)!=r.getLong(2))throw new SQLException("economy held-reservation invariant failed");}
        }
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
        try(var s=c.prepareStatement("INSERT INTO "+EconomySchema.ENTRIES+" VALUES(?,?,?,?,?,?,?,?,?)")){
            s.setString(1,op.toString());s.setString(2,account.toString());s.setLong(3,delta);s.setLong(4,Math.subtractExact(after.reserved().minor(),held));s.setLong(5,before);s.setLong(6,after.book().minor());s.setLong(7,held);s.setLong(8,after.reserved().minor());s.setLong(9,after.version());requireOne(s.executeUpdate());
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
                Mutation receipt=r.getString(4).equals("REJECTED")?new Rejected(id,r.getString(5),resultFound(r,6),resultFound(r,9)):new Committed(id,resultFound(r,6),resultFound(r,9),true);
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
