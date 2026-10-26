package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.common.consts.TableConst;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentTelemetryService {
    private final JdbcTemplate jdbc;
    public record Scope(long tenantId,long userId,long sessionId,String runId,long agentId) {}
    public record Usage(long input,long output,long reasoning,long cached,long cacheCreation,long toolPrompt) {}
    public String begin(Scope scope,String operation,String model) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO " + TableConst.AGENT_TELEMETRY
                        + " (id,tenant_id,user_id,session_id,run_id,agent_id,operation,model_name,state) VALUES (?,?,?,?,?,?,?,?,'RUNNING')",
                id,scope.tenantId,scope.userId,scope.sessionId,scope.runId,scope.agentId,operation,model);
        return id;
    }
    public void end(String id,String state,long elapsed,Usage usage,String stage,Throwable error) {
        jdbc.update("UPDATE " + TableConst.AGENT_TELEMETRY
                        + " SET state=?,elapsed_ms=?,input_tokens=?,output_tokens=?,reasoning_tokens=?,cached_tokens=?,"
                        + "cache_creation_tokens=?,tool_prompt_tokens=?,error_stage=?,error_type=?,completed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='RUNNING'",
                state,elapsed,usage==null?null:usage.input,usage==null?null:usage.output,usage==null?null:usage.reasoning,
                usage==null?null:usage.cached,usage==null?null:usage.cacheCreation,usage==null?null:usage.toolPrompt,
                stage,error==null?null:error.getClass().getSimpleName(),id);
    }
    public List<Map<String,Object>> list(long tenant,long user,long session) {
        return jdbc.queryForList("SELECT id,run_id AS runId,CAST(agent_id AS CHAR) AS agentId,operation,model_name AS modelName,state,"
                        + "elapsed_ms AS elapsedMs,input_tokens AS inputTokens,output_tokens AS outputTokens,reasoning_tokens AS reasoningTokens,"
                        + "cached_tokens AS cachedTokens,cache_creation_tokens AS cacheCreationTokens,tool_prompt_tokens AS toolPromptTokens,"
                        + "error_stage AS errorStage,error_type AS errorType,started_at AS startedAt FROM " + TableConst.AGENT_TELEMETRY
                        + " WHERE tenant_id=? AND user_id=? AND session_id=? ORDER BY started_at DESC,id LIMIT 200",tenant,user,session);
    }
    public List<Map<String,Object>> runs(long tenant,long user,long session) {
        return jdbc.queryForList("SELECT run_id AS runId,MAX(CASE WHEN operation='RUN' THEN state END) AS state,"
                + "MAX(CASE WHEN operation='RUN' THEN elapsed_ms END) AS elapsedMs,"
                + "SUM(CASE WHEN operation IN ('MODEL','COMPACTION','VOICE') THEN 1 ELSE 0 END) AS modelCalls,"
                + "SUM(CASE WHEN operation IN ('MODEL','COMPACTION','VOICE') AND input_tokens IS NULL THEN 1 ELSE 0 END) AS unknownCalls,"
                + "SUM(CASE WHEN operation='MODEL_RETRY' THEN 1 ELSE 0 END) AS retries,"
                + "SUM(CASE WHEN operation='MODEL_SWITCH' THEN 1 ELSE 0 END) AS switches,"
                + "SUM(input_tokens) AS inputTokens,SUM(output_tokens) AS outputTokens,SUM(reasoning_tokens) AS reasoningTokens,"
                + "SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END) AS failures,MAX(started_at) AS startedAt FROM " + TableConst.AGENT_TELEMETRY
                + " WHERE tenant_id=? AND user_id=? AND session_id=? GROUP BY run_id ORDER BY startedAt DESC LIMIT 20",tenant,user,session);
    }
    /** 聚合只覆盖当前账号，负责人/管理员跨用户查看需经具体会话授权。 */
    public List<Map<String,Object>> summary(long tenant,long user) {
        return jdbc.queryForList("SELECT CAST(agent_id AS CHAR) AS agentId,COUNT(*) AS modelCalls,SUM(input_tokens) AS inputTokens,"
                + "SUM(output_tokens) AS outputTokens,SUM(reasoning_tokens) AS reasoningTokens,"
                + "SUM(CASE WHEN input_tokens IS NULL THEN 1 ELSE 0 END) AS unknownCalls,SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END) AS failures"
                + " FROM " + TableConst.AGENT_TELEMETRY + " WHERE tenant_id=? AND user_id=? AND operation IN ('MODEL','COMPACTION','VOICE') GROUP BY agent_id",tenant,user);
    }
    public void clearSession(long tenant,long user,long session) {
        jdbc.update("DELETE FROM " + TableConst.AGENT_TELEMETRY + " WHERE tenant_id=? AND user_id=? AND session_id=?",tenant,user,session);
    }
}
