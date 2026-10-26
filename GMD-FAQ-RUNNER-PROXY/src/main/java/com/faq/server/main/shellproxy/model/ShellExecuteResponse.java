package com.gmd.faq.server.main.shellproxy.model;

/**
 * 描述：Shell执行响应（成功/失败/超时统一使用此结构）
 *
 **/
public class ShellExecuteResponse {

    /** 执行是否成功 */
    private boolean success;

    /** 进程退出码，0=成功，-1=超时 */
    private int returnCode;

    /** 标准输出 */
    private String stdout;

    /** 标准错误输出 */
    private String stderr;

    /** 执行耗时（毫秒） */
    private long durationMs;

    public ShellExecuteResponse() {
    }

    public ShellExecuteResponse(boolean success, int returnCode, String stdout,
                                String stderr, long durationMs) {
        this.success = success;
        this.returnCode = returnCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.durationMs = durationMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getReturnCode() {
        return returnCode;
    }

    public void setReturnCode(int returnCode) {
        this.returnCode = returnCode;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * Builder模式，兼容原Lombok @Builder用法
     */
    public static class Builder {
        private boolean success;
        private int returnCode;
        private String stdout;
        private String stderr;
        private long durationMs;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder returnCode(int returnCode) {
            this.returnCode = returnCode;
            return this;
        }

        public Builder stdout(String stdout) {
            this.stdout = stdout;
            return this;
        }

        public Builder stderr(String stderr) {
            this.stderr = stderr;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public ShellExecuteResponse build() {
            return new ShellExecuteResponse(success, returnCode, stdout, stderr, durationMs);
        }
    }
}
