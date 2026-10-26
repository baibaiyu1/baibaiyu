package com.gmd.faq.server.main.runtime.workspace;

import com.gmd.faq.server.main.common.dto.WorkspaceFileWriteDTO;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.plan.AgentPlanService;
import com.gmd.faq.server.main.engine.plan.PlanWorkspace;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 原生方案文件复用现有工作区服务，不使用 SDK 全局默认目录。 */
@Component
@RequiredArgsConstructor
public class PlanWorkspaceAdapter implements PlanWorkspace {
    private final WorkspaceService workspace;

    @Override public void write(AgentContext owner, String path, String content) {
        withTenant(owner, () -> {
            var input = new WorkspaceFileWriteDTO();
            input.setSessionId(owner.getThreadId()); input.setFilePath(path); input.setContent(content);
            workspace.writeFileContent(input);
        });
    }

    @Override public String read(AgentContext owner, String path) {
        var output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                if ((long) count + length > AgentPlanService.MAX_BYTES) throw new IllegalStateException("方案文件超过大小限制");
                super.write(bytes, offset, length);
            }
            @Override public synchronized void write(int value) {
                if (count >= AgentPlanService.MAX_BYTES) throw new IllegalStateException("方案文件超过大小限制");
                super.write(value);
            }
        };
        withTenant(owner, () -> workspace.downloadFile(owner.getThreadId(), path, output));
        return output.toString(StandardCharsets.UTF_8);
    }

    private void withTenant(AgentContext owner, Runnable action) {
        Long previousId = TenantUtils.getCurrentTenantId();
        String previousCode = TenantUtils.getCurrentTenantCode();
        try {
            TenantUtils.setCurrentTenant(owner.getTenantId(), owner.getTenantCode());
            action.run();
        } finally {
            TenantUtils.clear();
            if (previousId != null) TenantUtils.setCurrentTenant(previousId, previousCode);
        }
    }
}
