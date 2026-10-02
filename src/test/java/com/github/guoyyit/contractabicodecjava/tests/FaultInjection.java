package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.registry.JsonFileFunctionStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Test-only store that simulates storage I/O failures (disk full, quota exhausted,
 * read-only directory, failed replacement) at a chosen point of the write pipeline.
 *
 * <p>Injection points are ordered 1..N through {@link JsonFileFunctionStore}'s write
 * sequence; a negative point fires on every write at or after its absolute value, so a
 * single test can say "fail from the flush stage onward".
 */
final class FaultInjection extends JsonFileFunctionStore {

    /** Logical stages at which a failure can be injected. */
    enum Stage {
        WRITE,
        FORCE,
        REPLACE
    }

    private volatile Stage failAt;
    private volatile boolean armed = true;
    private final java.util.concurrent.atomic.AtomicInteger remainingFaults =
            new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
    private volatile java.util.function.Predicate<Thread> threadFilter = t -> true;

    FaultInjection(Path file) {
        super(file);
    }

    void failAt(Stage stage) {
        this.failAt = stage;
        this.armed = true;
    }

    void disarm() {
        this.armed = false;
    }

    /** Arms the fault so that it is raised at most {@code count} more times. */
    void failOnce(Stage stage) {
        this.failAt = stage;
        this.armed = true;
        this.remainingFaults.set(1);
    }

    /** Restricts fault injection to a single thread (so concurrent writers are isolated). */
    void failOnceForThread(Stage stage, Thread thread) {
        failOnce(stage);
        this.threadFilter = t -> t == thread;
    }

    private boolean shouldFail(Stage stage) {
        if (!armed || failAt == null || stage.ordinal() < failAt.ordinal()) {
            return false;
        }
        if (!threadFilter.test(Thread.currentThread())) {
            return false;
        }
        return remainingFaults.getAndDecrement() > 0;
    }

    @Override
    protected void writeBytes(java.nio.channels.FileChannel channel, java.nio.ByteBuffer buffer)
            throws IOException {
        if (shouldFail(Stage.WRITE)) {
            throw new IOException("simulated disk full during write");
        }
        super.writeBytes(channel, buffer);
    }

    @Override
    protected void forceFile(java.nio.channels.FileChannel channel) throws IOException {
        if (shouldFail(Stage.FORCE)) {
            throw new IOException("simulated I/O error during fsync");
        }
        super.forceFile(channel);
    }

    @Override
    protected void replaceFile(Path tmp, Path target) throws IOException {
        if (shouldFail(Stage.REPLACE)) {
            throw new IOException("simulated read-only directory during replace");
        }
        super.replaceFile(tmp, target);
    }
}
