package com.gmd.faq.server.main.console.knowledge;

import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.knowledge.service.LightRagManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge/config/{id}/lightrag")
@PermNeed({"knowledge:config:edit"})
public class LightRagManagementController {
    private final LightRagManagementService management;

    @GetMapping("/management")
    public R<Boolean> prepare(@PathVariable("id") Long id) {
        try {
            return R.data(management.prepare(id));
        } catch (ResponseStatusException ex) {
            return R.fail(ex.getReason());
        } catch (RestClientException ex) {
            return R.fail("无法连接知识图谱服务，请检查服务地址、API 密钥和服务状态");
        }
    }

    /** 原生前端协议通过 response 直接透传，普通平台接口仍使用 R。 */
    @RequestMapping(value = "/{*path}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
    public R<Void> proxy(@PathVariable("id") Long id, @PathVariable("path") String path,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        management.proxy(id, path, request, response);
        return null;
    }
}
