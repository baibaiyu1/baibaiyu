package com.gmd.faq.server.main.shellproxy.service;

import com.gmd.faq.server.main.shellproxy.config.ShellProperties;
import com.gmd.faq.server.main.shellproxy.executor.ShellExecutor;
import com.gmd.faq.server.main.shellproxy.model.ShellExecuteRequest;
import com.gmd.faq.server.main.shellproxy.model.ShellExecuteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 描述：Shell执行服务层
 * <p>
 * 负责参数校验、超时裁剪、调用执行器，并记录执行日志。
 *
 **/
@Service
public class ShellService {

    private static final Logger log = LoggerFactory.getLogger(ShellService.class);

    private final ShellExecutor shellExecutor;
    private final ShellProperties shellProperties;

    public ShellService(ShellExecutor shellExecutor, ShellProperties shellProperties) {
        this.shellExecutor = shellExecutor;
        this.shellProperties = shellProperties;
    }

    /**
     * 执行Shell命令
     *
     * @param request 执行请求
     * @return 执行响应
     * @throws IllegalArgumentException 参数校验失败时抛出
     */
    public ShellExecuteResponse execute(ShellExecuteRequest request) {
        validateRequest(request);

        int timeout = resolveTimeout(request.getTimeout());

        log.info("shell execute start, timeout={}s{}", timeout,
                shellProperties.isLogCommand()
                        ? ", command=" + truncateCommand(request.getCommand())
                        : "");

        ShellExecuteResponse response = shellExecutor.execute(
                request.getCommand(),
                timeout,
                request.getCharset(),
                request.getWorkingDirectory(),
                shellProperties.getMaxOutputSize()
        );

        log.info("shell execute finish, returnCode={}, duration={}ms, success={}",
                response.getReturnCode(),
                response.getDurationMs(),
                response.isSuccess());

        return response;
    }

    /**
     * 请求参数校验，不合法时抛出 IllegalArgumentException
     */
    private void validateRequest(ShellExecuteRequest request) {
        if (request.getCommand() == null || request.getCommand().isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        if (request.getWorkingDirectory() != null && !request.getWorkingDirectory().isBlank()) {
            File dir = new File(request.getWorkingDirectory());
            if (!dir.exists() || !dir.isDirectory()) {
                throw new IllegalArgumentException(
                        "working directory not found: " + request.getWorkingDirectory());
            }
        }
    }

    /**
     * 解析超时时间，确保在合理范围内
     */
    private int resolveTimeout(Integer requestTimeout) {
        if (requestTimeout == null || requestTimeout <= 0) {
            return shellProperties.getDefaultTimeout();
        }
        return Math.min(requestTimeout, shellProperties.getMaxTimeout());
    }

    /**
     * 截断命令用于日志输出，避免过长
     */
    private String truncateCommand(String command) {
        if (command.length() > 200) {
            return command.substring(0, 200) + "...";
        }
        return command;
    }
}
