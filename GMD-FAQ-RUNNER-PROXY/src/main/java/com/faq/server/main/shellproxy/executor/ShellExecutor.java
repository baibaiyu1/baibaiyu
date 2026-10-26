package com.gmd.faq.server.main.shellproxy.executor;

import com.gmd.faq.server.main.shellproxy.model.ShellExecuteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * 描述：Shell命令核心执行器
 * <p>
 * 采用双线程分别读取stdout和stderr，防止管道缓冲区满导致进程死锁。
 * 支持超时控制、工作目录设置、字符集选择、输出大小限制。
 *
 **/
@Component
public class ShellExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellExecutor.class);

    /**
     * 执行Shell命令
     *
     * @param command          命令内容
     * @param timeoutSeconds   超时时间（秒）
     * @param charsetName      字符集名称
     * @param workingDirectory 工作目录路径
     * @param maxOutputSize    最大输出字节数限制
     * @return 执行响应
     */
    public ShellExecuteResponse execute(String command, int timeoutSeconds,
                                         String charsetName, String workingDirectory,
                                         long maxOutputSize) {
        long startTime = System.currentTimeMillis();

        Process process = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;

        // 分别收集stdout和stderr（两个线程操作不同对象，无竞争）
        final StringBuilder stdoutBuilder = new StringBuilder();
        final StringBuilder stderrBuilder = new StringBuilder();

        // 输出大小溢出标记
        final boolean[] stdoutOverflow = {false};
        final boolean[] stderrOverflow = {false};

        try {
            // 根据操作系统选择shell
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }

            // 设置工作目录（目录有效性由调用方校验）
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                pb.directory(new File(workingDirectory));
            }

            // 保持stdout和stderr独立管道，双线程分别读取防止死锁
            pb.redirectErrorStream(false);

            process = pb.start();

            Charset charset = parseCharset(charsetName);

            // 双线程分别读取stdout和stderr，防止管道缓冲区满导致死锁
            stdoutThread = startReaderThread(process, charset, stdoutBuilder, maxOutputSize, stdoutOverflow, "stdout");
            stderrThread = startReaderThread(process, charset, stderrBuilder, maxOutputSize, stderrOverflow, "stderr");
            stdoutThread.start();
            stderrThread.start();

            // 等待进程完成或超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            // 等待读取线程完成
            stdoutThread.join(5000);
            stderrThread.join(5000);

            long duration = System.currentTimeMillis() - startTime;

            // 溢出时立即销毁进程，防止无限输出继续消耗资源
            if (stdoutOverflow[0] || stderrOverflow[0]) {
                destroyProcess(process);
            }

            if (!finished) {
                // 超时处理
                return ShellExecuteResponse.builder()
                        .success(false)
                        .returnCode(-1)
                        .stdout(truncateIfOverflow(stdoutBuilder.toString(), stdoutOverflow[0]))
                        .stderr(truncateIfOverflow(
                                "execution timeout after " + timeoutSeconds + "s. " + stderrBuilder.toString(),
                                stderrOverflow[0]))
                        .durationMs(duration)
                        .build();
            }

            int exitCode = process.exitValue();

            return ShellExecuteResponse.builder()
                    .success(exitCode == 0)
                    .returnCode(exitCode)
                    .stdout(truncateIfOverflow(stdoutBuilder.toString(), stdoutOverflow[0]))
                    .stderr(truncateIfOverflow(stderrBuilder.toString(), stderrOverflow[0]))
                    .durationMs(duration)
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("shell execution error", e);
            return ShellExecuteResponse.builder()
                    .success(false)
                    .returnCode(-1)
                    .stdout("")
                    .stderr("execution error: " + e.getMessage())
                    .durationMs(duration)
                    .build();
        } finally {
            // 确保进程被清理
            if (process != null && process.isAlive()) {
                destroyProcess(process);
            }
            // 中断可能仍在运行的读取线程
            if (stdoutThread != null && stdoutThread.isAlive()) {
                stdoutThread.interrupt();
            }
            if (stderrThread != null && stderrThread.isAlive()) {
                stderrThread.interrupt();
            }
        }
    }

    /**
     * 启动输出读取线程
     * <p>
     * 通过InputStreamReader获取对应流，而非通过Process.getInputStream()/
     * getErrorStream()，因为后者返回的是原始字节流，需指定字符集。
     *
     * @param process       进程对象
     * @param charset       字符集
     * @param builder       输出收集器
     * @param maxSize       大小限制
     * @param overflow      溢出标记
     * @param streamType    流类型标识
     * @return 读取线程
     */
    private Thread startReaderThread(Process process, Charset charset,
                                      StringBuilder builder, long maxSize,
                                      boolean[] overflow, String streamType) {
        return Thread.ofVirtual().name("shell-" + streamType + "-reader").unstarted(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            "stderr".equals(streamType)
                                    ? process.getErrorStream()
                                    : process.getInputStream(),
                            charset))) {
                char[] buf = new char[8192];
                int len;
                long totalRead = 0;
                while ((len = reader.read(buf)) != -1) {
                    totalRead += len;
                    if (totalRead > maxSize) {
                        overflow[0] = true;
                        break;
                    }
                    builder.append(buf, 0, len);
                }
            } catch (Exception e) {
                log.debug("{} reader interrupted", streamType, e);
            }
        });
    }

    /**
     * 解析字符集，解析失败回退到系统默认字符集
     */
    private Charset parseCharset(String charsetName) {
        if (charsetName == null || charsetName.isBlank()) {
            return Charset.defaultCharset();
        }
        try {
            return Charset.forName(charsetName);
        } catch (Exception e) {
            log.warn("unsupported charset: {}, fallback to system default", charsetName);
            return Charset.defaultCharset();
        }
    }

    /**
     * 安全销毁进程
     */
    private void destroyProcess(Process process) {
        try {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log.debug("process destroy error", e);
        }
    }

    /**
     * 溢出时截断输出并追加提示
     */
    private String truncateIfOverflow(String output, boolean overflow) {
        if (overflow) {
            return output + "\n[OUTPUT TRUNCATED: exceeded max output size]";
        }
        return output;
    }
}
