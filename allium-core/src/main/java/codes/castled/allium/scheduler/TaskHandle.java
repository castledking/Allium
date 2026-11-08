package codes.castled.allium.scheduler;

/** A scheduled task that can be cancelled. Cancelling a task that already ran, or twice, does nothing. */
public interface TaskHandle {

    /** A handle for work that was never scheduled (the scheduler is closed). */
    TaskHandle NONE = new TaskHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
