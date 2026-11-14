package com.lanhao.ops;

import com.lanhao.ops.persistence.RunStore;
import com.lanhao.ops.workflow.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunSchedulerTest {
    @Test void claimsOnlyAvailableSlotsAndDoesNotQueueLeasedTasks() throws Exception {
        RunStore store = mock(RunStore.class); RunEngine engine = mock(RunEngine.class);
        when(store.claim(1)).thenAnswer(c -> List.of(new RunStore.Claim(UUID.randomUUID(), "worker")));
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        doAnswer(c -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stopped) { /* 测试关闭 */ }
            return null;
        }).when(engine).step(any());
        OpsProperties base = TestFixtures.ops();
        var options = new OpsProperties(2, base.stepTimeout(), base.collectionTimeout(), false, Map.of(), base.notification());
        var metrics = new SimpleMeterRegistry();
        try {
            var scheduler = new RunScheduler(store, engine, TestFixtures.config(true), options, metrics);
            try {
                scheduler.tick();
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                scheduler.tick();
                verify(store, times(2)).claim(1);
                assertThat(metrics.get("agent.worker.active").gauge().value()).isEqualTo(2);
            } finally { release.countDown(); scheduler.close(); }
        } finally { metrics.close(); }
    }
    @Test void timedOutWorkerRetainsItsSlotUntilItReallyExits() throws Exception {
        RunStore store = mock(RunStore.class); RunEngine engine = mock(RunEngine.class);
        when(store.claim(1)).thenAnswer(c -> List.of(new RunStore.Claim(UUID.randomUUID(), "worker")));
        var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(c -> {
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) {
                interrupted.countDown();
                while (release.getCount() != 0) {
                    try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException stopped) { /* 等待真实退出 */ }
                }
            }
            return null;
        }).when(engine).step(any());
        var options = new OpsProperties(1, Duration.ofMillis(100), Duration.ofMillis(50), false, Map.of(), TestFixtures.ops().notification());
        var metrics = new SimpleMeterRegistry();
        try {
            var scheduler = new RunScheduler(store, engine, TestFixtures.config(true), options, metrics);
            try {
                scheduler.tick();
                assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
                scheduler.tick();
                verify(store, times(1)).claim(1);
            } finally { release.countDown(); scheduler.close(); }
        } finally { metrics.close(); }
    }
    @Test void failedHeartbeatInterruptsAndRevokesSubsequentOperations() throws Exception {
        RunStore store = mock(RunStore.class); RunEngine engine = mock(RunEngine.class);
        var claim = new RunStore.Claim(UUID.randomUUID(), "worker");
        when(store.claim(1)).thenReturn(List.of(claim), List.of());
        doThrow(new IllegalStateException("lost")).when(store).ensureLease(claim);
        var revoked = new CountDownLatch(1); var sideEffect = new AtomicBoolean();
        doAnswer(c -> {
            try { new CountDownLatch(1).await(4, TimeUnit.SECONDS); }
            catch (InterruptedException expected) {
                try { StepControl.check(); sideEffect.set(true); }
                catch (StepControl.Aborted stopped) { revoked.countDown(); }
            }
            return null;
        }).when(engine).step(any());
        var base = TestFixtures.config(true);
        var shortLease = new AgentProperties(base.mode(), false, true, 2, base.stallTimeout(), base.runTimeout(),
                base.retryBackoff(), Duration.ofSeconds(3), base.verificationTimeout(), 3, base.namespaces(),
                base.apiUsername(), base.apiPassword(), null, null, null);
        var metrics = new SimpleMeterRegistry();
        try {
            var scheduler = new RunScheduler(store, engine, shortLease, TestFixtures.ops(), metrics);
            try {
                scheduler.tick();
                assertThat(revoked.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(sideEffect).isFalse();
                verify(store).ensureLease(claim);
            } finally { scheduler.close(); }
        } finally { metrics.close(); }
    }
}
