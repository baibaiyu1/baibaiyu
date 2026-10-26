package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agui.event.AguiEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import static org.junit.jupiter.api.Assertions.*;

class AguiRunCompletionTest {
    @Test void finishingWaitsForSourceStateSavingAndLogCommitWhileEarlierEventsRemainLive() {
        var source = Sinks.many().unicast().<AguiEvent>onBackpressureBuffer();
        var committed = Sinks.<Void>empty();
        var persistenceStarted = new AtomicBoolean();
        var completed = new AtomicBoolean();
        List<AguiEvent> visible = new ArrayList<>();
        AguiMvcController.finishAfterPersistence(source.asFlux(), () -> {
            persistenceStarted.set(true); return committed.asMono();
        }).doOnComplete(() -> completed.set(true)).subscribe(visible::add);
        var start = new AguiEvent.RunStarted("thread", "run");
        var finish = new AguiEvent.RunFinished("thread", "run");
        var trailing = new AguiEvent.Custom("thread", "run", "MODEL_USAGE", Map.of("available", false));
        source.tryEmitNext(start); source.tryEmitNext(finish);
        assertEquals(List.of(start), visible);
        assertFalse(persistenceStarted.get());
        source.tryEmitNext(trailing); source.tryEmitComplete();
        assertEquals(List.of(start, trailing), visible);
        assertTrue(persistenceStarted.get()); assertFalse(completed.get());
        committed.tryEmitEmpty();
        assertEquals(List.of(start, trailing, finish), visible);
        assertTrue(completed.get());
    }

    @Test void failedLogCommitCannotEmitASuccessfulEndEvent() {
        List<AguiEvent> visible = new ArrayList<>();
        var failure = new IllegalStateException("commit failed");
        var thrown = assertThrows(IllegalStateException.class, () ->
                AguiMvcController.finishAfterPersistence(Flux.just(new AguiEvent.RunStarted("thread", "run"),
                                new AguiEvent.RunFinished("thread", "run")), () -> Mono.error(failure))
                        .doOnNext(visible::add).collectList().block(Duration.ofSeconds(5)));
        assertSame(failure, thrown);
        assertEquals(1, visible.size());
        assertInstanceOf(AguiEvent.RunStarted.class, visible.getFirst());
    }

    @Test void anAgentFailureAlsoWaitsForEarlierLogsBeforeTheControllerEmitsItsError() {
        var source = Sinks.many().unicast().<AguiEvent>onBackpressureBuffer();
        var committed = Sinks.<Void>empty();
        var persistenceStarted = new AtomicBoolean();
        var failed = new AtomicBoolean();
        AguiMvcController.finishAfterPersistence(source.asFlux(), () -> {
            persistenceStarted.set(true); return committed.asMono();
        }).doOnError(error -> failed.set(true)).subscribe(ignored -> {}, error -> {});
        source.tryEmitNext(new AguiEvent.RunStarted("thread", "run"));
        source.tryEmitError(new IllegalStateException("agent failed"));
        assertTrue(persistenceStarted.get()); assertFalse(failed.get());
        committed.tryEmitEmpty(); assertTrue(failed.get());
    }
}
