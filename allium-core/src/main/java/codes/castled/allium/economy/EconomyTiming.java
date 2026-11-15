package codes.castled.allium.economy;

import java.lang.reflect.*;
import java.sql.*;
import java.util.function.Consumer;

/** Opt-in JDBC phase timings. No SQL text, identities, credentials or monetary payloads. */
public final class EconomyTiming {
    private EconomyTiming(){}
    public record Sample(String kind,long threadId,long startEpochMillis,long startNano,long totalNanos,long connectionNanos,
                         long statementNanos,long commitNanos,long coordinatorNanos,long otherNanos){}
    static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    static Scope begin(String kind,Consumer<Sample> observer){return observer==null?null:new Scope(kind,observer);}
    static final class Scope implements AutoCloseable {
        final String kind;final Consumer<Sample> observer;final Scope previous;
        final long epoch=System.currentTimeMillis(),start=System.nanoTime();
        long connection,statement,commit,coordinator;boolean committing;
        Scope(String kind,Consumer<Sample> observer){this.kind=kind;this.observer=observer;previous=CURRENT.get();CURRENT.set(this);}
        @Override public void close(){
            long total=System.nanoTime()-start;CURRENT.set(previous);
            try{observer.accept(new Sample(kind,Thread.currentThread().getId(),epoch,start,total,connection,statement,commit,coordinator,
                    Math.max(0,total-connection-statement-commit-coordinator)));}catch(RuntimeException ignored){/* diagnostics cannot alter money outcomes */}
        }
    }
    static Connection wrap(Connection connection){
        if(CURRENT.get()==null)return connection;
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            Object result=invoke(connection,m,a);
            if(result instanceof CallableStatement statement)return statement(statement,CallableStatement.class);
            if(result instanceof PreparedStatement statement)return statement(statement,PreparedStatement.class);
            if(result instanceof Statement statement)return statement(statement,Statement.class);
            return result;
        });
    }
    private static Object statement(Statement statement,Class<?> type){
        return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{type},(p,m,a)->{
            Scope scope=CURRENT.get();boolean measured=scope!=null&&!scope.committing&&m.getName().startsWith("execute");
            long start=measured?System.nanoTime():0;
            try{return invoke(statement,m,a);}finally{if(measured)scope.statement+=System.nanoTime()-start;}
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
}
