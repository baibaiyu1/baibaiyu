package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.AgentTelemetryService;
import com.gmd.faq.server.main.common.consts.TableConst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AGENTSCOPE_TEST_MYSQL_URL",matches=".*/gmd_agentscope_upgrade_check.*")
class AgentTelemetryMysqlTest {
    @Test void actualUsageAggregatesByRunAndIdentityWithoutCountingRunOrToolRows() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("AGENTSCOPE_TEST_MYSQL_URL"),
                System.getenv("AGENTSCOPE_TEST_MYSQL_USER"),System.getenv("AGENTSCOPE_TEST_MYSQL_PASSWORD")));
        jdbc.update("DELETE FROM "+TableConst.AGENT_TELEMETRY);
        var service=new AgentTelemetryService(jdbc);var scope=new AgentTelemetryService.Scope(71,72,73,"run",74);
        var id=service.begin(scope,"MODEL","fixture");
        service.end(id,"SUCCEEDED",12,new AgentTelemetryService.Usage(20,6,2,3,0,0),null,null);
        service.end(id,"SUCCEEDED",12,new AgentTelemetryService.Usage(999,999,0,0,0,0),null,null);
        service.begin(scope,"COMPACTION","summary-fixture");
        service.end(service.begin(scope,"RUN",null),"FAILED",100,null,"AGENT",new IllegalArgumentException("must-not-persist-secret"));
        service.end(service.begin(scope,"TOOLS",null),"SUCCEEDED",80,null,null,null);
        service.end(service.begin(scope,"MODEL_RETRY","fixture"),"SUCCEEDED",0,null,"MODEL",new IllegalStateException("must-not-persist-secret"));
        service.end(service.begin(scope,"MODEL_SWITCH","backup"),"SUCCEEDED",0,null,"MODEL",null);
        assertTrue(service.list(75,72,73).isEmpty());assertTrue(service.summary(71,75).isEmpty());
        var run=service.runs(71,72,73).getFirst();
        assertEquals(20L,((Number)run.get("inputTokens")).longValue());assertEquals(6L,((Number)run.get("outputTokens")).longValue());
        assertEquals(2L,((Number)run.get("modelCalls")).longValue());assertEquals(1L,((Number)run.get("unknownCalls")).longValue());
        assertEquals(1L,((Number)run.get("retries")).longValue());assertEquals(1L,((Number)run.get("switches")).longValue());
        assertFalse(service.list(71,72,73).toString().contains("must-not-persist-secret"));
        service.clearSession(71,75,73);assertEquals(6,service.list(71,72,73).size());
        service.clearSession(71,72,73);assertTrue(service.list(71,72,73).isEmpty());
    }
}
