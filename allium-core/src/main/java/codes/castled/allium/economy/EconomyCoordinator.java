package codes.castled.allium.economy;

import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Phase 1 process ordering for money, acceptance/ignore writers and configuration installation. */
public final class EconomyCoordinator {
    private final ReentrantLock lock=new ReentrantLock(true);
    public <T> T call(Callable<T> work)throws SQLException {
        boolean acquired=false;
        try {
            acquired=lock.tryLock(1,TimeUnit.SECONDS);
            if(!acquired)throw new SQLException("economy coordinator busy");
            return work.call();
        } catch(InterruptedException error){Thread.currentThread().interrupt();throw new SQLException("interrupted before coordinated work",error);}
        catch(SQLException error){throw error;}
        catch(Exception error){throw new SQLException("coordinated work failed",error);}
        finally{if(acquired)lock.unlock();}
    }
}
