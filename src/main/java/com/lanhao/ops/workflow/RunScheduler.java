package com.lanhao.ops.workflow;

import com.lanhao.ops.persistence.RunStore;
import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.OpsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

@Component
public class RunScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(RunScheduler.class);
    private final RunStore store;
    private final RunEngine engine;
    private final AgentProperties config;
    private final OpsProperties ops;
    private final ExecutorService workers;
    private final ScheduledExecutorService renewals;
    private final ScheduledExecutorService deadlines;
    private final Semaphore slots;
    private final MeterRegistry metrics;
    public RunScheduler(RunStore store, RunEngine engine, AgentProperties config, OpsProperties ops, MeterRegistry metrics) {
        this.store = store; this.engine = engine; this.config = config; this.ops = ops; this.metrics = metrics;
        slots = new Semaphore(ops.workers());
        workers = Executors.newFixedThreadPool(ops.workers(), Thread.ofPlatform().name("agent-worker-", 0).factory());
        renewals = Executors.newScheduledThreadPool(ops.workers(), Thread.ofPlatform().name("agent-lease-", 0).factory());
        deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("agent-deadline-", 0).factory());
        metrics.gauge("agent.worker.active", slots, value -> ops.workers() - value.availablePermits());
    }
    @Scheduled(fixedDelayString = "${agent.poll-interval:5000}")
    public void tick() {
        try {
            // 先预留真实工作槽位，再逐个认领，不预取等待中的任务。
            while (slots.tryAcquire()) {
                boolean submitted = false;
                try {
                    var claims = store.claim(1);
                    if (claims.isEmpty()) break;
                    RunStore.Claim claim = claims.getFirst();
                    workers.execute(() -> execute(claim));
                    submitted = true;
                } finally { if (!submitted) slots.release(); }
            }
        } catch (RuntimeException e) { LOG.warn("任务队列暂不可用，下次调度重试"); }
    }
    private void execute(RunStore.Claim claim) {
        StepControl control = new StepControl(ops.stepTimeout());
        ScheduledFuture<?> heartbeat = null;
        ScheduledFuture<?> timeout = null;
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            control.bind();
            long period = Math.max(1000, config.leaseDuration().toMillis() / 3);
            heartbeat = renewals.scheduleAtFixedRate(() -> {
                try { store.ensureLease(claim); }
                catch (RuntimeException e) { control.abort("租约续期失败"); }
            }, period, period, TimeUnit.MILLISECONDS);
            timeout = deadlines.schedule(() -> control.abort("单步总时限耗尽"), ops.stepTimeout().toMillis(), TimeUnit.MILLISECONDS);
            engine.step(claim);
        } catch (RuntimeException e) {
            metrics.counter("agent.step.failure").increment();
            LOG.warn("任务 {} 暂未落盘，将由租约回收机制接管", claim.id());
        } finally {
            control.close();
            if (heartbeat != null) heartbeat.cancel(false);
            if (timeout != null) timeout.cancel(false);
            sample.stop(metrics.timer("agent.step.duration"));
            slots.release();
        }
    }
    @PreDestroy public void close() {
        workers.shutdownNow();
        renewals.shutdownNow();
        deadlines.shutdownNow();
    }
}
