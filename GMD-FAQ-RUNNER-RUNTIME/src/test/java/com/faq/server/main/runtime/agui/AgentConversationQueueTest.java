package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.AgentConversationQueue;
import com.gmd.faq.server.main.agent.service.AgentInstructionService;
import com.gmd.faq.server.main.common.consts.TableConst;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AGENTSCOPE_TEST_MYSQL_URL",matches=".*/gmd_agentscope_upgrade_check.*")
class AgentConversationQueueTest {
    private JdbcTemplate jdbc;
    private AgentConversationQueue queue;
    private AgentInstructionService inbox;
    private final AgentConversationQueue.Scope scope=new AgentConversationQueue.Scope(71,72,73);
    private final AgentConversationQueue.Options options=new AgentConversationQueue.Options(true,false,false,List.of(),false);
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("AGENTSCOPE_TEST_MYSQL_URL"),System.getenv("AGENTSCOPE_TEST_MYSQL_USER"),System.getenv("AGENTSCOPE_TEST_MYSQL_PASSWORD"));
        jdbc=spy(new JdbcTemplate(source));
        jdbc.update("DELETE FROM "+TableConst.AGENT_QUEUED_MESSAGE); jdbc.update("DELETE FROM "+TableConst.AGENT_RUN_LANE);
        jdbc.update("DELETE FROM "+TableConst.AGENT_INSTRUCTION); jdbc.update("DELETE FROM "+TableConst.AGENT_INSTRUCTION_RUN);
        var manager=new DataSourceTransactionManager(source);
        inbox=transactional(new AgentInstructionService(jdbc),manager);
        queue=transactional(new AgentConversationQueue(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),inbox),manager);
    }
    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target,DataSourceTransactionManager manager) {
        var proxy=new TransactionProxyFactoryBean(); proxy.setTarget(target); proxy.setProxyTargetClass(true);
        proxy.setTransactionManager(manager);
        var attributes=new Properties(); attributes.setProperty("*","PROPAGATION_REQUIRED"); proxy.setTransactionAttributes(attributes); proxy.afterPropertiesSet();
        return (T)proxy.getObject();
    }
    @Test void sequenceIdempotencyEditCancelAndOwnership() {
        queue.begin(scope,"first","node",false);
        var first=queue.enqueue(scope,"one","任务一",options);
        assertEquals(first,queue.enqueue(scope,"one","任务一",options));
        assertThrows(IllegalArgumentException.class,() -> queue.enqueue(scope,"one","不同内容",options));
        var second=queue.enqueue(scope,"two","任务二",options);
        assertNull(queue.claim(scope,"another","node"));
        queue.finish(scope,"first",false,false);
        var claimed=queue.claim(scope,"second","node");
        assertEquals(first.id(),claimed.message().id());
        assertThrows(IllegalStateException.class,() -> queue.edit(scope,Long.parseLong(first.id()),"已执行不可改"));
        var other=new AgentConversationQueue.Scope(71,74,73);
        assertThrows(IllegalStateException.class,() -> queue.cancel(other,Long.parseLong(second.id())));
        queue.edit(scope,Long.parseLong(second.id()),"修改后");
        queue.cancel(scope,Long.parseLong(second.id()));
        assertTrue(queue.snapshot(other).messages().isEmpty());
        queue.finish(scope,"second",false,false);
        assertEquals(List.of("COMPLETED","CANCELLED"),queue.snapshot(scope).messages().stream().map(AgentConversationQueue.Queued::state).toList());
    }
    @Test void competingDispatchAndManualRunsCannotExecuteSameSession() throws Exception {
        queue.enqueue(scope,"one","任务",options); queue.preferNode(scope,"node");
        var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> {ready.await();return queue.claim(scope,"a","node");});
            var two=pool.submit(() -> {ready.await();return queue.claim(scope,"b","node");});
            ready.countDown();
            assertEquals(1,List.of(Optional.ofNullable(one.get(5,TimeUnit.SECONDS)),Optional.ofNullable(two.get(5,TimeUnit.SECONDS))).stream().filter(Optional::isPresent).count());
        }
        assertThrows(IllegalStateException.class,() -> queue.begin(scope,"manual","other",false));
        assertThrows(IllegalStateException.class,() -> queue.clearSession(71,72,73));
        assertFalse(queue.owns(scope,queue.snapshot(scope).runId(),"other"));
    }
    @Test void approvalStopAndCrashNeverReplayStartedMessages() {
        queue.enqueue(scope,"one","任务一",options); queue.enqueue(scope,"two","任务二",options); queue.preferNode(scope,"node");
        queue.claim(scope,"first","node"); queue.finish(scope,"first",true,false);
        assertNull(queue.claim(scope,"ignored","node"));
        assertThrows(IllegalStateException.class,() -> queue.begin(scope,"manual","node",false));
        queue.begin(scope,"resume","node",true); queue.finish(scope,"resume",false,false);
        queue.claim(scope,"second","node"); queue.pause(scope);
        assertFalse(queue.owns(scope,"second","node"));
        queue.finish(scope,"second",false,false);
        assertTrue(queue.snapshot(scope).paused());
        queue.enqueue(scope,"three","任务三",options); queue.continueQueue(scope,"node"); queue.claim(scope,"third","node");
        jdbc.update("UPDATE "+TableConst.AGENT_RUN_LANE+" SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        queue.expire();
        assertEquals("INTERRUPTED",queue.snapshot(scope).state());
        assertFalse(queue.renew(scope,"third","node"));
        queue.finish(scope,"third",false,false);
        assertEquals("INTERRUPTED",queue.snapshot(scope).state(),"迟到的完成回调不能覆盖中断状态");
        queue.continueQueue(scope,"new-node"); assertNull(queue.claim(scope,"retry","new-node"));
        assertEquals(List.of("COMPLETED","INTERRUPTED","INTERRUPTED"),queue.snapshot(scope).messages().stream().map(AgentConversationQueue.Queued::state).toList());
    }
    @Test void boundedQueueExpiresAndCanBeCleared() {
        for(int i=0;i<20;i++) queue.enqueue(scope,"item-"+i,"任务"+i,options);
        assertThrows(IllegalStateException.class,() -> queue.enqueue(scope,"overflow","超限",options));
        jdbc.update("UPDATE "+TableConst.AGENT_QUEUED_MESSAGE+" SET created_at=DATE_SUB(NOW(3),INTERVAL 2 DAY)");
        assertTrue(queue.snapshot(scope).messages().stream().allMatch(m -> m.state().equals("EXPIRED")));
        queue.clearSession(71,72,73); assertTrue(queue.snapshot(scope).messages().isEmpty());
    }
    @Test void queuedWorkOnLostIdleNodeRequiresExplicitContinue() {
        queue.enqueue(scope,"one","等待执行",options); queue.preferNode(scope,"old-node");
        jdbc.update("UPDATE "+TableConst.AGENT_RUN_LANE+" SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        queue.renewIdle("old-node"); queue.expire();
        assertTrue(queue.snapshot(scope).paused());
        assertEquals("INTERRUPTED",queue.snapshot(scope).state());
        queue.continueQueue(scope,"new-node");
        assertNotNull(queue.claim(scope,"new-run","new-node"));
    }

    @Test void steeringIsIdempotentAndNeverRunsAgainAsAQueuedTurn() {
        queue.begin(scope,"active","node",false);
        var active=new AgentInstructionService.Scope(71,72,73,"active"); inbox.begin(active,74);
        var first=queue.enqueue(scope,"first","先处理这个要求",options);
        var second=queue.enqueue(scope,"second","下一轮",options);
        var steered=queue.steer(scope,Long.parseLong(first.id()),"active","node");
        assertEquals("STEERED",steered.state());
        assertEquals("active",steered.runId());
        assertEquals(steered,queue.steer(scope,Long.parseLong(first.id()),"active","node"));
        assertEquals(List.of("先处理这个要求"),inbox.claim(active).stream().map(AgentInstructionService.Instruction::content).toList());
        inbox.finish(active,"ENDED"); queue.finish(scope,"active",false,false);
        assertEquals(second.id(),queue.claim(scope,"next","node").message().id());
        assertEquals(steered,queue.steer(scope,Long.parseLong(first.id()),"active","node"),"迟到重试应返回原结果，不投递到新任务");
        assertThrows(IllegalStateException.class,() -> queue.steer(scope,Long.parseLong(first.id()),"next","node"));
        assertEquals(1,inbox.list(71,72,73).size());
    }

    @Test void rejectedSteeringPreservesTheQueuedMessage() {
        queue.begin(scope,"active","node",false);
        var active=new AgentInstructionService.Scope(71,72,73,"active"); inbox.begin(active,74);
        var item=queue.enqueue(scope,"one","要求",options); long id=Long.parseLong(item.id());
        assertThrows(IllegalStateException.class,() -> queue.steer(scope,id,"stale","node"));
        assertThrows(IllegalStateException.class,() -> queue.steer(scope,id,"active","another-node"));
        assertThrows(IllegalStateException.class,() -> queue.steer(new AgentConversationQueue.Scope(71,75,73),id,"active","node"));
        var attachment=queue.enqueue(scope,"file","附件",new AgentConversationQueue.Options(true,false,false,List.of("123"),false));
        assertThrows(IllegalArgumentException.class,() -> queue.steer(scope,Long.parseLong(attachment.id()),"active","node"));
        queue.edit(scope,id,"字".repeat(4001));
        assertThrows(IllegalArgumentException.class,() -> queue.steer(scope,id,"active","node"));
        queue.edit(scope,id,"要求");
        inbox.finish(active,"WAITING");
        assertThrows(IllegalStateException.class,() -> queue.steer(scope,id,"active","node"));
        queue.pause(scope);
        assertThrows(IllegalStateException.class,() -> queue.steer(scope,id,"active","node"));
        assertTrue(queue.snapshot(scope).messages().stream().allMatch(m -> "QUEUED".equals(m.state())));
        assertTrue(inbox.list(71,72,73).isEmpty());
    }

    @Test void steeringRollsBackTheInboxIfTheQueueUpdateFails() {
        queue.begin(scope,"active","node",false);
        inbox.begin(new AgentInstructionService.Scope(71,72,73,"active"),74);
        var item=queue.enqueue(scope,"one","要求",options);
        doThrow(new IllegalStateException("模拟队列写入失败")).when(jdbc).update(
                startsWith("UPDATE "+TableConst.AGENT_QUEUED_MESSAGE+" SET state='STEERED'"),any(Object[].class));
        assertThrows(IllegalStateException.class,() -> queue.steer(scope,Long.parseLong(item.id()),"active","node"));
        assertEquals("QUEUED",queue.snapshot(scope).messages().getFirst().state());
        assertTrue(inbox.list(71,72,73).isEmpty(),"队列转换未提交时，收件箱也必须回滚");
    }

    @Test void concurrentSteeringAndDispatchOnlyConsumeTheMessageOnce() throws Exception {
        queue.enqueue(scope,"one","要求",options); queue.preferNode(scope,"node");
        var id=Long.parseLong(queue.snapshot(scope).messages().getFirst().id());
        inbox.begin(new AgentInstructionService.Scope(71,72,73,"next"),74);
        var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var dispatch=pool.submit(() -> {ready.await();return queue.claim(scope,"next","node");});
            var steer=pool.submit(() -> {
                ready.await();
                try { queue.steer(scope,id,"next","node"); return true; }
                catch (IllegalStateException expected) { return false; }
            });
            ready.countDown();
            assertNotNull(dispatch.get(5,TimeUnit.SECONDS));
            assertFalse(steer.get(5,TimeUnit.SECONDS));
        }
        assertEquals("RUNNING",queue.snapshot(scope).messages().getFirst().state());
        assertTrue(inbox.list(71,72,73).isEmpty());
    }
}
