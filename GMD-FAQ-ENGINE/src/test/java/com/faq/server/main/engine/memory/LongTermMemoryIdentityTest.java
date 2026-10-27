package com.gmd.faq.server.main.engine.memory;

import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.memory.longterm.Mem0LongTermMemoryBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryIdentityTest {
    private final LongTermMemoryBuilder builder = new Mem0LongTermMemoryBuilder();
    @Test void keysIgnoreNamesAndSeparateTenantsAndAccounts() {
        var first = context(1L, 9L); var other = context(1L, 12L);
        assertNotEquals(builder.resolveUserId(first), builder.resolveUserId(other));
        String stable = builder.resolveUserId(first);
        first.getUserInfo().setNickname("修改昵称"); first.getUserInfo().setUsername("修改账号");
        assertEquals(stable, builder.resolveUserId(first));
        assertNotEquals(stable, builder.resolveUserId(context(2L, 9L)));
        assertEquals(stable, builder.resolveUserId(context(1L, 9L)));
    }
    @Test void missingIdentityCannotFallBackToASharedDefaultUser() {
        assertThrows(IllegalStateException.class, () -> builder.resolveUserId(null));
        assertThrows(IllegalStateException.class, () -> builder.resolveUserId(context(null, 9L)));
        assertThrows(IllegalStateException.class, () -> builder.resolveUserId(context(1L, null)));
    }
    private AgentContext context(Long tenant, Long id) {
        var user = new AccountVO(); user.setId(id); user.setNickname("重名账号"); user.setUsername("account");
        var context = new AgentContext(); context.setTenantId(tenant); context.setUserInfo(user); return context;
    }
}
