package com.lanhao.ops.workflow;

import java.time.Duration;

/** 工作槽位一直保留到线程真实退出，超时不释放仍在运行的线程。 */
public final class StepControl implements AutoCloseable {
    public static final class Aborted extends RuntimeException {
        public Aborted() { super("单步超时或执行租约失效，停止后续操作"); }
    }
    private static final ThreadLocal<StepControl> CURRENT = new ThreadLocal<>();
    private final long deadline;
    private Thread thread;
    private String failure;
    private boolean closed;
    public StepControl(Duration timeout) { deadline = System.nanoTime() + timeout.toNanos(); }
    public synchronized void bind() { thread = Thread.currentThread(); CURRENT.set(this); check(); }
    public synchronized void abort(String reason) {
        if (closed) return;
        failure = reason;
        if (thread != null) thread.interrupt();
    }
    public static void check() {
        StepControl control = CURRENT.get();
        if (control != null) control.assertActive();
    }
    private synchronized void assertActive() {
        if (failure != null || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
            throw new Aborted();
    }
    public synchronized void close() {
        closed = true;
        CURRENT.remove();
        Thread.interrupted();
    }
}
