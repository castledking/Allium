import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** THROWAWAY JDBC prototype: one COMMITTED row and both money legs share one commit.
 * Not an economy engine or Vault provider. Uses only scratch databases selected by run.py.
 * A lost acknowledgement is injected at the JDBC commit-return boundary, explicitly recorded.
 */
public final class SingleCommitProbe {
    static Path output;
    static String database;
    static long ids;
    static int checks;
    static final List<Map<String,Object>> samples = new ArrayList<>(), faults = new ArrayList<>();
    static void require(boolean b, String why) { if (!b) throw new AssertionError(why); checks++; }
    static String json(Object o) {
        if(o==null)return "null";
        if(o instanceof Number||o instanceof Boolean)return o.toString();
        if(o instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(o instanceof Collection<?> c)return "["+String.join(",",c.stream().map(SingleCommitProbe::json).toList())+"]";
        return "\""+o.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static void exec(Connection c,String sql)throws SQLException {try(Statement s=c.createStatement()){s.execute(sql);}}
    static long scalar(Connection c,String sql)throws SQLException {try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){if(!r.next())throw new SQLException("missing scalar");return r.getLong(1);}}
    static int update(Connection c,String sql,Object... args)throws SQLException {
        try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p.executeUpdate();}
    }
    static boolean mysql(String lane){return lane.equals("mysql-durable");}
    static Connection connect(String lane,int timeout)throws SQLException {
        boolean mysql=mysql(lane);Properties p=new Properties();Connection c;
        if(mysql){
            p.setProperty("user","root");p.setProperty("password",Objects.requireNonNull(System.getenv("BENCH_MYSQL_PASSWORD")));
            p.setProperty("useSSL","false");p.setProperty("allowPublicKeyRetrieval","true");
            p.setProperty("connectTimeout","2000");p.setProperty("socketTimeout","5000");p.setProperty("autoReconnect","false");
            c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:25733/"+(database==null?"":database),p);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);exec(c,"SET SESSION innodb_lock_wait_timeout=3");
        }else{
            p.setProperty("transaction_mode","IMMEDIATE");
            c=DriverManager.getConnection("jdbc:sqlite:"+output.resolve(lane+".sqlite"),p);
            exec(c,"PRAGMA busy_timeout="+timeout);exec(c,"PRAGMA foreign_keys=ON");
            exec(c,"PRAGMA synchronous="+(lane.endsWith("normal")?"NORMAL":"FULL"));
        }
        return c;
    }
    static boolean duplicate(String lane,SQLException e){
        return mysql(lane)?e.getErrorCode()==1062&&"23000".equals(e.getSQLState())
                :(e.getErrorCode()&255)==19&&e.getMessage().contains("UNIQUE constraint failed: operations.id");
    }
    static boolean busy(SQLException e){return (e.getErrorCode()&255)==5||(e.getErrorCode()&255)==6;}
    static void setup(String lane)throws Exception {
        try(Connection c=connect(lane,2000)){
            if(!mysql(lane))exec(c,"PRAGMA journal_mode=WAL");
            String engine=mysql(lane)?" ENGINE=InnoDB":"";
            exec(c,"CREATE TABLE accounts(id INT PRIMARY KEY,balance BIGINT NOT NULL)"+engine);
            exec(c,"CREATE TABLE operations(id BIGINT PRIMARY KEY,payload VARCHAR(64) NOT NULL,state VARCHAR(16) NOT NULL)"+engine);
            exec(c,"CREATE TABLE entries(op BIGINT NOT NULL,account INT NOT NULL,delta BIGINT NOT NULL,PRIMARY KEY(op,account))"+engine);
            exec(c,"INSERT INTO accounts VALUES(1,1000000),(2,1000000)");
            var meta=new LinkedHashMap<String,Object>();meta.put("lane",lane);meta.put("driver",c.getMetaData().getDriverVersion());meta.put("server",c.getMetaData().getDatabaseProductVersion());
            if(mysql(lane)){
                long flush=scalar(c,"SELECT @@innodb_flush_log_at_trx_commit"),bin=scalar(c,"SELECT @@sync_binlog"),enabled=scalar(c,"SELECT @@log_bin");
                require(flush==1&&(enabled==0||bin==1),"durable MySQL configuration");meta.put("innodb_flush_log_at_trx_commit",flush);meta.put("sync_binlog",bin);meta.put("log_bin",enabled);
            }else {meta.put("synchronous",scalar(c,"PRAGMA synchronous"));meta.put("journal_mode","WAL");}
            Files.writeString(output.resolve(lane+"-environment.json"),json(meta)+"\n");
        }
    }
    static void insert(Connection c,long id)throws SQLException {update(c,"INSERT INTO operations VALUES(?,?,'COMMITTED')",id,"1->2:1");}
    static void money(Connection c,String lane,long id)throws SQLException {
        if(mysql(lane))for(int account=1;account<=2;account++)scalar(c,"SELECT balance FROM accounts WHERE id="+account+" FOR UPDATE");
        require(update(c,"UPDATE accounts SET balance=balance-1 WHERE id=1 AND balance>=1")==1,"conditional debit");
        require(update(c,"UPDATE accounts SET balance=balance+1 WHERE id=2 AND balance<=1999999")==1,"bounded credit");
        update(c,"INSERT INTO entries VALUES(?,1,-1),(?,2,1)",id,id);
    }
    static void verify(Connection c,long id)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT payload,state FROM operations WHERE id=?")){
            p.setLong(1,id);try(ResultSet r=p.executeQuery()){require(r.next()&&r.getString(1).equals("1->2:1")&&r.getString(2).equals("COMMITTED"),"exact committed payload");}
        }
        require(scalar(c,"SELECT COUNT(*) FROM entries WHERE op="+id)==2,"exactly two entries");
        require(scalar(c,"SELECT SUM(delta) FROM entries WHERE op="+id)==0,"conserved entries");
    }
    static void transfer(String lane,Connection c,Connection observer,String phase)throws Exception {
        long id=++ids,start=System.nanoTime();c.setAutoCommit(false);insert(c,id);money(c,lane,id);
        long commit=System.nanoTime();c.commit();long ack=System.nanoTime();c.setAutoCommit(true);long end=System.nanoTime();verify(observer,id);
        samples.add(Map.of("lane",lane,"phase",phase,"operation",id,"elapsed_ns",end-start,"commit_ns",ack-commit));
    }
    static void kill(String lane,Connection old,long connectionId)throws Exception {
        if(mysql(lane))try(Connection admin=connect(lane,2000)){exec(admin,"KILL CONNECTION "+connectionId);}
        else old.close(); // Embedded SQLite has no server session/socket to kill.
    }
    static String recover(String lane,long id,CountDownLatch entered)throws Exception {
        try(Connection c=connect(lane,2000)){
            entered.countDown();
            try {
                c.setAutoCommit(false);insert(c,id);money(c,lane,id);c.commit();return "REINSERT_APPLIED_ONCE";
            }catch(SQLException e){
                try{c.rollback();}catch(SQLException ignored){}
                if(!duplicate(lane,e))throw e;
                c.setAutoCommit(true);verify(c,id);return "DUPLICATE_COMMITTED";
            }
        }
    }
    static void fault(String lane,boolean after)throws Exception {
        long id=++ids;Connection old=connect(lane,2000);long cid=mysql(lane)?scalar(old,"SELECT CONNECTION_ID()"):0;
        old.setAutoCommit(false);insert(old,id);money(old,lane,id);
        boolean busyProven=false;
        if(!mysql(lane))try(Connection probe=connect(lane,100)){
            try{probe.setAutoCommit(false);insert(probe,id);throw new AssertionError("old SQLite writer was not locked");}
            catch(SQLException e){require(busy(e)&&!duplicate(lane,e),"SQLite BUSY remains UNKNOWN, never committed proof");busyProven=true;}
        }
        ExecutorService executor=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1);
        long started=System.nanoTime();Future<String> recovery=executor.submit(()->recover(lane,id,entered));
        try {
            require(entered.await(2,TimeUnit.SECONDS),"recovery started");
            if(mysql(lane))try(Connection observer=connect(lane,2000)){
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;
                while(System.nanoTime()<end){if(scalar(observer,"SELECT COUNT(*) FROM performance_schema.data_lock_waits")>0){waiting=true;break;}Thread.sleep(5);}
                require(waiting,"server reports real unique-key lock wait");
            }
            Thread.sleep(150);require(!recovery.isDone(),"same-ID recovery blocked behind old transaction");
            // Deterministic commit-return fault seam: the caller receives an exception, never
            // success. In AFTER mode the inner JDBC commit succeeded before the connection dies.
            boolean innerCommit=false;String callerOutcome;
            try {
                if(after){old.commit();innerCommit=true;kill(lane,old,cid);throw new SQLNonTransientConnectionException("injected discarded commit return");}
                kill(lane,old,cid);old.commit();throw new AssertionError("killed connection committed");
            }catch(SQLException expected){callerOutcome="OUTCOME_UNKNOWN";}
            String result=recovery.get(4,TimeUnit.SECONDS);
            require(result.equals(after?"DUPLICATE_COMMITTED":"REINSERT_APPLIED_ONCE"),"recovered correct outcome");
            try(Connection observer=connect(lane,2000)){verify(observer,id);require(scalar(observer,"SELECT SUM(balance) FROM accounts")==2000000,"fault conserves balances");}
            faults.add(Map.of("lane",lane,"kill",after?"after_commit":"before_commit","caller",callerOutcome,"inner_commit_ack_discarded",innerCommit,"recovery",result,"sqlite_busy_proven",busyProven,"blocked_then_resolved_ms",(System.nanoTime()-started)/1e6));
        }finally{try{old.close();}catch(SQLException ignored){}executor.shutdownNow();require(executor.awaitTermination(3,TimeUnit.SECONDS),"recovery worker stopped");}
    }
    static void lane(String lane)throws Exception {
        setup(lane);try(Connection c=connect(lane,2000);Connection observer=connect(lane,2000)){
            for(int i=0;i<120;i++)transfer(lane,c,observer,i<20?"warmup":"single");
            for(int tick=0;tick<20;tick++){long start=System.nanoTime();for(int i=0;i<3;i++)transfer(lane,c,observer,"burst3");long remain=50_000_000L-(System.nanoTime()-start);if(remain>0)TimeUnit.NANOSECONDS.sleep(remain);}
        }
        fault(lane,false);fault(lane,true);
        try(Connection c=connect(lane,2000)){
            require(scalar(c,"SELECT COUNT(*) FROM operations")==182,"all committed rows including recovered faults");
            require(scalar(c,"SELECT COUNT(*) FROM entries")==364,"no duplicate effects");
            require(scalar(c,"SELECT balance FROM accounts WHERE id=1")==999818,"exact debit total");
            require(scalar(c,"SELECT balance FROM accounts WHERE id=2")==1000182,"exact credit total");
        }
    }
    public static void main(String[] args)throws Exception {
        output=Path.of(args[0]);Files.createDirectories(output);boolean ok=false;
        try {
            lane("sqlite-full");lane("sqlite-normal");
            try(Connection admin=connect("mysql-durable",2000)){
                database="s14_single_"+UUID.randomUUID().toString().replace("-","");exec(admin,"CREATE DATABASE `"+database+"`");
            }
            lane("mysql-durable");ok=true;
        }finally{
            if(database!=null)try(Connection admin=connect("mysql-durable",2000)){exec(admin,"DROP DATABASE `"+database+"`");}
            Files.writeString(output.resolve("results.json"),json(Map.of("passed",ok,"checks",checks,"samples",samples,"faults",faults,"fault_boundary","JDBC commit-return seam; SQLite native close; MySQL administrative KILL CONNECTION; no real packet-loss or power-failure injection"))+"\n");
        }
        System.out.println("PASS single-commit probe: "+checks+" invariants, "+samples.size()+" timed samples, "+faults.size()+" connection-kill cases");
    }
}
