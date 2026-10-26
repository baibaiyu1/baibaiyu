package com.gmd.faq.server.main.engine.knowledge;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.State;

/** 每轮额度在并发工具之间共享，暂停续跑从同一身份的状态槽恢复。 */
final class KnowledgeRetrievalBudget {
    private static final String KEY = "gmd.knowledge.budget";
    private int calls;
    private boolean automaticRetrieved;

    private KnowledgeRetrievalBudget(int calls, boolean automaticRetrieved) {
        this.calls = calls;
        this.automaticRetrieved = automaticRetrieved;
    }

    static void initialize(Agent agent, RuntimeContext context, boolean newQuestion) {
        Saved saved = null;
        if (!newQuestion && agent instanceof ReActAgent react && react.getStateStore() != null && context.getSessionId() != null) {
            saved = react.getStateStore().get(context.getUserId(), context.getSessionId(), KEY, Saved.class).orElse(null);
        }
        var budget = new KnowledgeRetrievalBudget(saved == null ? 0 : saved.calls(), saved != null && saved.automaticRetrieved());
        context.put(KEY, budget);
        budget.save(agent, context);
    }

    static KnowledgeRetrievalBudget of(Agent agent, RuntimeContext context) {
        KnowledgeRetrievalBudget budget = context.get(KEY);
        if (budget == null) {
            initialize(agent, context, false);
            budget = context.get(KEY);
        }
        return budget;
    }

    synchronized boolean claim(Agent agent, RuntimeContext context, int maximum) {
        if (calls >= maximum) return false;
        calls++;
        save(agent, context);
        return true;
    }

    synchronized boolean hasRemaining(int maximum) { return calls < maximum; }

    synchronized boolean automaticRetrieved() { return automaticRetrieved; }

    synchronized void automaticRetrieved(Agent agent, RuntimeContext context) {
        automaticRetrieved = true;
        save(agent, context);
    }

    private void save(Agent agent, RuntimeContext context) {
        if (agent instanceof ReActAgent react && react.getStateStore() != null && context.getSessionId() != null) {
            react.getStateStore().save(context.getUserId(), context.getSessionId(), KEY, new Saved(calls, automaticRetrieved));
        }
    }

    public record Saved(int calls, boolean automaticRetrieved) implements State {}
}
