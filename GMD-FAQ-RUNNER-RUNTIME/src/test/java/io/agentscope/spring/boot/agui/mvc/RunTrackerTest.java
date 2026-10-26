package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunTrackerTest {

    private static final Pattern EVENT_ID = Pattern.compile("id:([^\\s]+)");
    private final List<RunTracker> trackers = new ArrayList<>();

    @AfterEach
    void closeTrackers() {
        trackers.forEach(RunTracker::close);
    }

    @Test
    void stopKeepsSlotOccupiedUntilCompletionAndNextRunHasIndependentIdentity() {
        RunTracker tracker = tracker();
        var context = new com.gmd.faq.server.main.engine.agui.AgentContext();
        context.setRunId("run-first");
        RuntimeContext runtime = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        runtime.put(com.gmd.faq.server.main.engine.agui.AgentContext.class, context);
        tracker.registerRun("s1", mock(Agent.class), null, runtime);
        assertEquals("run-first", tracker.snapshot("s1").runId());
        org.junit.jupiter.api.Assertions.assertNotNull(tracker.snapshot("s1").startedAt());
        tracker.stopRun("s1");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> tracker.registerRun("s1", mock(Agent.class), null));
        tracker.markCompleted("s1");
        context.setRunId("run-second");
        tracker.registerRun("s1", mock(Agent.class), null, runtime);
        assertEquals("run-second", tracker.snapshot("s1").runId());
        assertEquals(RunTracker.RunState.RUNNING, tracker.snapshot("s1").state());
        assertEquals(RunTracker.RunState.COMPLETED, tracker.snapshot("missing").state());
    }

    @Test
    void stopTargetsExplicitSessionWithoutInterruptingOtherSlots() {
        RunTracker tracker = tracker();
        ReActAgent agent = mock(ReActAgent.class);
        RuntimeContext first = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        RuntimeContext second = RuntimeContext.builder().userId("u2").sessionId("s2").build();
        tracker.registerRun("s1", agent, null, first);
        tracker.registerRun("s2", agent, null, second);
        tracker.stopRun("s1");
        org.mockito.Mockito.verify(agent).interrupt(first);
        org.mockito.Mockito.verify(agent, org.mockito.Mockito.never()).interrupt(second);
        org.mockito.Mockito.verify(agent, org.mockito.Mockito.never()).interrupt();
        assertEquals(RunTracker.RunState.STOPPING, tracker.getRunState("s1"));
        assertEquals(RunTracker.RunState.RUNNING, tracker.getRunState("s2"));
    }

    @Test
    void reconnectDeliversOnlyUnseenEventsInOrderWhileLiveEventArrives() throws Exception {
        RunTracker tracker = tracker();
        tracker.registerRun("thread", mock(Agent.class), null);
        CapturingEmitter initial = new CapturingEmitter();
        tracker.reconnect("thread", initial);
        for (int i = 1; i <= 3; i++) {
            tracker.pushEvent("thread", new AguiEvent.RunFinished("thread", "run-" + i));
        }

        String cursor = initial.eventIds().getFirst();
        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch continueReplay = new CountDownLatch(1);
        CapturingEmitter reconnected = new CapturingEmitter(replayStarted, continueReplay);
        CompletableFuture<Void> replay = CompletableFuture.runAsync(
                () -> tracker.reconnect("thread", reconnected, cursor));
        assertTrue(replayStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> live = CompletableFuture.runAsync(
                () -> tracker.pushEvent("thread", new AguiEvent.RunFinished("thread", "run-4")));
        continueReplay.countDown();
        replay.get(5, TimeUnit.SECONDS);
        live.get(5, TimeUnit.SECONDS);

        List<String> originalIds = initial.eventIds();
        String runToken = originalIds.getFirst().split(":")[0];
        assertEquals(List.of(originalIds.get(1), originalIds.get(2),
                runToken + ":4"), reconnected.eventIds());

        tracker.markCompleted("thread");
        CapturingEmitter completed = new CapturingEmitter();
        tracker.reconnect("thread", completed, reconnected.eventIds().getLast());
        assertTrue(completed.eventIds().isEmpty());
        assertTrue(completed.wire().contains("REPLAY_CAUGHT_UP"));
    }

    @Test
    void reconnectReplaysOverflowFromArchiveAndKeepsCursorAndLiveEventsInOrder() {
        RunTracker tracker = new RunTracker(new AguiEventEncoder());
        trackers.add(tracker);
        tracker.registerRun("thread", mock(Agent.class), null);
        for (int i = 1; i <= 10003; i++) {
            tracker.pushEvent("thread", new AguiEvent.TextMessageContent("thread", "run", "reply", "第" + i + "行\n"));
        }
        Object run = ((Map<?, ?>) ReflectionTestUtils.getField(tracker, "activeRuns")).get("thread");
        assertEquals(10000, ((java.util.Collection<?>) ReflectionTestUtils.getField(run, "eventBuffer")).size());
        Path archive = (Path) ReflectionTestUtils.getField(run, "replayFile");
        assertTrue(Files.exists(archive));
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.reconnect("thread", emitter);
        assertTrue(!emitter.wire().contains("REPLAY_GAP"));
        assertTrue(emitter.wire().contains("第1行\\n"));
        List<String> eventIds = emitter.eventIds();
        assertEquals(10003, eventIds.size());
        String prefix = eventIds.getFirst().split(":")[0] + ":";
        for (int i = 1; i <= 10003; i++) assertEquals(prefix + i, eventIds.get(i - 1));

        CapturingEmitter resumed = new CapturingEmitter();
        tracker.reconnect("thread", resumed, prefix + "2");
        assertEquals(eventIds.subList(2, 10003), resumed.eventIds());
        tracker.pushEvent("thread", new AguiEvent.TextMessageEnd("thread", "run", "reply"));
        assertEquals(prefix + "10004", resumed.eventIds().getLast());
        tracker.markCompleted("thread");
        CapturingEmitter completed = new CapturingEmitter();
        tracker.reconnect("thread", completed);
        assertEquals(10004, completed.eventIds().size());

        tracker.registerRun("thread", mock(Agent.class), null);
        assertTrue(!Files.exists(archive));
        CapturingEmitter changed = new CapturingEmitter();
        tracker.reconnect("thread", changed, prefix + "10004");
        assertTrue(changed.wire().contains("REPLAY_GAP"));
    }

    @Test
    void missingArchiveReportsGapButDoesNotInterruptLiveOutput() throws IOException {
        RunTracker tracker = tracker();
        tracker.registerRun("thread", mock(Agent.class), null);
        for (int i = 0; i <= 10000; i++) tracker.pushEvent("thread", new AguiEvent.RunFinished("thread", "run"));
        Object run = ((Map<?, ?>) ReflectionTestUtils.getField(tracker, "activeRuns")).get("thread");
        ((java.io.Writer) ReflectionTestUtils.getField(run, "replayWriter")).close();
        Path archive = (Path) ReflectionTestUtils.getField(run, "replayFile");
        Files.delete(archive);
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.reconnect("thread", emitter);
        assertTrue(emitter.wire().contains("REPLAY_GAP"));
        assertTrue(tracker.getStatus("thread"));
        tracker.pushEvent("thread", new AguiEvent.RunFinished("thread", "run"));
        assertTrue(tracker.getStatus("thread"));
    }

    private RunTracker tracker() {
        AguiEventEncoder encoder = mock(AguiEventEncoder.class);
        when(encoder.encodeToJson(any())).thenReturn("{\"type\":\"RUN_FINISHED\"}");
        RunTracker tracker = new RunTracker(encoder);
        trackers.add(tracker);
        return tracker;
    }

    private static class CapturingEmitter extends SseEmitter {
        private final List<String> frames = new ArrayList<>();
        private final CountDownLatch replayStarted;
        private final CountDownLatch continueReplay;

        CapturingEmitter() {
            this(null, null);
        }

        CapturingEmitter(CountDownLatch replayStarted, CountDownLatch continueReplay) {
            this.replayStarted = replayStarted;
            this.continueReplay = continueReplay;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            String frame = builder.build().stream()
                    .map(part -> String.valueOf(part.getData()))
                    .reduce("", String::concat);
            if (replayStarted != null && frame.contains("id:") && replayStarted.getCount() > 0) {
                replayStarted.countDown();
                try {
                    if (!continueReplay.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to resume replay");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            frames.add(frame);
        }

        synchronized List<String> eventIds() {
            List<String> ids = new ArrayList<>();
            for (String frame : frames) {
                Matcher matcher = EVENT_ID.matcher(frame);
                if (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
            return ids;
        }

        synchronized String wire() {
            return String.join("", frames);
        }
    }
}
