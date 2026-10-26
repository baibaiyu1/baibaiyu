package com.gmd.faq.server.main.shellproxy.controller;

import com.gmd.faq.server.main.shellproxy.model.ShellExecuteRequest;
import com.gmd.faq.server.main.shellproxy.model.ShellExecuteResponse;
import com.gmd.faq.server.main.shellproxy.service.ShellService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 描述：Shell执行代理接口层
 * <p>
 * 命令执行结果统一返回200，请求参数错误返回400，服务内部异常返回500。
 *
 **/
@RestController
@RequestMapping("/api/proxy")
public class ShellController {

    private static final Logger log = LoggerFactory.getLogger(ShellController.class);

    private final ShellService shellService;

    public ShellController(ShellService shellService) {
        this.shellService = shellService;
    }

    /**
     * 执行Shell命令
     *
     * @param request 执行请求
     * @return 执行响应
     */
    @PostMapping("/execute/shell")
    public ResponseEntity<ShellExecuteResponse> executeShell(@RequestBody ShellExecuteRequest request) {
        try {
            return ResponseEntity.ok(shellService.execute(request));
        } catch (IllegalArgumentException e) {
            log.warn("invalid shell request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ShellExecuteResponse.builder()
                    .success(false)
                    .returnCode(-1)
                    .stdout("")
                    .stderr(e.getMessage())
                    .durationMs(0)
                    .build());
        } catch (Exception e) {
            log.error("unexpected error during shell execution", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ShellExecuteResponse.builder()
                            .success(false)
                            .returnCode(-1)
                            .stdout("")
                            .stderr("internal server error")
                            .durationMs(0)
                            .build());
        }
    }
}
