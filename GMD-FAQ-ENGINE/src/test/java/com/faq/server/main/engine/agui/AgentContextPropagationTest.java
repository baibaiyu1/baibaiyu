package com.gmd.faq.server.main.engine.agui;

import com.gmd.faq.server.main.common.util.TenantUtils;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.junit.jupiter.api.Assertions.*;

class AgentContextPropagationTest {
    @BeforeEach void initialize() { AgentContextPropagation.initialize(); }
    @AfterEach void clean() { AgentContext.clean(); TenantUtils.clear(); }

    @Test void restoresCallerAfterAsyncCompletionAndFailure() {
        AgentContext caller = context(10);
        AgentContext.set(caller);
        TenantUtils.setCurrentTenant(10L, "tenant-10");
        AgentContext request = context(20);
        assertEquals(20L, Mono.fromCallable(() -> {
            assertSame(request, AgentContext.get());
            return TenantUtils.getCurrentTenantId();
        }).subscribeOn(Schedulers.boundedElastic())
                .contextWrite(ctx -> AgentContextPropagation.withContext(ctx, request)).block(Duration.ofSeconds(5)));
        assertThrows(IllegalStateException.class, () -> Mono.defer(() -> {
            assertEquals(20L, TenantUtils.getCurrentTenantId());
            return Mono.error(new IllegalStateException("expected"));
        }).subscribeOn(Schedulers.boundedElastic())
                .contextWrite(ctx -> AgentContextPropagation.withContext(ctx, request)).block(Duration.ofSeconds(5)));
        assertSame(caller, AgentContext.get());
        assertEquals(10L, TenantUtils.getCurrentTenantId());
    }

    @Test void parallelRequestsDoNotShareTenantOrLeakToWorker() {
        var scheduler = Schedulers.newParallel("context-isolation", 2);
        try {
            Flux.range(1, 80).flatMap(id -> {
                AgentContext request = context(id);
                return Mono.delay(Duration.ofMillis(id % 3), scheduler).map(ignored -> {
                    assertSame(request, AgentContext.get());
                    assertEquals((long) id, TenantUtils.getCurrentTenantId());
                    return id;
                }).contextWrite(ctx -> AgentContextPropagation.withContext(ctx, request));
            }, 8).collectList().block(Duration.ofSeconds(10));
            Mono.fromRunnable(() -> {
                assertTrue(AgentContext.getIfExists().isEmpty());
                assertNull(TenantUtils.getCurrentTenantId());
            }).subscribeOn(scheduler).block(Duration.ofSeconds(5));
        } finally { scheduler.dispose(); }
    }

    private static AgentContext context(long id) {
        AgentContext context = new AgentContext();
        context.setTenantId(id);
        context.setTenantCode("tenant-" + id);
        return context;
    }
}
